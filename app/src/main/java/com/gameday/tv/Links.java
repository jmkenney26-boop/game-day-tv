package com.gameday.tv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Your saved links (web pages that play a game or channel), used by Multiview tiles and Sources. */
final class Links {
    private Links() {}

    static final class Link {
        final String name, url;
        Link(String name, String url) { this.name = name; this.url = url; }
    }

    static List<Link> all(Context c) {
        List<Link> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.get(c, "links", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !o.optString("url").isEmpty()) out.add(new Link(o.optString("name", o.optString("url")), o.optString("url")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    static void save(Context c, List<Link> list) {
        JSONArray a = new JSONArray();
        try {
            for (Link l : list) a.put(new JSONObject().put("name", l.name).put("url", l.url));
        } catch (Exception ignored) {}
        Prefs.put(c, "links", a.toString());
    }

    static void add(Context c, String name, String url) {
        url = clean(url);
        if (url.isEmpty()) return;
        List<Link> l = all(c);
        l.add(new Link(name == null || name.trim().isEmpty() ? shortName(url) : name.trim(), url));
        save(c, l);
        DiagLog.add("Added link: " + url);
    }

    static void remove(Context c, int i) {
        List<Link> l = all(c);
        if (i >= 0 && i < l.size()) { l.remove(i); save(c, l); }
    }

    static String clean(String url) {
        if (url == null) return "";
        url = url.trim();
        if (url.isEmpty()) return "";
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url = "https://" + url;
        return url;
    }

    static String shortName(String url) {
        String s = url.replaceFirst("(?i)^[a-z]+://", "").replaceFirst("^www\\.", "");
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    /** The saved link after (step 1) or before (step -1) this url; the first one if it isn't saved. */
    static Link step(Context c, String url, int step) {
        List<Link> l = all(c);
        if (l.isEmpty()) return null;
        int i = -1;
        for (int k = 0; k < l.size(); k++) if (l.get(k).url.equals(url)) { i = k; break; }
        if (i < 0) return l.get(step > 0 ? 0 : l.size() - 1);
        return l.get(((i + step) % l.size() + l.size()) % l.size());
    }
}
