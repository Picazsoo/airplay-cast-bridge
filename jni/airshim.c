// airshim: runs a static glibc program inside an Android app sandbox.
//
// Android's app seccomp filter only allows the syscalls bionic itself uses. glibc still uses
// some older ones (on arm32 e.g. set_robust_list, pause, accept, send, recv, poll), which the
// filter traps with SIGSYS, killing the program. This shim runs the program under ptrace;
// when a thread gets a seccomp SIGSYS, the call is re-issued as its modern equivalent
// (accept -> accept4, poll -> ppoll, ...) or made to return -ENOSYS.
// Only trapped calls stop the tracer, so the overhead is negligible.
//
// Architectures: arm (armeabi-v7a), aarch64 (arm64-v8a), x86_64.
#include <elf.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>

#define AT_FDCWD_ (-100L)
#define AT_SYMLINK_NOFOLLOW_ 0x100

// ---------------------------------------------------------------- per-architecture registers
//
// A[i]: syscall argument i, RET: return value, NR: register the syscall number is read from
// when the instruction is executed, PC/SP, and SVCLEN: size of the syscall instruction.
#if defined(__arm__)
#include <asm/ptrace.h>
typedef struct pt_regs regs_t;
#define A(r, i)    ((r)->uregs[i])
#define RET(r)     ((r)->ARM_r0)
#define NR(r)      ((r)->ARM_r7)
#define PC(r)      ((r)->ARM_pc)
#define SP(r)      ((r)->ARM_sp)
#define SVCLEN(r)  (((r)->ARM_cpsr & 0x20) ? 2 : 4)       // Thumb or ARM svc
static int getregs(pid_t p, regs_t *r) { return (int)ptrace(PTRACE_GETREGS, p, 0, r); }
static int setregs(pid_t p, regs_t *r) { return (int)ptrace(PTRACE_SETREGS, p, 0, r); }
static void no_restart(regs_t *r) { (void)r; }
#elif defined(__aarch64__)
#include <asm/ptrace.h>
typedef struct user_pt_regs regs_t;
#define A(r, i)    ((r)->regs[i])
#define RET(r)     ((r)->regs[0])
#define NR(r)      ((r)->regs[8])
#define PC(r)      ((r)->pc)
#define SP(r)      ((r)->sp)
#define SVCLEN(r)  4
static int getregs(pid_t p, regs_t *r) { struct iovec v = {r, sizeof *r}; return (int)ptrace(PTRACE_GETREGSET, p, (void *)NT_PRSTATUS, &v); }
static int setregs(pid_t p, regs_t *r) { struct iovec v = {r, sizeof *r}; return (int)ptrace(PTRACE_SETREGSET, p, (void *)NT_PRSTATUS, &v); }
static void no_restart(regs_t *r) { (void)r; }
#elif defined(__x86_64__)
typedef struct user_regs_struct regs_t;
static unsigned long *xarg(regs_t *r, int i) {
    switch (i) { case 0: return &r->rdi; case 1: return &r->rsi; case 2: return &r->rdx;
                 case 3: return &r->r10; case 4: return &r->r8; default: return &r->r9; }
}
#define A(r, i)    (*xarg((r), (i)))
#define RET(r)     ((r)->rax)
#define NR(r)      ((r)->rax)
#define PC(r)      ((r)->rip)
#define SP(r)      ((r)->rsp)
#define SVCLEN(r)  2                                        // syscall = 0f 05
static int getregs(pid_t p, regs_t *r) { return (int)ptrace(PTRACE_GETREGS, p, 0, r); }
static int setregs(pid_t p, regs_t *r) { return (int)ptrace(PTRACE_SETREGS, p, 0, r); }
// orig_rax >= 0 would make the kernel apply its own syscall-restart logic on top of ours.
static void no_restart(regs_t *r) { r->orig_rax = (unsigned long)-1; }
#else
#error unsupported architecture
#endif

#define W (sizeof(long))   // size of a long / pointer in the tracee (same ABI as the shim)

// Rewind the tracee to its syscall instruction and make it issue syscall `nr` instead.
#define RESTART(nr) do { PC(r) -= SVCLEN(r); NR(r) = (nr); no_restart(r); } while (0)

static int poke_timespec(pid_t p, unsigned long addr, long sec, long nsec) {
    if (ptrace(PTRACE_POKEDATA, p, (void *)addr, (void *)sec) < 0) return -1;
    return (int)ptrace(PTRACE_POKEDATA, p, (void *)(addr + W), (void *)nsec);
}

