package io.github.picazsoo.airplaybridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs philippe44's aircast (AirPlay -> Cast bridge) as a child process and keeps it alive.
 * Holds a partial wake lock, a Wi-Fi lock and a multicast lock so it stays reachable
 * while the box is in standby.
 */
public class BridgeService extends Service {
    static final String TAG = "AirBridge";
    static final String ACTION_RESTART = "io.github.picazsoo.airplaybridge.RESTART";
    static final String ACTION_STOP = "io.github.picazsoo.airplaybridge.STOP";
    private static final String CHANNEL = "bridge";
    private static final int NOTIF_ID = 1;

    // ---- state shown by MainActivity (single process, so statics are fine) ----
    static volatile BridgeService instance;
    static volatile String status = "Stopped";
    static volatile long startedAt;      // elapsedRealtime of the current aircast start
    static volatile int restarts;
    static volatile String boundIp;
    static final Map<String, String> renderers = new LinkedHashMap<>(); // name -> ip:port
    static final ArrayDeque<String> logTail = new ArrayDeque<>();

    private static final Pattern ADD = Pattern.compile("adding renderer \\((.*) - ([0-9.]+:\\d+)\\)");
    private static final Pattern RTSP_OPEN = Pattern.compile("got RTSP connection (\\d+)");
    private static final Pattern RTSP_CLOSE = Pattern.compile("RTSP close (\\d+)");
    /** Open AirPlay (RTSP) sessions; a sender keeps one open while a speaker is selected. */
    static final java.util.Set<String> sessions = new java.util.HashSet<>();
    static boolean streaming() { synchronized (sessions) { return !sessions.isEmpty(); } }
    static volatile boolean pendingApply;
    private static final Pattern DEL = Pattern.compile("(?:remov\\w*|delet\\w*) .*?renderer \\((.*?)(?: - [0-9.:]+)?\\)", Pattern.CASE_INSENSITIVE);

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock mcLock;
    private ConnectivityManager.NetworkCallback netCb;
    private volatile Process proc;
    private volatile boolean wanted;
    private int backoffMs = 2000;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener nsdListener;
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;

