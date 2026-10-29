package com.gameday.tv;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Recognising a game by its teams' names in any text ("Boston Bruins at Carolina Hurricanes"). */
final class Match {
    private Match() {}

    static String norm(CharSequence t) {
        return t == null ? "" : t.toString().toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }

    /** Words that identify a team: name, nickname, place, full name, last word ("leafs"), abbreviation. */
    static List<String> words(JSONObject team) {
        Set<String> w = new LinkedHashSet<>();
        String name = norm(team.optString("name")), nick = norm(team.optString("nick")), place = norm(team.optString("place"));
        for (String s : new String[]{name, nick, place, full(team)}) {
            s = norm(s);
            if (s.isEmpty()) continue;
            w.add(s);
            String[] parts = s.split(" ");
            if (parts.length > 1) w.add(parts[parts.length - 1]);
        }
        String ab = norm(team.optString("abbrev"));
        if (ab.length() >= 3) w.add(ab);
        List<String> out = new ArrayList<>();
        for (String s : w) if (s.length() >= 3) out.add(s);
        return out;
    }

    /** "Seattle Kraken"; for college teams the name already is the place ("Alabama"). */
    static String full(JSONObject team) {
        String n = team.optString("name"), p = team.optString("place");
        if (!p.isEmpty() && !n.isEmpty() && !norm(n).contains(norm(p))) return p + " " + n;
        return n.isEmpty() ? team.optString("abbrev") : n;
    }

    static boolean hasWord(String text, String word) {
        return Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(word) + "($|[^a-z0-9])").matcher(text).find();
    }

    static boolean any(String text, List<String> words) {
        for (String w : words) if (hasWord(text, w)) return true;
        return false;
    }

    /** Text names this game: a word from each team. */
    static boolean names(String text, List<String> away, List<String> home) {
        String t = norm(text);
        return any(t, away) && any(t, home);
    }

    static boolean looksLikeReplay(String text) {
        return Pattern.compile("replay|highlight|recap|condensed|preview|pregame|postgame|classic|rewind").matcher(norm(text)).find();
    }
}