// poll(fds, n, ms) -> ppoll(fds, n, ts|NULL, NULL, 8)
static __attribute__((unused)) int emu_poll(pid_t p, regs_t *r, int ppoll_nr, unsigned long scratch) {
    long ms = (long)(int)A(r, 2);
    if (ms < 0) A(r, 2) = 0;
    else if (poke_timespec(p, scratch, ms / 1000, (ms % 1000) * 1000000L) == 0) A(r, 2) = scratch;
    else return 0;
    RESTART(ppoll_nr); A(r, 3) = 0; A(r, 4) = 8;
    return 1;
}

// select(n, r, w, e, tv) -> pselect6(n, r, w, e, ts|NULL, NULL)
static __attribute__((unused)) int emu_select(pid_t p, regs_t *r, int pselect_nr, unsigned long scratch) {
    unsigned long tv = A(r, 4);
    if (tv) {
        errno = 0;
        long sec = ptrace(PTRACE_PEEKDATA, p, (void *)tv, 0);
        long usec = ptrace(PTRACE_PEEKDATA, p, (void *)(tv + W), 0);
        if (errno || poke_timespec(p, scratch, sec, usec * 1000L) < 0) return 0;
        A(r, 4) = scratch;
    }
    RESTART(pselect_nr); A(r, 5) = 0;
    return 1;
}

// Shift the arguments right by one and put AT_FDCWD first: open(path, ...) -> openat(AT_FDCWD, path, ...)
static __attribute__((unused)) void at_fdcwd(regs_t *r, int nargs) {
    for (int i = nargs; i > 0; i--) A(r, i) = A(r, i - 1);
    A(r, 0) = (unsigned long)AT_FDCWD_;
}

