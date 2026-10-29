package com.gameday.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Tonight's games (NHL, and Red Zone's college games), straight from the NHL and ESPN feeds. Call off the main thread. */
final class Schedule {
    private Schedule() {}

    static final class Game {
        String id, sport, away, home, when, nets, state;
        String label() { return away + " at " + home; }
    }

    private static List<Game> cachedNhl, cachedCfb;
    private static long nhlAt, cfbAt;

    static synchronized List<Game> nhl() {
        if (cachedNhl != null && System.currentTimeMillis() - nhlAt < 60_000) return cachedNhl;
        List<Game> out = new ArrayList<>();
        try {
            JSONArray games = new JSONObject(get("https://api-web.nhle.com/v1/score/now")).optJSONArray("games");
            for (int i = 0; games != null && i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                Game x = new Game();
                x.id = String.valueOf(g.opt("id"));
                x.sport = "nhl";
                x.away = g.optJSONObject("awayTeam").optString("abbrev");
                x.home = g.optJSONObject("homeTeam").optString("abbrev");
                String st = g.optString("gameState");
                x.state = st.equals("LIVE") || st.equals("CRIT") ? "live" : st.equals("FINAL") || st.equals("OFF") ? "final" : "pre";
                x.when = "live".equals(x.state) ? "Live" : "final".equals(x.state) ? "Final" : time(g.optString("startTimeUTC"));
                StringBuilder n = new StringBuilder();
                JSONArray tv = g.optJSONArray("tvBroadcasts");
                for (int k = 0; tv != null && k < tv.length(); k++) {
                    JSONObject b = tv.getJSONObject(k);
                    if (!b.optString("countryCode", "US").equals("US")) continue;
                    if (n.length() > 0) n.append(", ");
                    n.append(b.optString("network"));
                }
                x.nets = n.toString();
                out.add(x);
            }
            cachedNhl = out;
            nhlAt = System.currentTimeMillis();
        } catch (Exception e) {
            DiagLog.add("NHL schedule: " + e.getMessage());
        }
        return out;
    }

    /** Red Zone's games: a Top 25, SEC or Big Ten team. */
    static synchronized List<Game> cfb() {
        if (cachedCfb != null && System.currentTimeMillis() - cfbAt < 60_000) return cachedCfb;
        List<Game> out = new ArrayList<>();
        try {
            JSONArray evs = new JSONObject(get("https://site.api.espn.com/apis/site/v2/sports/football/college-football/scoreboard?groups=80&limit=400")).optJSONArray("events");
            for (int i = 0; evs != null && i < evs.length(); i++) {
                JSONObject ev = evs.getJSONObject(i);
                JSONObject comp = ev.optJSONArray("competitions").getJSONObject(0);
                JSONArray cs = comp.optJSONArray("competitors");
                JSONObject home = null, away = null;
                boolean big = false;
                for (int k = 0; cs != null && k < cs.length(); k++) {
                    JSONObject c = cs.getJSONObject(k);
                    if ("home".equals(c.optString("homeAway"))) home = c; else away = c;
                    int rank = c.optJSONObject("curatedRank") == null ? 99 : c.optJSONObject("curatedRank").optInt("current", 99);
                    String conf = c.optJSONObject("team") == null ? "" : c.optJSONObject("team").optString("conferenceId");
                    if ((rank >= 1 && rank <= 25) || "8".equals(conf) || "5".equals(conf)) big = true;
                }
                if (!big || home == null || away == null) continue;
                Game x = new Game();
                x.id = ev.optString("id");
                x.sport = "cfb";
                x.away = away.optJSONObject("team").optString("abbreviation");
                x.home = home.optJSONObject("team").optString("abbreviation");
                String st = ev.optJSONObject("status").optJSONObject("type").optString("state");
                x.state = "in".equals(st) ? "live" : "post".equals(st) ? "final" : "pre";
                x.when = "live".equals(x.state) ? "Live" : "final".equals(x.state) ? "Final" : time(ev.optString("date"));
                StringBuilder n = new StringBuilder();
                JSONArray bs = comp.optJSONArray("broadcasts");
                for (int k = 0; bs != null && k < bs.length(); k++) {
                    JSONArray names = bs.getJSONObject(k).optJSONArray("names");
                    for (int m = 0; names != null && m < names.length(); m++) { if (n.length() > 0) n.append(", "); n.append(names.optString(m)); }
                }
                x.nets = n.toString();
                out.add(x);
            }
            cachedCfb = out;
            cfbAt = System.currentTimeMillis();
        } catch (Exception e) {
            DiagLog.add("College schedule: " + e.getMessage());
        }
        return out;
    }

    static List<Game> forMode(String mode) { return "rz".equals(mode) ? cfb() : nhl(); }

    private static String time(String iso) {
        try {
            SimpleDateFormat in = new SimpleDateFormat(iso.length() > 17 ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(iso);
            return new SimpleDateFormat("EEE h:mm a", Locale.US).format(d);
        } catch (Exception e) { return ""; }
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "GameDayTV/1.0");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
}
