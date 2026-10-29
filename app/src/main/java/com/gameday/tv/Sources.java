package com.gameday.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which app plays each network, and how the game is opened in it:
 *   find – open the app's search, then pick the game whose name has both teams (needs the accessibility switch)
 *   link – open a web link / deep link template in the app ({away} {home} {awayFull} {homeFull} {query})
 *   open – just open the app (you pick the game)
 */
final class Sources {
    private Sources() {}

    static final String YTTV = "com.google.android.youtube.tvunplugged";
    static final String OTHER = "FIND";   // any network not in the list (regional networks etc.)

    /** Network key (as Goal Zone / Red Zone name it) → label. */
    static final Map<String, String> NETWORKS = new LinkedHashMap<>();
    static {
        NETWORKS.put("ESPN", "ESPN");
        NETWORKS.put("ESPN2", "ESPN2");
        NETWORKS.put("ESPNU", "ESPNU (college)");
        NETWORKS.put("ESPN+", "ESPN+ / ESPN Unlimited / SEC Network+ / ACC Network Extra");
        NETWORKS.put("ABC", "ABC");
        NETWORKS.put("TNT", "TNT");
        NETWORKS.put("TBS", "TBS");
        NETWORKS.put("TRUTV", "truTV");
        NETWORKS.put("NHLN", "NHL Network");
        NETWORKS.put("FOX", "FOX (college)");
        NETWORKS.put("FS1", "FS1 (college)");
        NETWORKS.put("CBS", "CBS (college)");
        NETWORKS.put("NBC", "NBC (college)");
        NETWORKS.put("BTN", "Big Ten Network");
        NETWORKS.put("SECN", "SEC Network");
        NETWORKS.put("ACCN", "ACC Network");
        NETWORKS.put("CBSSN", "CBS Sports Network");
        NETWORKS.put("CW", "The CW");
        NETWORKS.put(OTHER, "Any other network (regional networks, streaming services)");
    }

    static final class Source {
        String pkg = YTTV;
        String method = "find";
        String link = "";
        boolean usesDefault = true;
    }

    static Source forKey(Context c, String key) {
        JSONObject all = Prefs.json(c, "sources");
        String k = key == null || key.isEmpty() ? OTHER : key;
        if ("THECW".equals(k)) k = "CW";
        JSONObject o = all.optJSONObject(k);
        if (o == null) o = all.optJSONObject(OTHER);
        Source s = new Source();
        if (o != null) {
            s.pkg = o.optString("pkg", YTTV);
            s.method = o.optString("method", "find");
            s.link = o.optString("link", "");
            s.usesDefault = false;
        }
        return s;
    }

    static void save(Context c, String key, String pkg, String method, String link) {
        JSONObject all = Prefs.json(c, "sources");
        try {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("method", method);
            o.put("link", link == null ? "" : link);
            all.put(key, o);
        } catch (Exception ignored) {}
        Prefs.putJson(c, "sources", all);
    }

    static void reset(Context c, String key) {
        JSONObject all = Prefs.json(c, "sources");
        all.remove(key);
        Prefs.putJson(c, "sources", all);
    }

    static String methodLabel(String m) {
        if ("link".equals(m)) return "open a link";
        if ("open".equals(m)) return "just open the app";
        return "find the game in the app";
    }

    /** An installed app you can open from the TV home screen. */
    static final class App {
        final String pkg;
        final String label;
        App(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }

    static List<App> installedApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> found = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || found.containsKey(pkg)) continue;
                found.put(pkg, new App(pkg, String.valueOf(r.loadLabel(pm))));
            }
        }
        List<App> list = new ArrayList<>(found.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    static String appLabel(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return YTTV.equals(pkg) ? "YouTube TV (not installed)" : pkg + " (not installed)";
        }
    }

    static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getApplicationInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    static Intent launchIntent(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }
}