// Returns 1 when the call was re-issued as another syscall, 0 when it was answered here.
// After a re-issue the tracer follows the call to its exit and puts back every register
// except the return value, since callers expect the argument registers to survive.
static int emulate(pid_t p, int nr, regs_t *r) {
    unsigned long scratch = (SP(r) - 512) & ~15UL;          // below the stack pointer and x86-64 red zone
    (void)scratch;
    switch (nr) {
#if defined(__arm__)
    case 29:   /* pause -> ppoll(NULL, 0, NULL, NULL) */
        RESTART(336); A(r, 0) = A(r, 1) = A(r, 2) = A(r, 3) = 0; return 1;
    case 285:  /* accept -> accept4(..., 0) */            RESTART(366); A(r, 3) = 0; return 1;
    case 289:  /* send -> sendto(..., NULL, 0) */         RESTART(290); A(r, 4) = 0; A(r, 5) = 0; return 1;
    case 291:  /* recv -> recvfrom(..., NULL, NULL) */    RESTART(292); A(r, 4) = 0; A(r, 5) = 0; return 1;
    case 252:  /* epoll_wait -> epoll_pwait(..., NULL, 8) */ RESTART(346); A(r, 4) = 0; A(r, 5) = 8; return 1;
    case 250:  /* epoll_create -> epoll_create1(0) */     RESTART(357); A(r, 0) = 0; return 1;
    case 351:  /* eventfd -> eventfd2(n, 0) */            RESTART(356); A(r, 1) = 0; return 1;
    case 42:   /* pipe -> pipe2(fds, 0) */                RESTART(359); A(r, 1) = 0; return 1;
    case 63:   /* dup2 -> dup3(a, b, 0); dup2(a, a) is a no-op */
        if (A(r, 0) == A(r, 1)) return 0;
        RESTART(358); A(r, 2) = 0; return 1;
    case 2: case 190:  /* fork/vfork -> clone(SIGCHLD, 0, ...) */
        RESTART(120); A(r, 0) = SIGCHLD; A(r, 1) = A(r, 2) = A(r, 3) = A(r, 4) = 0; return 1;
    case 168:  /* poll */     if (emu_poll(p, r, 336, scratch)) return 1; break;
    case 142:  /* _newselect */ if (emu_select(p, r, 335, scratch)) return 1; break;
#elif defined(__aarch64__)
    // arm64 has only the generic syscall table, so far fewer legacy calls exist.
    case 202:  /* accept -> accept4(..., 0) */            RESTART(242); A(r, 3) = 0; return 1;
#elif defined(__x86_64__)
    case 34:   /* pause -> ppoll(NULL, 0, NULL, NULL) */
        RESTART(271); A(r, 0) = A(r, 1) = A(r, 2) = A(r, 3) = 0; return 1;
    case 43:   /* accept -> accept4(..., 0) */            RESTART(288); A(r, 3) = 0; return 1;
    case 232:  /* epoll_wait -> epoll_pwait(..., NULL, 8) */ RESTART(281); A(r, 4) = 0; A(r, 5) = 8; return 1;
    case 213:  /* epoll_create -> epoll_create1(0) */     RESTART(291); A(r, 0) = 0; return 1;
    case 284:  /* eventfd -> eventfd2(n, 0) */            RESTART(290); A(r, 1) = 0; return 1;
    case 22:   /* pipe -> pipe2(fds, 0) */                RESTART(293); A(r, 1) = 0; return 1;
    case 33:   /* dup2 -> dup3(a, b, 0) */
        if (A(r, 0) == A(r, 1)) return 0;
        RESTART(292); A(r, 2) = 0; return 1;
    case 57: case 58:  /* fork/vfork -> clone(SIGCHLD, 0, ...) */
        RESTART(56); A(r, 0) = SIGCHLD; A(r, 1) = A(r, 2) = A(r, 3) = A(r, 4) = 0; return 1;
    case 7:    /* poll */     if (emu_poll(p, r, 271, scratch)) return 1; break;
    case 23:   /* select */   if (emu_select(p, r, 270, scratch)) return 1; break;
    case 2:    /* open(path, flags, mode) -> openat */    at_fdcwd(r, 3); RESTART(257); return 1;
    case 85:   /* creat(path, mode) -> openat(AT_FDCWD, path, O_CREAT|O_WRONLY|O_TRUNC, mode) */
        A(r, 2) = A(r, 1); A(r, 1) = 01 | 0100 | 01000; A(r, 0) = (unsigned long)AT_FDCWD_;
        RESTART(257); return 1;
    case 4:    /* stat -> newfstatat(AT_FDCWD, path, buf, 0) */  at_fdcwd(r, 2); A(r, 3) = 0; RESTART(262); return 1;
    case 6:    /* lstat -> newfstatat(..., AT_SYMLINK_NOFOLLOW) */ at_fdcwd(r, 2); A(r, 3) = AT_SYMLINK_NOFOLLOW_; RESTART(262); return 1;
    case 21:   /* access -> faccessat(AT_FDCWD, path, mode, 0) */ at_fdcwd(r, 2); A(r, 3) = 0; RESTART(269); return 1;
    case 89:   /* readlink -> readlinkat */               at_fdcwd(r, 3); RESTART(267); return 1;
    case 87:   /* unlink -> unlinkat(AT_FDCWD, path, 0) */ at_fdcwd(r, 1); A(r, 2) = 0; RESTART(263); return 1;
    case 84:   /* rmdir -> unlinkat(AT_FDCWD, path, AT_REMOVEDIR) */ at_fdcwd(r, 1); A(r, 2) = 0x200; RESTART(263); return 1;
    case 83:   /* mkdir -> mkdirat */                     at_fdcwd(r, 2); RESTART(258); return 1;
    case 82:   /* rename(a, b) -> renameat(AT_FDCWD, a, AT_FDCWD, b) */
        A(r, 3) = A(r, 1); A(r, 1) = A(r, 0); A(r, 0) = A(r, 2) = (unsigned long)AT_FDCWD_;
        RESTART(264); return 1;
    case 86:   /* link(a, b) -> linkat(AT_FDCWD, a, AT_FDCWD, b, 0) */
        A(r, 3) = A(r, 1); A(r, 1) = A(r, 0); A(r, 0) = A(r, 2) = (unsigned long)AT_FDCWD_; A(r, 4) = 0;
        RESTART(265); return 1;
    case 88:   /* symlink(t, l) -> symlinkat(t, AT_FDCWD, l) */
        A(r, 2) = A(r, 1); A(r, 1) = (unsigned long)AT_FDCWD_; RESTART(266); return 1;
    case 90:   /* chmod -> fchmodat(AT_FDCWD, path, mode) */ at_fdcwd(r, 2); RESTART(268); return 1;
#endif
    }
    RET(r) = (unsigned long)-ENOSYS;
    return 0;
}

