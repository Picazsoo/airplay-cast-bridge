package io.github.picazsoo.airplaybridge;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small HTTP server for changing the bridge's settings from a PC or phone browser on the LAN.
 * Off by default; the app asks on first start and the TV can switch it. Protected by a PIN (on by default) via HTTP Basic auth, with a
 * lockout after repeated wrong PINs.
 */
final class WebServer {
    static final int PORT = 8099;
    private static final int MAX_BODY = 512 * 1024;

    private final BridgeService svc;
    private final int port;
    private volatile ServerSocket server;

    WebServer(BridgeService svc, int port) { this.svc = svc; this.port = port; }

    void start() throws Exception {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        server = ss;
        Thread t = new Thread(() -> {
            while (server == ss) {
                try {
                    Socket s = ss.accept();
                    Thread h = new Thread(() -> handle(s), "web-conn");
                    h.setDaemon(true);
                    h.start();
                } catch (Exception e) {
                    if (server == ss) Log.w(BridgeService.TAG, "web accept: " + e);
                }
            }
        }, "web-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        ServerSocket ss = server;
        server = null;
        try { if (ss != null) ss.close(); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- HTTP plumbing

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], target = parts[1];
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
            byte[] body = new byte[0];
            String cl = headers.get("content-length");
            if (cl != null) {
                int n = Integer.parseInt(cl.trim());
                if (n > MAX_BODY) { send(sock.getOutputStream(), 413, "text/plain", "Too large"); return; }
                body = new byte[n];
                int off = 0;
                while (off < n) { int r = in.read(body, off, n - off); if (r < 0) break; off += r; }
            }
            OutputStream out = sock.getOutputStream();
            int auth = authorized(headers.get("authorization"));
            if (auth < 0) { send(out, 429, "text/plain", "Too many wrong PINs, try again in 30 seconds"); return; }
            if (auth == 0) {
                String msg = "PIN required (shown on the TV screen in AirPlay Bridge)";
                out.write(("HTTP/1.0 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"AirPlay Bridge (user: any, password: PIN)\"\r\n"
                    + "Content-Type: text/plain\r\nContent-Length: " + msg.length() + "\r\nConnection: close\r\n\r\n" + msg)
                    .getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            route(method, path, target, body, out);
        } catch (Exception e) {
            Log.w(BridgeService.TAG, "web: " + e);
        }
    }

    private static final int MAX_FAILS = 5;
    private static final long LOCKOUT_MS = 30_000;
    private int fails;
    private long lockedUntil;

    /** 1 = ok, 0 = wrong or missing PIN, -1 = locked out after too many wrong PINs. */
    private synchronized int authorized(String auth) {
        if (!Prefs.webPinOn(svc)) return 1;
        long now = SystemClock.elapsedRealtime();
        if (now < lockedUntil) return -1;
        if (auth == null || !auth.startsWith("Basic ")) return 0;   // first request: browser asks
        boolean ok = false;
        try {
            String dec = new String(Base64.decode(auth.substring(6).trim(), Base64.DEFAULT), StandardCharsets.UTF_8);
            int c = dec.indexOf(':');
            ok = c >= 0 && java.security.MessageDigest.isEqual(
                dec.substring(c + 1).getBytes(StandardCharsets.UTF_8),
                Prefs.webPin(svc).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        if (ok) { fails = 0; return 1; }
        if (++fails >= MAX_FAILS) {
            fails = 0;
            lockedUntil = now + LOCKOUT_MS;
            Log.w(BridgeService.TAG, "web: too many wrong PINs, locked for 30 s");
        }
        return 0;
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) return null;
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void send(OutputStream out, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 400 ? "Bad Request" : "Error";
        out.write(("HTTP/1.0 " + code + " " + reason + "\r\nContent-Type: " + type + "; charset=utf-8\r\n"
            + "Cache-Control: no-store\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static void redirect(OutputStream out, String to) throws Exception {
        out.write(("HTTP/1.0 303 See Other\r\nLocation: " + to + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static Map<String, String> form(byte[] body) throws Exception {
        Map<String, String> m = new HashMap<>();
        String s = new String(body, StandardCharsets.UTF_8);
        for (String kv : s.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            String k = URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8");
            String v = e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8");
            m.put(k, v);
        }
        return m;
    }

    // ---------------------------------------------------------------- routes

    private void route(String method, String path, String target, byte[] body, OutputStream out) throws Exception {
        Context c = svc;
        if ("GET".equals(method) && path.equals("/")) {
            send(out, 200, "text/html", page(target.contains("saved=1")));
        } else if ("GET".equals(method) && path.equals("/status")) {
            send(out, 200, "text/plain", statusText());
        } else if ("GET".equals(method) && path.equals("/config.xml")) {
            String cfg = Prefs.customConfig(c);
            send(out, 200, "application/xml", cfg != null ? cfg : BridgeService.generatedConfig(c));
        } else if ("POST".equals(method) && path.equals("/settings")) {
            Map<String, String> f = form(body);
            for (Setting st : Setting.values()) {
                String v = f.get(st.key);
                if (v != null) for (String ok : st.values) if (ok.equals(v)) Prefs.set(c, st, v);
            }
            Prefs.setPlainNames(c, f.containsKey("plain_names"));
            for (String udn : Prefs.known(c).keySet()) {
                Prefs.setHidden(c, udn, !f.containsKey("show_" + udn));
                String n = f.get("name_" + udn);
                if (n != null) Prefs.setRename(c, udn, n);
            }
            svc.settingsChanged();
            redirect(out, "/?saved=1");
        } else if ("POST".equals(method) && path.equals("/config")) {
            String xml = form(body).get("xml");
            if (xml == null || !xml.contains("<aircast")) { send(out, 400, "text/plain", "That doesn't look like an aircast config.xml (no <aircast> element)."); return; }
            Prefs.setCustomConfig(c, xml);
            svc.settingsChanged();
            redirect(out, "/?saved=1");
        } else if ("POST".equals(method) && path.equals("/config/reset")) {
            Prefs.setCustomConfig(c, null);
            svc.settingsChanged();
            redirect(out, "/?saved=1");
        } else if ("POST".equals(method) && path.equals("/restart")) {
            BridgeService.send(c, BridgeService.ACTION_RESTART);
            redirect(out, "/?saved=1");
        } else {
            send(out, 404, "text/plain", "Not found");
        }
    }

    private String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Status: ").append(BridgeService.status);
        if (BridgeService.startedAt > 0)
            sb.append("   up ").append((SystemClock.elapsedRealtime() - BridgeService.startedAt) / 60000).append(" min");
        sb.append("   restarts: ").append(BridgeService.restarts);
        int n;
        synchronized (BridgeService.sessions) { n = BridgeService.sessions.size(); }
        sb.append("   AirPlay sessions: ").append(n);
        if (BridgeService.pendingApply) sb.append(n > 0 ? "\nChanges are waiting until playback ends." : "\nApplying changes…");
        sb.append("\n\nOffered now:");
        synchronized (BridgeService.renderers) {
            if (BridgeService.renderers.isEmpty()) sb.append(" (none)");
            for (String r : BridgeService.renderers.keySet()) sb.append("\n  • ").append(r);
        }
        sb.append("\n\nLog (newest first):\n");
        List<String> lines;
        synchronized (BridgeService.logTail) { lines = new ArrayList<>(BridgeService.logTail); }
        for (int i = lines.size() - 1; i >= 0 && i >= lines.size() - 60; i--) sb.append(lines.get(i)).append('\n');
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String page(boolean saved) {
        Context c = svc;
        String custom = Prefs.customConfig(c);
        Set<String> hidden = new HashSet<>(Prefs.hidden(c));
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
         .append("<title>AirPlay Bridge</title><style>")
         .append("body{font:15px system-ui,sans-serif;max-width:960px;margin:24px auto;padding:0 16px;color:#222;background:#fafafa}")
         .append("h1{font-size:22px}h2{font-size:17px;margin-top:28px;border-bottom:1px solid #ddd;padding-bottom:4px}")
         .append("table{border-collapse:collapse}td{padding:4px 10px 4px 0;vertical-align:middle}")
         .append("input[type=text]{width:260px;padding:3px}select{padding:3px}")
         .append("textarea{width:100%;height:340px;font:12px ui-monospace,monospace}")
         .append("pre{background:#fff;border:1px solid #ddd;padding:10px;white-space:pre-wrap;font-size:12px;max-height:360px;overflow:auto}")
         .append(".note{color:#666;font-size:13px}.ok{background:#e7f6e7;border:1px solid #9c9;padding:8px}")
         .append(".warn{background:#fff4e0;border:1px solid #e0b060;padding:8px}button{padding:5px 12px;margin-right:6px}")
         .append("@media(prefers-color-scheme:dark){body{background:#161a20;color:#ddd}pre,textarea,input,select{background:#222;color:#ddd;border-color:#444}h2{border-color:#444}.ok{background:#1f3a1f}.warn{background:#3a2f1a}}")
         .append("</style></head><body><h1>AirPlay Bridge</h1>");
        if (saved) h.append("<p class=ok>Saved. Changes apply a few seconds from now (after playback ends if someone is streaming).</p>");
        h.append("<pre id=status>").append(esc(statusText())).append("</pre>");

        h.append("<form method=post action=/settings>");
        if (custom != null)
            h.append("<p class=warn>A custom config.xml is in use (see below), so these settings are saved but <b>not applied</b> until you go back to the app-managed config.</p>");
        h.append("<h2>Settings</h2><table>");
        for (Setting st : Setting.values()) {
            String cur = Prefs.get(c, st);
            h.append("<tr><td>").append(esc(st.title)).append("</td><td><select name=").append(st.key).append('>');
            for (int i = 0; i < st.values.length; i++)
                h.append("<option value=\"").append(esc(st.values[i])).append('"').append(st.values[i].equals(cur) ? " selected" : "")
                 .append('>').append(esc(st.labels[i])).append("</option>");
            h.append("</select></td></tr>");
        }
        h.append("<tr><td>Plain names (no \"+\")</td><td><input type=checkbox name=plain_names").append(Prefs.plainNames(c) ? " checked" : "").append("></td></tr>");
        h.append("</table>");

        h.append("<h2>Speakers</h2><table><tr><td><b>Shown</b></td><td><b>Cast device</b></td><td><b>AirPlay name</b> <span class=note>(empty = Cast name)</span></td></tr>");
        for (Map.Entry<String, String> e : Prefs.known(c).entrySet()) {
            String udn = e.getKey();
            String rn = Prefs.rename(c, udn);
            h.append("<tr><td><input type=checkbox name=\"show_").append(esc(udn)).append('"').append(hidden.contains(udn) ? "" : " checked").append("></td><td>")
             .append(esc(e.getValue())).append("</td><td><input type=text name=\"name_").append(esc(udn)).append("\" value=\"")
             .append(esc(rn == null ? "" : rn)).append("\"></td></tr>");
        }
        h.append("</table><p><button type=submit>Save settings</button></p></form>");

        h.append("<h2>config.xml</h2><p class=note>")
         .append(custom != null ? "<b>Custom config in use.</b> The app writes this file verbatim to aircast. "
                                : "The app currently generates this from the settings above. Edit and save it to take full control; ")
         .append("Format: see <a href='https://github.com/philippe44/AirConnect'>AirConnect's README</a>. <a href=/config.xml download>Download</a></p>")
         .append("<form method=post action=/config><textarea name=xml id=xml spellcheck=false>")
         .append(esc(custom != null ? custom : BridgeService.generatedConfig(c)))
         .append("</textarea><p><input type=file id=file accept='.xml,text/xml'> ")
         .append("<button type=submit>Use this config.xml</button></p></form>");
        if (custom != null)
            h.append("<form method=post action=/config/reset><button type=submit>Go back to app-managed config</button></form>");

        h.append("<h2>Bridge</h2><form method=post action=/restart><button type=submit>Restart now</button> <span class=note>(stops anything playing)</span></form>");
        h.append("<script>")
         .append("document.getElementById('file').onchange=e=>{const f=e.target.files[0];if(!f)return;const r=new FileReader();r.onload=()=>{document.getElementById('xml').value=r.result};r.readAsText(f)};")
         .append("setInterval(()=>fetch('/status').then(r=>r.text()).then(t=>{document.getElementById('status').textContent=t}).catch(()=>{}),3000);")
         .append("</script></body></html>");
        return h.toString();
    }
}