    static void start(Context c) {
        Intent i = new Intent(c, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void send(Context c, String action) {
        Intent i = new Intent(c, BridgeService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "AirPlay bridge", NotificationManager.IMPORTANCE_LOW));
        goForeground("Starting…");
        ShizukuHelper.autoExempt(this);   // Doze whitelist via Shizuku, if allowed

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirBridge:run");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wm.createWifiLock(mode, "AirBridge:wifi");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
        mcLock = wm.createMulticastLock("AirBridge:mdns");
        mcLock.setReferenceCounted(false);
        mcLock.acquire();

        watchNetwork();
        findSelfUdn();
        updateWeb();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(status);
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(a)) {
            Prefs.setEnabled(this, false);
            wanted = false;
            killProc();
            stopSelf();
            return START_NOT_STICKY;
        }
        Prefs.setEnabled(this, true);
        wanted = true;
        if (ACTION_RESTART.equals(a) || proc == null) restartProc("requested");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        wanted = false;
        killProc();
        if (web != null) { web.stop(); web = null; }
        try { if (netCb != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
        try { if (nsdListener != null) nsd.stopServiceDiscovery(nsdListener); } catch (Exception ignored) {}
        if (mcLock != null && mcLock.isHeld()) mcLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        status = "Stopped";
        instance = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- aircast process

    private synchronized void restartProc(String why) {
        log("restart: " + why);
        main.removeCallbacks(retry);
        main.removeCallbacks(applySettings);
        pendingApply = false;
        killProc();
        if (!wanted) return;
        try {
            File bin = new File(getApplicationInfo().nativeLibraryDir, "libaircast.so");
            File dir = getFilesDir();
            File cfg = new File(dir, "config.xml");
            writeConfig(cfg);
            List<String> cmd = new ArrayList<>();
            // airshim runs aircast under ptrace and turns the syscalls Android's app seccomp
            // filter traps (set_robust_list on arm32) into ENOSYS instead of a fatal SIGSYS.
            cmd.add(new File(getApplicationInfo().nativeLibraryDir, "libairshim.so").getAbsolutePath());
            cmd.add(bin.getAbsolutePath());
            cmd.add("-Z");                 // not interactive
            cmd.add("-k");                 // exit at once on SIGTERM
            cmd.add("-x"); cmd.add(cfg.getAbsolutePath());
            cmd.add("-p"); cmd.add(new File(dir, "aircast.pid").getAbsolutePath());
            if (Prefs.plainNames(this)) { cmd.add("-N"); cmd.add("%s"); }
            String ip = currentIpv4();
            if (ip != null) { cmd.add("-b"); cmd.add(ip); }
            boundIp = ip;
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
            synchronized (renderers) { renderers.clear(); }
            synchronized (sessions) { sessions.clear(); }
            final Process p = pb.start();
            proc = p;
            startedAt = SystemClock.elapsedRealtime();
            setStatus("Running");
            Thread t = new Thread(() -> pump(p), "aircast-out");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            log("start failed: " + e);
            setStatus("Start failed: " + e.getMessage());
            scheduleRetry();
        }
    }

    private void pump(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) onLine(line);
        } catch (Exception ignored) {}
        int code = -1;
        try { code = p.waitFor(); } catch (InterruptedException ignored) {}
        if (proc != p) return;           // replaced on purpose
        proc = null;
        log("aircast exited, code " + code);
        if (wanted) {
            restarts++;
            setStatus("Restarting (exit " + code + ")");
            scheduleRetry();
        }
    }

    private final Runnable retry = () -> { if (wanted && proc == null) restartProc("watchdog"); };

    private void scheduleRetry() {
        int d = backoffMs;
        backoffMs = Math.min(backoffMs * 2, 60000);
        main.removeCallbacks(retry);
        main.postDelayed(retry, d);
    }

    private void killProc() {
        Process p = proc;
        proc = null;
        if (p != null) {
            p.destroy();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            if (Build.VERSION.SDK_INT >= 26 && p.isAlive()) p.destroyForcibly();
        }
        // An aircast left over from an earlier app process (same uid) would advertise twice.
        try {
            File pid = new File(getFilesDir(), "aircast.pid");
            if (pid.exists()) {
                int n = Integer.parseInt(new String(Files.readAllBytes(pid.toPath())).trim());
                File cmdline = new File("/proc/" + n + "/cmdline");
                if (cmdline.exists() && new String(Files.readAllBytes(cmdline.toPath())).contains("libaircast"))
                    android.os.Process.killProcess(n);
                pid.delete();
            }
        } catch (Exception ignored) {}
    }

    private void onLine(String line) {
        if (line.startsWith("airshim")) Log.w(TAG, line); else Log.d(TAG + "Out", line);
        synchronized (logTail) {
            logTail.addLast(line);
            while (logTail.size() > 200) logTail.removeFirst();
        }
        Matcher m = ADD.matcher(line);
        if (m.find()) {
            synchronized (renderers) { renderers.put(m.group(1), m.group(2)); }
            backoffMs = 2000;            // healthy
            return;
        }
        m = DEL.matcher(line);
        if (m.find() && !line.contains("adding")) {
            synchronized (renderers) { renderers.remove(m.group(1)); }
        }
        m = RTSP_OPEN.matcher(line);
        if (m.find()) { synchronized (sessions) { sessions.add(m.group(1)); } return; }
        m = RTSP_CLOSE.matcher(line);
        if (m.find()) { synchronized (sessions) { sessions.remove(m.group(1)); } }
    }

    private void writeConfig(File f) throws Exception {
        String custom = Prefs.customConfig(this);
        if (custom != null) log("using custom config.xml from the web page");
        String cfg = custom != null ? custom : generatedConfig(this);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(cfg.getBytes(StandardCharsets.UTF_8)); }
    }