// Per-thread state of a re-issued call that we follow to its exit.
#define MAXT 512
static struct { pid_t tid; int phase; regs_t saved; } tr[MAXT];
static int slot(pid_t tid, int create) {
    int free_i = -1;
    for (int i = 0; i < MAXT; i++) {
        if (tr[i].tid == tid) return i;
        if (free_i < 0 && tr[i].tid == 0) free_i = i;
    }
    if (create && free_i >= 0) { tr[free_i].tid = tid; return free_i; }
    return -1;
}

static pid_t child;
static void fwd(int s) { if (child > 0) kill(child, s); }

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: airshim <program> [args]\n"); return 2; }
    child = fork();
    if (child < 0) { perror("fork"); return 1; }
    if (child == 0) {
        ptrace(PTRACE_TRACEME, 0, 0, 0);
        raise(SIGSTOP);
        execv(argv[1], argv + 1);
        perror("execv");
        _exit(127);
    }
    signal(SIGTERM, fwd); signal(SIGINT, fwd); signal(SIGHUP, fwd); signal(SIGQUIT, fwd);
    int st;
    if (waitpid(child, &st, 0) < 0) return 1;
    ptrace(PTRACE_SETOPTIONS, child, 0, PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_TRACESYSGOOD | PTRACE_O_EXITKILL);
    ptrace(PTRACE_CONT, child, 0, 0);
    unsigned char seen[1024] = {0};
    for (;;) {
        pid_t p = waitpid(-1, &st, __WALL);
        if (p < 0) { if (errno == EINTR) continue; break; }
        if (WIFEXITED(st) || WIFSIGNALED(st)) {
            int i = slot(p, 0);
            if (i >= 0) tr[i].tid = 0;
            if (p == child) {
                if (WIFSIGNALED(st)) { fprintf(stderr, "airshim: child killed by signal %d\n", WTERMSIG(st)); return 128 + WTERMSIG(st); }
                return WEXITSTATUS(st);
            }
            continue;
        }
        if (!WIFSTOPPED(st)) continue;
        int sig = WSTOPSIG(st);
        int event = (unsigned)st >> 16;
        int deliver = sig;
        int req = PTRACE_CONT;
        if (sig == (SIGTRAP | 0x80)) {                 // syscall entry/exit of a re-issued call
            deliver = 0;
            int i = slot(p, 0);
            if (i >= 0 && tr[i].phase == 1) {
                tr[i].phase = 2;                       // entered; follow it to the exit
                req = PTRACE_SYSCALL;
            } else if (i >= 0 && tr[i].phase == 2) {
                regs_t now;
                if (getregs(p, &now) == 0) {
                    long res = (long)RET(&now);
                    regs_t back = tr[i].saved;         // registers as at the legacy call
                    if (res <= -512 && res >= -516) {
                        // Interrupted and due for restart: rewind to the legacy call so it is
                        // trapped and rewritten again (registers already hold its arguments).
                        PC(&back) -= SVCLEN(&back);
                    } else {
                        RET(&back) = (unsigned long)res;
                    }
                    no_restart(&back);
                    setregs(p, &back);
                }
                tr[i].tid = 0;
            }
        } else if (event != 0 || sig == SIGTRAP) {
            deliver = 0;                               // clone/fork/exec event stop
        } else if (sig == SIGSTOP) {
            deliver = 0;                               // new thread's initial stop (or our own)
        } else if (sig == SIGSYS) {
            siginfo_t si;
            if (ptrace(PTRACE_GETSIGINFO, p, 0, &si) == 0 && si.si_code == 1 /* SYS_SECCOMP */) {
                regs_t regs, orig;
                if (getregs(p, &regs) == 0) {
                    orig = regs;
                    deliver = 0;
                    if (emulate(p, si.si_syscall, &regs)) {
                        int i = slot(p, 1);
                        if (i >= 0) {
                            tr[i].phase = 1;
                            tr[i].saved = orig;
                            req = PTRACE_SYSCALL;
                        }
                    }
                    setregs(p, &regs);
                    int nr = si.si_syscall;
                    if (nr >= 0 && nr < 1024 && !seen[nr]) { seen[nr] = 1; fprintf(stderr, "airshim: syscall %d blocked by seccomp, handled\n", nr); }
                }
            }
        }
        ptrace(req, p, 0, deliver);
    }
    return 1;
}
