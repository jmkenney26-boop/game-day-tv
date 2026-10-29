package com.gameday.tv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Experimental: several apps on screen at once, using Android's hidden "freeform windows". TV boxes have
 * it switched off; it has to be switched on once with ADB (see the Multiview screen), and even then some
 * boxes and some apps won't do it. Each tile is an app, or Goal Zone / Red Zone (which open their games
 * in that tile).
 */
final class Multiview {
    private Multiview() {}

    static final String[] LAYOUTS = {"2", "4"};

    static JSONObject config(Context c) {
        JSONObject o = Prefs.json(c, "multiview");
        try {
            if (!o.has("layout")) o.put("layout", "2");
            if (!o.has("tiles")) o.put("tiles", new JSONArray());
            JSONArray t = o.getJSONArray("tiles");
            while (t.length() < 4) t.put(new JSONObject().put("type", "none"));
        } catch (Exception ignored) {}
        return o;
    }
    static void save(Context c, JSONObject o) { Prefs.putJson(c, "multiview", o); }

    static boolean active(Context c) { return config(c).optBoolean("active", false); }
    static void setActive(Context c, boolean on) {
        JSONObject o = config(c);
        try { o.put("active", on); } catch (Exception ignored) {}
        save(c, o);
    }

    static int tileCount(Context c) {
        try { return Math.max(1, Math.min(4, Integer.parseInt(config(c).optString("layout", "2")))); } catch (Exception e) { return 2; }
    }

    static Rect tileRect(Context c, int i) {
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        int w = m.widthPixels, h = m.heightPixels;
        int n = tileCount(c);
        if (n == 1) return new Rect(0, 0, w, h);
        if (n == 2) {
            int tw = w / 2, th = tw * 9 / 16, top = (h - th) / 2;
            return new Rect(i * tw, top, (i + 1) * tw, top + th);
        }
        if (n == 3) {
            int bw = w * 2 / 3, bh = bw * 9 / 16, sw = w - bw, sh = sw * 9 / 16, top = (h - bh) / 2;
            return i == 0 ? new Rect(0, top, bw, top + bh) : new Rect(bw, top + (i - 1) * sh, w, top + i * sh);
        }
        int tw = w / 2, th = h / 2;
        return new Rect((i % 2) * tw, (i / 2) * th, (i % 2 + 1) * tw, (i / 2 + 1) * th);
    }

    /** The tile Goal Zone ("gz") or Red Zone ("rz") plays in, while Multiview is running; else full screen. */
    static Rect boundsFor(Context c, String mode) {
        if (!active(c) || MvPlayerActivity.self != null) return null;
        JSONArray t = config(c).optJSONArray("tiles");
        for (int i = 0; t != null && i < tileCount(c); i++) {
            JSONObject tile = t.optJSONObject(i);
            if (tile != null && mode.equals(tile.optString("type"))) return tileRect(c, i);
        }
        return null;
    }

    /** Whether this box will put apps in windows at all. */
    static boolean windowsAllowed(Context c) {
        return c.getPackageManager().hasSystemFeature("android.software.freeform_window_management")
                || Settings.Global.getInt(c.getContentResolver(), "enable_freeform_support", 0) == 1;
    }

    /** What this box says about freeform windows. */
    static String check(Context c) {
        PackageManager pm = c.getPackageManager();
        ContentResolver cr = c.getContentResolver();
        boolean feature = pm.hasSystemFeature("android.software.freeform_window_management");
        int freeform = Settings.Global.getInt(cr, "enable_freeform_support", 0);
        int resizable = Settings.Global.getInt(cr, "force_resizable_activities", 0);
        String verdict = feature || freeform == 1
                ? "Freeform windows look switched on. Try Launch below and see what each app does."
                : "Freeform windows are OFF on this box. Switch them on with the ADB steps below, restart the box, then check again.";
        return verdict + "\n\nDetails: freeform feature " + (feature ? "yes" : "no") + " · enable_freeform_support=" + freeform
                + " · force_resizable_activities=" + resizable + " · Android " + android.os.Build.VERSION.RELEASE
                + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
    }

    static final String ADB_STEPS =
            "One-time setup from a computer (free Google tool \"ADB\"):\n"
            + "1. On the box: Settings › Device Preferences › About › click Build 7 times (turns on Developer options).\n"
            + "2. Settings › Device Preferences › Developer options › turn on USB debugging / Network debugging.\n"
            + "3. On a computer with ADB installed, connect to the box's IP address:  adb connect <box IP>\n"
            + "4. Run these three lines:\n"
            + "   adb shell settings put global enable_freeform_support 1\n"
            + "   adb shell settings put global force_resizable_activities 1\n"
            + "   adb reboot\n"
            + "To undo: the same two lines with 0 at the end, then adb reboot.";

    /** Opens the Multiview player (web pages in tiles; apps experimental). */
    static void open(Context c) {
        Intent i = new Intent(c, MvPlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** Old app-window launcher (experimental): every app tile in its own window. */
    static void launch(Context c) {
        setActive(c, true);
        JSONArray t = config(c).optJSONArray("tiles");
        for (int i = 0; t != null && i < tileCount(c); i++) {
            JSONObject tile = t.optJSONObject(i);
            String type = tile == null ? "none" : tile.optString("type", "none");
            Rect r = tileRect(c, i);
            if ("app".equals(type)) {
                String pkg = tile.optString("pkg");
                Intent li = Sources.launchIntent(c, pkg);
                boolean ok = li != null && Tuner.start(c, li, r);
                DiagLog.add("Multiview tile " + (i + 1) + ": " + Sources.appLabel(c, pkg) + (ok ? " opened in " + r.toShortString() : " couldn't be opened"));
            } else if ("gz".equals(type) || "rz".equals(type)) {
                DiagLog.add("Multiview tile " + (i + 1) + ": " + ("gz".equals(type) ? "Goal Zone" : "Red Zone") + " in " + r.toShortString());
                BrainService.start(c, type);
            }
        }
    }

    static void stopAll(Context c) {
        setActive(c, false);
        BrainService.stop(c, "gz");
        BrainService.stop(c, "rz");
    }
}
