package io.github.picazsoo.airplaybridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.util.Log;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Optional Shizuku support: with Shizuku running (on Android/Google TV the thedjchi fork, which
 * supports D-pad remotes and pairing on Android 14+ TVs), the app can do what otherwise needs
 * adb from a PC, namely put itself on the Doze whitelist so the bridge keeps running in standby.
 */
final class ShizukuHelper {
    private ShizukuHelper() {}

    static final String PACKAGE = "moe.shizuku.privileged.api";
    static final String TV_FORK_URL = "https://github.com/thedjchi/Shizuku/releases";
    static final int REQUEST_CODE = 4711;

    enum State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    static State state(Context c) {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.isPreV11()) return State.NOT_RUNNING;   // too old to use
                return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? State.READY : State.NO_PERMISSION;
            }
        } catch (Throwable ignored) {}
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return State.NOT_RUNNING;
        } catch (PackageManager.NameNotFoundException e) {
            return State.NOT_INSTALLED;
        }
    }

    static boolean dozeExempt(Context c) {
        return c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** Runs a shell command as Shizuku's user (shell or root). Returns the exit code, -1 on error. */
    static int run(String cmd) {
        try {
            IShizukuService s = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            IRemoteProcess p = s.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            int rc = p.waitFor();
            Log.i(BridgeService.TAG, "shizuku: " + cmd + " -> " + rc);
            return rc;
        } catch (Throwable e) {
            Log.w(BridgeService.TAG, "shizuku: " + cmd + ": " + e);
            return -1;
        }
    }

    /**
     * Puts the app on the Doze whitelist and allows it to run in the background. Both settings
     * persist across reboots, so this is needed once. Call off the main thread. Returns true when
     * the app is exempt afterwards.
     */
    static boolean exemptFromDoze(Context c) {
        String pkg = c.getPackageName();
        run("dumpsys deviceidle whitelist +" + pkg);
        run("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow");
        return dozeExempt(c);
    }

    /** If Shizuku is ready and the app is not exempt yet, fixes it in the background. */
    static void autoExempt(Context c) {
        Context app = c.getApplicationContext();
        Runnable r = () -> {
            boolean exempt = dozeExempt(app);
            State st = state(app);
            Log.i(BridgeService.TAG, "shizuku: " + st + ", doze exempt: " + exempt);
            if (!exempt && st == State.READY) exemptFromDoze(app);
        };
        try {
            Shizuku.addBinderReceivedListenerSticky(() -> new Thread(r, "shizuku").start());
        } catch (Throwable ignored) {}
    }
}
