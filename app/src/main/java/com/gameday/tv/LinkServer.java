package com.gameday.tv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny page on your home network for adding links from your phone (no typing on the remote):
 * open http://<box IP>:8765 in Safari, paste a link, Add. Runs only while a Game Day links screen is open.
 */
final class LinkServer {
    private LinkServer() {}
    static final int PORT = 8765;
    private static ServerSocket server;
    private static Runnable onChange;

    static synchronized void start(Context c, Runnable changed) {
        onChange = changed;
        if (server != null) return;
        Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                while (server != null && !server.isClosed()) {
                    Socket s = server.accept();
                    try { handle(app, s); } catch (Exception e) { DiagLog.add("link page error: " + e.getMessage()); }
                    finally { try { s.close(); } catch (Exception ignored) {} }
                }
            } catch (Exception e) {
                if (server != null) DiagLog.add("link page stopped: " + e.getMessage());
            }
        }, "link-server");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void stop() {
        onChange = null;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }

    /** The box's address on your Wi-Fi, e.g. http://192.168.1.57:8765 */
    static String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return "http://" + a.getHostAddress() + ":" + PORT;
                }
            }
        } catch (Exception ignored) {}
        return "http://<box IP>:" + PORT;
    }

    private static void handle(Context c, Socket s) throws Exception {
        s.setSoTimeout(5000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String request = in.readLine();
        if (request == null) return;
        int length = 0;
        for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
            if (h.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
        }
        String[] parts = request.split(" ");
        String path = parts.length > 1 ? parts[1] : "/";
        String msg = "";
        if (request.startsWith("POST") && path.startsWith("/add")) {
            char[] buf = new char[Math.min(length, 20000)];
            int n = 0;
            while (n < buf.length) { int r = in.read(buf, n, buf.length - n); if (r < 0) break; n += r; }
            Map<String, String> f = form(new String(buf, 0, n));
            String url = f.containsKey("url") ? f.get("url").trim() : "";
            if (!url.isEmpty()) { Links.add(c, f.get("name"), url); msg = "Added."; changed(); }
            else msg = "Paste a link first.";
        } else if (path.startsWith("/del?i=")) {
            try { Links.remove(c, Integer.parseInt(path.substring(7))); msg = "Removed."; changed(); } catch (Exception ignored) {}
        }
        byte[] body = page(c, msg).getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    private static Map<String, String> form(String body) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
        }
        return m;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String page(Context c, String msg) {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
         .append("<title>Game Day links</title><style>body{font:17px -apple-system,system-ui,sans-serif;background:#0d1016;color:#eceff4;margin:0;padding:18px}")
         .append("h1{font-size:24px;margin:0 0 4px}p{color:#96a0af;margin:0 0 14px}input{width:100%;box-sizing:border-box;font-size:17px;padding:12px;margin:6px 0;border-radius:10px;border:1px solid #333;background:#1c212b;color:#fff}")
         .append("button{width:100%;font-size:18px;padding:13px;border:0;border-radius:10px;background:#d63031;color:#fff;font-weight:700;margin-top:6px}")
         .append("li{list-style:none;background:#1c212b;border-radius:10px;padding:10px 12px;margin:8px 0;display:flex;gap:10px;align-items:center}li span{flex:1;min-width:0;word-break:break-all}")
         .append("li a{color:#ff7675;text-decoration:none;font-weight:600}.msg{color:#55efc4;font-weight:600}ul{padding:0}</style></head><body>")
         .append("<h1>Game Day links</h1><p>Add a web page that plays a game or channel. It shows up in Multiview on the TV.</p>");
        if (!msg.isEmpty()) b.append("<p class=msg>").append(esc(msg)).append("</p>");
        b.append("<form method=post action=/add><input name=url placeholder='Paste the link (https://…)' autocapitalize=off autocorrect=off>")
         .append("<input name=name placeholder='Name (optional), e.g. ESPN or Bruins'><button>Add link</button></form><ul>");
        List<Links.Link> all = Links.all(c);
        for (int i = 0; i < all.size(); i++) {
            Links.Link l = all.get(i);
            b.append("<li><span><b>").append(esc(l.name)).append("</b><br><small>").append(esc(l.url)).append("</small></span><a href='/del?i=")
             .append(i).append("'>Remove</a></li>");
        }
        if (all.isEmpty()) b.append("<p>No links yet.</p>");
        return b.append("</ul></body></html>").toString();
    }
}
