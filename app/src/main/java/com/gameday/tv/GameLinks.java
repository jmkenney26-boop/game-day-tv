package com.gameday.tv;

import android.content.Context;

import org.json.JSONObject;

import java.util.Iterator;

/** A source picked for one game: {type:"link", url, name} or {type:"app", pkg}. Kept 3 days. */
final class GameLinks {
    private GameLinks() {}

    static JSONObject get(Context c, String gameId) {
        JSONObject o = Prefs.json(c, "gameLinks").optJSONObject(gameId);
        return o == null || o.optString("type").isEmpty() ? null : o;
    }

    static void setLink(Context c, String gameId, String url, String name) {
        try { put(c, gameId, new JSONObject().put("type", "link").put("url", Links.clean(url)).put("name", name == null ? "" : name)); }
        catch (Exception ignored) {}
        DiagLog.add("Game " + gameId + ": link set");
    }

    static void setApp(Context c, String gameId, String pkg) {
        try { put(c, gameId, new JSONObject().put("type", "app").put("pkg", pkg)); } catch (Exception ignored) {}
    }

    static void clear(Context c, String gameId) {
        JSONObject all = Prefs.json(c, "gameLinks");
        all.remove(gameId);
        Prefs.putJson(c, "gameLinks", all);
    }

    private static void put(Context c, String gameId, JSONObject o) throws Exception {
        JSONObject all = Prefs.json(c, "gameLinks");
        o.put("at", System.currentTimeMillis());
        all.put(gameId, o);
        long old = System.currentTimeMillis() - 3L * 86400_000L;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            JSONObject x = all.optJSONObject(it.next());
            if (x == null || x.optLong("at") < old) it.remove();
        }
        Prefs.putJson(c, "gameLinks", all);
    }

    /** "Link: ESPN" / "App: HeatLive" / "" */
    static String describe(Context c, String gameId) {
        JSONObject o = get(c, gameId);
        if (o == null) return "";
        if ("link".equals(o.optString("type"))) return "Link: " + (o.optString("name").isEmpty() ? Links.shortName(o.optString("url")) : o.optString("name"));
        return "App: " + Sources.appLabel(c, o.optString("pkg"));
    }
}
