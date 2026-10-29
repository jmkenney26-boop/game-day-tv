package com.gameday.tv;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs Goal Zone and CFB Red Zone in the background (they keep picking games while YouTube TV or another
 * app is on screen). Their game-picking logic is the Chrome extension's, running in a hidden web view;
 * every "play this game" decision comes back here and goes to {@link Tuner}.
 */
public class BrainService extends Service {
    static final String ACTION = "action", MODE = "mode";
    private static BrainService self;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private WebView brain;
    private boolean ready = false;
    private final List<String> pending = new ArrayList<>();
    private static boolean gzOn = false, rzOn = false;
    private static String lastRemoteMode = null;
    private static final JSONObject[] lastStatus = new JSONObject[2];   // [gz, rz]

    // ---------- talking to it from the screens ----------
    static void start(Context c, String mode) { send(c, "start", mode); }
    static void stop(Context c, String mode) { send(c, "stop", mode); }
    static void next(Context c, String mode) { send(c, "next", mode); }
    static void prev(Context c, String mode) { send(c, "prev", mode); }
    static void settingsChanged(Context c) { send(c, "settings", null); }

    private static void send(Context c, String action, String mode) {
        Intent i = new Intent(c, BrainService.class).putExtra(ACTION, action).putExtra(MODE, mode);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static boolean running(String mode) { return "rz".equals(mode) ? rzOn : gzOn; }

    /** Which one the remote's ← / → control: the one started last that's still running. */
    static String remoteMode() {
        if (lastRemoteMode != null && running(lastRemoteMode)) return lastRemoteMode;
        return gzOn ? "gz" : rzOn ? "rz" : null;
    }

    static JSONObject status(String mode) { return lastStatus["rz".equals(mode) ? 1 : 0]; }

    /** The app opened (or couldn't open) a game: tell Goal Zone / Red Zone. */
    static void result(String mode, String gameId, boolean ok, String why) {
        BrainService s = self;
        if (s == null) return;
        main.post(() -> s.js("Brain.result(" + q(mode) + "," + q(gameId) + "," + ok + "," + q(why) + ")"));
    }

    // ---------- service ----------
    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        DiagLog.init(this);
        startForeground(1, notification("Game Day is ready"));
        makeBrain();
        main.postDelayed(tick, 4000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getStringExtra(ACTION);
        String mode = intent == null ? null : intent.getStringExtra(MODE);
        if (action == null) return START_STICKY;
        switch (action) {
            case "start":
                // Full screen: one at a time. In Multiview each has its own tile, so both can run.
                if (!Multiview.active(this)) {
                    if ("gz".equals(mode) && rzOn) { rzOn = false; js("Brain.stop('rz')"); }
                    if ("rz".equals(mode) && gzOn) { gzOn = false; js("Brain.stop('gz')"); }
                }
                if ("rz".equals(mode)) rzOn = true; else gzOn = true;
                lastRemoteMode = mode;
                js("Brain.start(" + q(mode) + "," + q(Prefs.brainSettings(this)) + ")");
                break;
            case "stop":
                if ("rz".equals(mode)) rzOn = false; else gzOn = false;
                js("Brain.stop(" + q(mode) + ")");
                break;
            case "next": js("Brain.next(" + q(mode) + ")"); break;
            case "prev": js("Brain.prev(" + q(mode) + ")"); break;
            case "settings": js("Brain.settings(" + q(Prefs.brainSettings(this)) + ")"); break;
            default: break;
        }
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        self = null;
        gzOn = rzOn = false;
        main.removeCallbacks(tick);
        if (brain != null) brain.destroy();
        DiagLog.save();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // The app keeps time for the brain (a hidden web view's own timers can be slowed down).
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (gzOn || rzOn) js("Brain.tick()");
            main.postDelayed(this, 4000);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void makeBrain() {
        brain = new WebView(getApplicationContext());
        brain.getSettings().setJavaScriptEnabled(true);
        brain.addJavascriptInterface(new Bridge(), "Native");
        brain.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                ready = true;
                for (String p : pending) v.evaluateJavascript(p, null);
                pending.clear();
            }
        });
        brain.loadUrl("file:///android_asset/brain/brain.html");
    }

    private void js(String code) {
        if (brain == null) return;
        if (!ready) { pending.add(code); return; }
        brain.evaluateJavascript(code, null);
    }

    static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    // ---------- what the brain can ask of the app ----------
    final class Bridge {
        /** NHL / ESPN scores (no browser restrictions here). Returns the body, or "ERR <reason>". */
        @JavascriptInterface
        public String httpGet(String url) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "GameDayTV/1.0");
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) return "ERR HTTP " + code;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            } catch (Exception e) {
                return "ERR " + e.getClass().getSimpleName() + " " + e.getMessage();
            } finally {
                if (c != null) c.disconnect();
            }
        }

        @JavascriptInterface
        public void tune(String json) {
            main.post(() -> {
                try {
                    JSONObject req = new JSONObject(json);
                    if (!running(req.optString("mode"))) return;
                    Tuner.play(BrainService.this, req);
                    updateNotification();
                } catch (Exception e) {
                    DiagLog.add("tune error: " + e);
                }
            });
        }

        /** Nothing live to show: bring up the Goal Zone / Red Zone screen with the reasons. */
        @JavascriptInterface
        public void idle(String json) {
            main.post(() -> {
                try {
                    String mode = new JSONObject(json).optString("mode");
                    if (!running(mode) || Multiview.active(BrainService.this)) return;
                    Intent i = new Intent(BrainService.this, "rz".equals(mode) ? RedZoneActivity.class : GoalZoneActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    startActivity(i);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void status(String json) {
            try {
                JSONObject s = new JSONObject(json);
                lastStatus["rz".equals(s.optString("mode")) ? 1 : 0] = s;
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void log(String text) { DiagLog.add(text); }
    }

    // ---------- the "running" notification (Android requires one for background work) ----------
    private Notification notification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("run", "Goal Zone / Red Zone running", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "run");
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Game Day").setContentText(text).setSmallIcon(R.drawable.ic_stat).setOngoing(true).build();
    }

    private void updateNotification() {
        String t = gzOn && rzOn ? "Goal Zone and Red Zone running" : gzOn ? "Goal Zone running" : rzOn ? "Red Zone running" : "Game Day is ready";
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notification(t));
    }
}
