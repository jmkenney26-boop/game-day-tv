package com.gameday.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Everything the app remembers: which app plays each network, timing settings, the multiview layout. */
final class Prefs {
    private Prefs() {}

    static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("gameday", Context.MODE_PRIVATE);
    }

    static String get(Context c, String key, String def) { return p(c).getString(key, def); }
    static void put(Context c, String key, String value) { p(c).edit().putString(key, value).apply(); }
    static int getInt(Context c, String key, int def) { return p(c).getInt(key, def); }
    static void putInt(Context c, String key, int value) { p(c).edit().putInt(key, value).apply(); }
    static boolean getBool(Context c, String key, boolean def) { return p(c).getBoolean(key, def); }
    static void putBool(Context c, String key, boolean value) { p(c).edit().putBoolean(key, value).apply(); }

    static JSONObject json(Context c, String key) {
        try { return new JSONObject(get(c, key, "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    static void putJson(Context c, String key, JSONObject o) { put(c, key, o.toString()); }

    /** Timing settings handed to Goal Zone / Red Zone (same names as the Chrome extension). */
    static String brainSettings(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("streamDelaySeconds", getInt(c, "streamDelaySeconds", 30));
            o.put("commercialLeadSeconds", getInt(c, "commercialLeadSeconds", 10));
            o.put("commercialTailSeconds", getInt(c, "commercialTailSeconds", 25));
            o.put("skipCommercials", getBool(c, "skipCommercials", true));
            o.put("rotateSeconds", getInt(c, "rotateSeconds", 90));
            o.put("goalHoldSeconds", getInt(c, "goalHoldSeconds", 40));
            o.put("scoreHoldSeconds", getInt(c, "scoreHoldSeconds", 35));
            o.put("zoneWait", getBool(c, "zoneWait", true));
        } catch (Exception ignored) {}
        return o.toString();
    }
}
