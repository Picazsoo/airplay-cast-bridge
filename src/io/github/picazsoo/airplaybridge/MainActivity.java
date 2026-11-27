package io.github.picazsoo.airplaybridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView info, log;
    private Button toggle, names, battery, webBtn, pinBtn;
    private android.widget.ImageView qrView;
    private String qrUrl;
    private LinearLayout devices;
    private TextView devNote;
    private String shownKeys = "";
    private final Map<Setting, Button> settingButtons = new java.util.EnumMap<>(Setting.class);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(18, 22, 30));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        ScrollView left = new ScrollView(this);
        left.addView(col);
        root.addView(left, new LinearLayout.LayoutParams(dp(320), -1));

        TextView title = text(26, Color.WHITE);
        title.setText("AirPlay Bridge");
        col.addView(title);
        TextView sub = text(13, Color.GRAY);
        sub.setText("AirPlay from iPhone/Mac to Chromecast speakers (aircast by philippe44)");
        col.addView(sub);

        toggle = button(col, v -> {
            if (BridgeService.instance != null) BridgeService.send(this, BridgeService.ACTION_STOP);
            else BridgeService.start(this);
            h.postDelayed(this::refresh, 500);
        });
        button(col, v -> BridgeService.send(this, BridgeService.ACTION_RESTART)).setText("Restart bridge");
        names = button(col, v -> {
            Prefs.setPlainNames(this, !Prefs.plainNames(this));
            applyLater();
            refresh();
        });
        for (Setting st : Setting.values()) {
            Button sb = button(col, v -> {
                Prefs.set(this, st, st.next(Prefs.get(this, st)));
                applyLater();
                refresh();
            });
            settingButtons.put(st, sb);
        }
        webBtn = button(col, v -> {
            Prefs.setWebEnabled(this, !Prefs.webEnabled(this));
            if (BridgeService.instance != null) BridgeService.instance.updateWeb();
            refresh();
        });
        qrView = new android.widget.ImageView(this);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(180), dp(180));
        qlp.topMargin = dp(8);
        qlp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        col.addView(qrView, qlp);
        pinBtn = button(col, v -> pinDialog());
        battery = button(col, v -> fixBattery());

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(pad, 0, 0, 0);
        root.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        info = text(16, Color.WHITE);
        right.addView(info);
        TextView dh = text(16, Color.WHITE);
        dh.setText("\nSpeakers shown on iPhone/Mac (OK to toggle):");
        right.addView(dh);
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        right.addView(devices);
        devNote = text(12, Color.GRAY);
        right.addView(devNote);
        ScrollView sv = new ScrollView(this);
        log = text(11, Color.rgb(150, 160, 170));
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        sv.addView(log);
        right.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        if (Prefs.enabled(this)) BridgeService.start(this);
        toggle.requestFocus();
        if (!Prefs.webAsked(this)) askWeb();
    }

    /** First start: ask once whether to turn on the web settings page (off by default). */
    private void askWeb() {
        new AlertDialog.Builder(this)
            .setTitle("Change settings from your phone or PC too?")
            .setMessage("AirPlay Bridge can show a settings page in any browser on your home network, "
                + "protected by a PIN shown here on the TV. Everything can also be set on this screen. "
                + "You can change this later with the Web settings button.")
            .setPositiveButton("Turn on", (d, w) -> setWeb(true))
            .setNegativeButton("Not now", (d, w) -> setWeb(false))
            .setOnCancelListener(d -> setWeb(false))
            .show();
    }

    private void setWeb(boolean on) {
        Prefs.setWebEnabled(this, on);
        if (BridgeService.instance != null) BridgeService.instance.updateWeb();
        refresh();
        if (on) webBtn.requestFocus();
    }

    @Override protected void onResume() { super.onResume(); tick(); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacksAndMessages(null); }

    private void tick() { refresh(); h.postDelayed(this::tick, 2000); }

    private void refresh() {
        boolean running = BridgeService.instance != null;
        toggle.setText(running ? "Stop bridge" : "Start bridge");
        names.setText("Names: " + (Prefs.plainNames(this) ? "plain" : "with \"+\""));
        for (Map.Entry<Setting, Button> e : settingButtons.entrySet())
            e.getValue().setText(e.getKey().title + ": " + e.getKey().label(Prefs.get(this, e.getKey())));
        webBtn.setText(Prefs.webEnabled(this)
            ? "Web settings: on  http://" + BridgeService.boundIp + ":" + WebServer.PORT
            : "Web settings: off");
        pinBtn.setText(!Prefs.webPinOn(this) ? "Web PIN: off (no password)"
            : Prefs.webEnabled(this) ? "Web PIN: " + Prefs.webPin(this) : "Web PIN: on");
        String url = Prefs.webEnabled(this) && BridgeService.boundIp != null
            ? "http://" + BridgeService.boundIp + ":" + WebServer.PORT : null;
        if (url == null) { qrView.setVisibility(View.GONE); qrUrl = null; }
        else if (!url.equals(qrUrl)) {
            qrUrl = url;
            try {
                android.graphics.Bitmap bm = Qr.encode(url).toBitmap(1);
                android.graphics.Bitmap big = android.graphics.Bitmap.createScaledBitmap(bm, bm.getWidth() * 8, bm.getHeight() * 8, false);
                qrView.setImageBitmap(big);
                qrView.setVisibility(View.VISIBLE);
            } catch (Exception e) { qrView.setVisibility(View.GONE); }
        }
        if (ShizukuHelper.dozeExempt(this)) battery.setText("Battery optimisation: off (good)");
        else {
            String how;
            switch (ShizukuHelper.state(this)) {
                case READY: how = "tap to fix with Shizuku"; break;
                case NO_PERMISSION: how = "tap, then allow in Shizuku"; break;
                default: how = "tap to fix"; break;
            }
            battery.setText("Battery optimisation: on, " + how);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Status: ").append(BridgeService.status);
        if (running && BridgeService.startedAt > 0)
            sb.append("  ·  up ").append(dur(SystemClock.elapsedRealtime() - BridgeService.startedAt));
        sb.append("\nIP: ").append(BridgeService.boundIp).append("  ·  restarts: ").append(BridgeService.restarts);
        int n;
        synchronized (BridgeService.sessions) { n = BridgeService.sessions.size(); }
        sb.append("  ·  AirPlay sessions: ").append(n);
        if (BridgeService.pendingApply)
            sb.append(n > 0 ? "\nChanges are waiting until playback ends." : "\nApplying changes…");
        info.setText(sb);
        refreshDevices();

        List<String> lines;
        synchronized (BridgeService.logTail) { lines = new ArrayList<>(BridgeService.logTail); }
        StringBuilder lb = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0 && i >= lines.size() - 40; i--) lb.append(lines.get(i)).append('\n');
        log.setText(lb);
    }

    /** One checkbox per Cast device ever seen; checked = offered as an AirPlay speaker. */
    private void refreshDevices() {
        Map<String, String> known = Prefs.known(this);
        Set<String> live;
        synchronized (BridgeService.renderers) { live = new HashSet<>(BridgeService.renderers.keySet()); }
        String keys = known.toString() + live;
        if (!keys.equals(shownKeys)) {           // rebuild only on change, so focus is kept
            shownKeys = keys;
            View focused = getCurrentFocus();
            Object focusedTag = focused != null ? focused.getTag() : null;
            devices.removeAllViews();
            for (Map.Entry<String, String> e : known.entrySet()) {
                final String udn = e.getKey();
                final String castName = e.getValue();
                String custom = Prefs.rename(this, udn);
                String airName = custom != null ? custom : castName.replace(" (this box)", "");
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                CheckBox cb = new CheckBox(this);
                cb.setTag(udn);
                cb.setTextColor(Color.WHITE);
                cb.setChecked(!Prefs.isHidden(this, udn));
                // aircast logs the Cast name, even when the AirPlay name is overridden
                String plainCast = castName.replace(" (this box)", "");
                boolean on = live.contains(plainCast) || live.contains(airName) || live.contains(airName + "+");
                String label = castName + (custom != null ? "  →  \"" + custom + "\"" : "");
                cb.setText(label + (cb.isChecked() ? (on ? "   ✓ offered" : "   (not online)") : ""));
                cb.setOnCheckedChangeListener((b, checked) -> {
                    Prefs.setHidden(this, udn, !checked);
                    applyLater();
                    shownKeys = "";
                });
                row.addView(cb, new LinearLayout.LayoutParams(0, -2, 1));
                Button rn = new Button(this);
                rn.setAllCaps(false);
                rn.setTag(udn + "/rename");
                rn.setText("Rename");
                rn.setOnClickListener(v -> renameDialog(udn, castName));
                row.addView(rn, new LinearLayout.LayoutParams(-2, -2));
                devices.addView(row);
                if (udn.equals(focusedTag)) cb.requestFocus();
                else if ((udn + "/rename").equals(focusedTag)) rn.requestFocus();
            }
            if (known.isEmpty()) devNote.setText("(looking for Cast devices…)");
            else devNote.setText("Changes apply a few seconds after the last one (the bridge restarts). While someone is playing, they wait until playback ends.");
        }
    }

    private void applyLater() {
        if (BridgeService.instance != null) BridgeService.instance.settingsChanged();
        shownKeys = "";
    }

    /**
     * Gets the app off battery optimisation (the Doze whitelist), so the bridge keeps running in
     * standby. Uses Shizuku when available; Google TV usually has no settings screen for it.
     */
    private void fixBattery() {
        if (ShizukuHelper.dozeExempt(this)) return;
        switch (ShizukuHelper.state(this)) {
            case READY:
                battery.setText("Battery optimisation: fixing…");
                new Thread(() -> {
                    boolean ok = ShizukuHelper.exemptFromDoze(this);
                    h.post(() -> { refresh(); if (!ok) shizukuDialog("Shizuku ran the command, but Android still lists the app as optimised. See the log."); });
                }, "shizuku").start();
                return;
            case NO_PERMISSION:
                rikka.shizuku.Shizuku.addRequestPermissionResultListener(new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                    @Override public void onRequestPermissionResult(int code, int result) {
                        rikka.shizuku.Shizuku.removeRequestPermissionResultListener(this);
                        if (code == ShizukuHelper.REQUEST_CODE && result == PackageManager.PERMISSION_GRANTED) h.post(MainActivity.this::fixBattery);
                        else h.post(MainActivity.this::refresh);
                    }
                });
                rikka.shizuku.Shizuku.requestPermission(ShizukuHelper.REQUEST_CODE);
                return;
            case NOT_RUNNING:
                shizukuDialog("Shizuku is installed but not running. Open Shizuku, start it (wireless debugging), then press this button again.");
                return;
            default:
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    shizukuDialog("This TV has no settings screen for it. Two ways to fix it:\n\n"
                        + "1. Install Shizuku for Android TV (the thedjchi fork, works with the remote and on Android 14+ TVs) "
                        + "from the QR code / " + ShizukuHelper.TV_FORK_URL + ", start it, then press this button again.\n\n"
                        + "2. From a PC: adb shell dumpsys deviceidle whitelist +" + getPackageName());
                }
        }
    }

    private void shizukuDialog(String msg) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("Battery optimisation")
            .setMessage(msg)
            .setNegativeButton("Close", null);
        try {
            android.graphics.Bitmap bm = Qr.encode(ShizukuHelper.TV_FORK_URL).toBitmap(1);
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(android.graphics.Bitmap.createScaledBitmap(bm, bm.getWidth() * 6, bm.getHeight() * 6, false));
            iv.setPadding(0, dp(8), 0, dp(8));
            b.setView(iv);
        } catch (Exception ignored) {}
        Intent open = getPackageManager().getLeanbackLaunchIntentForPackage(ShizukuHelper.PACKAGE);
        if (open == null) open = getPackageManager().getLaunchIntentForPackage(ShizukuHelper.PACKAGE);
        if (open != null) {
            Intent o = open;
            b.setPositiveButton("Open Shizuku", (d, w) -> startActivity(o));
        }
        b.show();
    }

    private void pinDialog() {
        boolean on = Prefs.webPinOn(this);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("PIN for the web settings page")
            .setMessage(on
                ? "Current PIN: " + Prefs.webPin(this) + ". A new PIN replaces it; browsers will ask for the new one. "
                  + "Turning the PIN off lets anyone on your network open the page."
                : "The page is open to anyone on your network. Turning the PIN on uses PIN " + Prefs.webPin(this) + ".")
            .setNegativeButton("Cancel", null)
            .setNeutralButton(on ? "Turn PIN off" : "Turn PIN on", (d, w) -> { Prefs.setWebPinOn(this, !on); refresh(); });
        if (on) b.setPositiveButton("New PIN", (d, w) -> { Prefs.regenerateWebPin(this); refresh(); });
        b.show();
    }

    private void renameDialog(String udn, String castName) {
        EditText et = new EditText(this);
        String cur = Prefs.rename(this, udn);
        et.setText(cur != null ? cur : castName.replace(" (this box)", ""));
        et.setSingleLine(true);
        et.selectAll();
        new AlertDialog.Builder(this)
            .setTitle("AirPlay name for " + castName)
            .setMessage("Leave empty to use the Cast name.")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String v = et.getText().toString().trim();
                Prefs.setRename(this, udn, v.equals(castName.replace(" (this box)", "")) ? null : v);
                applyLater();
                refresh();
            })
            .setNeutralButton("Reset", (d, w) -> { Prefs.setRename(this, udn, null); applyLater(); refresh(); })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static String dur(long ms) {
        long s = ms / 1000, m = s / 60, hh = m / 60, d = hh / 24;
        if (d > 0) return d + "d " + (hh % 24) + "h";
        if (hh > 0) return hh + "h " + (m % 60) + "m";
        return m + "m " + (s % 60) + "s";
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(LinearLayout parent, View.OnClickListener l) {
        Button b = new Button(this);
        b.setOnClickListener(l);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
