package io.github.picazsoo.airplaybridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class Prefs {
    private Prefs() {}

    private static final char SEP = (char) 9;  // tab

    static SharedPreferences p(Context c) {
        // Device-protected storage, readable early in boot.
        Context dp = c.createDeviceProtectedStorageContext();
        SharedPreferences sp = dp.getSharedPreferences("bridge", Context.MODE_PRIVATE);
        if (!migrated) {
            migrated = true;
            // Before 1.1.1 the web page was on by default (and created a PIN): keep it on there.
            if (!sp.contains("web_enabled") && sp.contains("web_pin"))
                sp.edit().putBoolean("web_enabled", true).putBoolean("web_asked", true).commit();
        }
        return sp;
    }
    private static volatile boolean migrated;

    static boolean enabled(Context c) { return p(c).getBoolean("enabled", true); }
    static void setEnabled(Context c, boolean v) { p(c).edit().putBoolean("enabled", v).apply(); }

    /** Cast ids (udn) the user hid from AirPlay. This box's own receiver is added on first sight. */
    static Set<String> hidden(Context c) { return new HashSet<>(p(c).getStringSet("hidden", new HashSet<>())); }
    static boolean isHidden(Context c, String udn) { return hidden(c).contains(udn); }
    static void setHidden(Context c, String udn, boolean hide) {
        Set<String> h = hidden(c);
        if (hide) h.add(udn); else h.remove(udn);
        p(c).edit().putStringSet("hidden", h).apply();
    }

    /** Cast devices seen on the network, as udn + SEP + name, so hidden ones stay listed. */
    static Map<String, String> known(Context c) {
        Map<String, String> m = new TreeMap<>();
        for (String e : p(c).getStringSet("known", new HashSet<>())) {
            int t = e.indexOf(SEP);
            if (t > 0) m.put(e.substring(0, t), e.substring(t + 1));
        }
        return m;
    }
    static boolean addKnown(Context c, String udn, String name) {
        Map<String, String> m = known(c);
        if (name.equals(m.get(udn))) return false;
        m.put(udn, name);
        Set<String> s = new HashSet<>();
        for (Map.Entry<String, String> e : m.entrySet()) s.add(e.getKey() + SEP + e.getValue());
        p(c).edit().putStringSet("known", s).apply();
        return true;
    }

    /** Cast id (udn) of this box's own receiver, learned via mDNS. */
    static String selfUdn(Context c) { return p(c).getString("self_udn", null); }
    static void setSelfUdn(Context c, String v) { p(c).edit().putString("self_udn", v).apply(); }
    static boolean selfDefaulted(Context c) { return p(c).getBoolean("self_defaulted", false); }
    static void setSelfDefaulted(Context c) { p(c).edit().putBoolean("self_defaulted", true).apply(); }

    /** aircast settings, stored as the string written to config.xml. */
    static String get(Context c, Setting st) { return p(c).getString("cfg_" + st.key, st.values[0]); }
    static void set(Context c, Setting st, String v) { p(c).edit().putString("cfg_" + st.key, v).apply(); }

    /** Per-speaker AirPlay name override (null = the Cast name). */
    static String rename(Context c, String udn) { return p(c).getString("name_" + udn, null); }
    static void setRename(Context c, String udn, String name) {
        if (name == null || name.trim().isEmpty()) p(c).edit().remove("name_" + udn).apply();
        else p(c).edit().putString("name_" + udn, name.trim()).apply();
    }

    /** Web settings page: off by default; the app asks once on first start (webAsked). */
    static boolean webEnabled(Context c) { return p(c).getBoolean("web_enabled", false); }
    static void setWebEnabled(Context c, boolean v) { p(c).edit().putBoolean("web_enabled", v).putBoolean("web_asked", true).commit(); }
    static boolean webAsked(Context c) { return p(c).getBoolean("web_asked", false); }

    /**
     * PIN for the web page (HTTP Basic password). Always set: a random 6-digit PIN is created the
     * first time it is needed and stays the same until regenerated from the TV screen.
     */
    static synchronized String webPin(Context c) {
        String pin = p(c).getString("web_pin", "");
        if (pin.length() < 6) pin = regenerateWebPin(c);
        return pin;
    }
    /** Whether the web page asks for the PIN (on by default). Turning it off keeps the PIN for later. */
    static boolean webPinOn(Context c) { return p(c).getBoolean("web_pin_on", true); }
    static void setWebPinOn(Context c, boolean v) { p(c).edit().putBoolean("web_pin_on", v).commit(); }
    static synchronized String regenerateWebPin(Context c) {
        String pin = String.format(java.util.Locale.ROOT, "%06d", new java.security.SecureRandom().nextInt(1_000_000));
        p(c).edit().putString("web_pin", pin).commit();
        return pin;
    }

    /** A config.xml supplied by the user; when set it is used verbatim instead of the generated one. */
    static String customConfig(Context c) { return p(c).getString("custom_config", null); }
    static void setCustomConfig(Context c, String v) {
        if (v == null || v.trim().isEmpty()) p(c).edit().remove("custom_config").apply();
        else p(c).edit().putString("custom_config", v).apply();
    }

    /** Drop the "+" aircast appends to every name. */
    static boolean plainNames(Context c) { return p(c).getBoolean("plain_names", true); }
    static void setPlainNames(Context c, boolean v) { p(c).edit().putBoolean("plain_names", v).apply(); }
}