    /** The config.xml the app builds from its settings (used unless a custom one is set). */
    static String generatedConfig(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?>\n<aircast>\n<common>\n")
          .append("<enabled>1</enabled>\n<flush>1</flush>\n");
        for (Setting st : Setting.values())
            sb.append('<').append(st.key).append('>').append(xml(Prefs.get(ctx, st))).append("</").append(st.key).append(">\n");
        sb.append("</common>\n<main_log>info</main_log>\n<cast_log>warn</cast_log>\n<raop_log>warn</raop_log>\n<util_log>warn</util_log>\n")
          .append("<log_limit>-1</log_limit>\n<max_players>32</max_players>\n");
        java.util.Set<String> hidden = Prefs.hidden(ctx);
        Log.i(TAG, "hidden from AirPlay: " + hidden.size() + " device(s)");
        for (String udn : Prefs.known(ctx).keySet()) {
            String name = Prefs.rename(ctx, udn);
            boolean hide = hidden.contains(udn);
            if (!hide && name == null) continue;
            sb.append("<device>\n<udn>").append(xml(udn)).append("</udn>\n");
            if (name != null) sb.append("<name>").append(xml(name)).append("</name>\n");
            sb.append("<enabled>").append(hide ? 0 : 1).append("</enabled>\n</device>\n");
        }
        sb.append("</aircast>\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- web settings page

    private WebServer web;

    /** Start or stop the web settings page to match the preference. */
    void updateWeb() {
        boolean want = Prefs.webEnabled(this);
        if (want && web == null) {
            web = new WebServer(this, WebServer.PORT);
            try { web.start(); log("web settings on port " + WebServer.PORT); }
            catch (Exception e) { log("web server: " + e); web = null; }
        } else if (!want && web != null) {
            web.stop();
            web = null;
            log("web settings off");
        }
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Apply setting changes from the UI with one bridge restart, a few seconds after the last
     * change. While someone is streaming the restart waits, so playback is not cut off.
     */
    private final Runnable applySettings = new Runnable() {
        @Override public void run() {
            if (!wanted) { pendingApply = false; return; }
            if (streaming()) {
                setStatus("Running (changes wait until playback ends)");
                main.postDelayed(this, 10000);
                return;
            }
            pendingApply = false;
            restartProc("settings changed");
        }
    };
    void settingsChanged() {
        pendingApply = true;
        main.removeCallbacks(applySettings);
        main.postDelayed(applySettings, 2500);
    }

    // ---------------------------------------------------------------- network

    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) { main.post(BridgeService.this::checkIp); }
            @Override public void onLost(Network n) { main.post(BridgeService.this::checkIp); }
        };
        NetworkRequest req = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
        cm.registerNetworkCallback(req, netCb);
    }

    private void checkIp() {
        String ip = currentIpv4();
        if (ip != null && wanted && !ip.equals(boundIp)) {
            restarts++;
            restartProc("IP changed " + boundIp + " -> " + ip);
        }
    }

    String currentIpv4() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        if (n == null) return null;
        LinkProperties lp = cm.getLinkProperties(n);
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
        }
        return null;
    }

    /** Find this box's own Cast receiver (same IP as us) so it can be left out of the AirPlay list. */
    private void findSelfUdn() {
        nsd = getSystemService(NsdManager.class);
        nsdListener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { log("nsd start failed " + e); }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceLost(NsdServiceInfo s) {}
            @Override public void onServiceFound(NsdServiceInfo s) { main.post(() -> { resolveQueue.add(s); resolveNext(); }); }
        };
        try { nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, nsdListener); }
        catch (Exception e) { log("nsd: " + e); }
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty()) return;
        resolving = true;
        NsdServiceInfo s = resolveQueue.poll();
        nsd.resolveService(s, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo i, int e) { main.post(() -> { resolving = false; resolveNext(); }); }
            @Override public void onServiceResolved(NsdServiceInfo i) {
                main.post(() -> {
                    resolving = false;
                    try {
                        String host = i.getHost() != null ? i.getHost().getHostAddress() : null;
                        byte[] id = i.getAttributes().get("id");
                        byte[] fn = i.getAttributes().get("fn");
                        if (id != null) {
                            String udn = new String(id, StandardCharsets.UTF_8);
                            String name = fn != null ? new String(fn, StandardCharsets.UTF_8) : i.getServiceName();
                            boolean isSelf = host != null && host.equals(currentIpv4());
                            if (isSelf) name = name + " (this box)";
                            Prefs.addKnown(BridgeService.this, udn, name);
                            if (isSelf && !Prefs.selfDefaulted(BridgeService.this)) {
                                // First sight of our own receiver: hide it by default (once, so
                                // the user can show it later).
                                Prefs.setSelfUdn(BridgeService.this, udn);
                                Prefs.setSelfDefaulted(BridgeService.this);
                                log("own cast receiver: " + udn);
                                if (!Prefs.isHidden(BridgeService.this, udn)) {
                                    Prefs.setHidden(BridgeService.this, udn, true);
                                    restartProc("hide own receiver");
                                }
                            }
                        }
                    } catch (Exception e) { log("resolve: " + e); }
                    resolveNext();
                });
            }
        });
    }

    // ---------------------------------------------------------------- ui helpers

    private void setStatus(String s) {
        status = s;
        main.post(() -> goForeground(s));
    }

    private void goForeground(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setContentTitle("AirPlay Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIF_ID, n);
    }

    static void log(String s) {
        Log.i(TAG, s);
        synchronized (logTail) {
            logTail.addLast("[bridge] " + s);
            while (logTail.size() > 200) logTail.removeFirst();
        }
    }
}
