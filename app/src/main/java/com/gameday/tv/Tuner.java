package com.gameday.tv;

import android.app.ActivityOptions;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

/** Opens the app you chose for a game's network, and (if it can) the game itself. */
final class Tuner {
    private Tuner() {}

    /** A Goal Zone / Red Zone request: {mode, sport, gameId, away{...}, home{...}, networkKey, network}. */
    static void play(Context c, JSONObject req) {
        String mode = req.optString("mode");
        Sources.Source src = Sources.forKey(c, req.optString("networkKey"));
        JSONObject away = req.optJSONObject("away"), home = req.optJSONObject("home");
        if (away == null || home == null) return;
        String what = away.optString("abbrev") + " at " + home.optString("abbrev");
        String appName = Sources.appLabel(c, src.pkg);
        Rect bounds = Multiview.boundsFor(c, mode);   // a tile when Multiview is running, else full screen

        if (!Sources.installed(c, src.pkg)) {
            fail(c, req, appName + " isn't installed. Pick another app for " + req.optString("network") + " under Sources.");
            return;
        }
        DiagLog.add(what + ": opening " + appName + " (" + Sources.methodLabel(src.method) + ")");

        if ("link".equals(src.method) && !src.link.isEmpty()) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(fill(src.link, away, home)));
            i.setPackage(src.pkg);
            if (!start(c, i, bounds)) {
                i.setPackage(null);
                if (!start(c, i, bounds)) { fail(c, req, appName + " couldn't open the link " + i.getDataString()); return; }
            }
            BrainService.result(mode, req.optString("gameId"), true, "");
            return;
        }
        if ("open".equals(src.method)) {
            Intent i = Sources.launchIntent(c, src.pkg);
            if (i == null || !start(c, i, bounds)) { fail(c, req, appName + " couldn't be opened"); return; }
            BrainService.result(mode, req.optString("gameId"), true, "");
            return;
        }

        // "find": open the app's search for one team, then the accessibility helper picks the game.
        if (!AutomationService.isOn()) {
            Intent i = Sources.launchIntent(c, src.pkg);
            if (i != null) start(c, i, bounds);
            fail(c, req, "turn on Game Day's accessibility switch (Setup) so it can pick the game in " + appName);
            return;
        }
        String query = home.optString("nick", "").isEmpty() ? home.optString("name") : home.optString("nick");
        if (query.isEmpty()) query = Match.full(home);
        AutomationService.find(req, src.pkg, query);
        if (!openSearch(c, src.pkg, query, bounds)) {
            Intent i = Sources.launchIntent(c, src.pkg);
            if (i == null || !start(c, i, bounds)) { AutomationService.cancel(); fail(c, req, appName + " couldn't be opened"); return; }
            AutomationService.needsTyping();   // it'll find the app's search box and type
        }
    }

    /** Opens an app straight on its search results, if it supports that. */
    static boolean openSearch(Context c, String pkg, String query, Rect bounds) {
        if (Sources.YTTV.equals(pkg)) {
            // YouTube TV's results page, as on the web (tv.youtube.com/search/Bruins).
            Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse("https://tv.youtube.com/search/" + Uri.encode(query)));
            v.setPackage(pkg);
            if (start(c, v, bounds)) return true;
        }
        Intent s = new Intent(Intent.ACTION_SEARCH);
        s.setPackage(pkg);
        s.putExtra(SearchManager.QUERY, query);
        if (start(c, s, bounds)) return true;
        Intent g = new Intent("android.search.action.GLOBAL_SEARCH");
        g.setPackage(pkg);
        g.putExtra(SearchManager.QUERY, query);
        return start(c, g, bounds);
    }

    static String fill(String template, JSONObject away, JSONObject home) {
        return template
                .replace("{away}", Uri.encode(away.optString("name")))
                .replace("{home}", Uri.encode(home.optString("name")))
                .replace("{awayFull}", Uri.encode(Match.full(away)))
                .replace("{homeFull}", Uri.encode(Match.full(home)))
                .replace("{query}", Uri.encode(Match.full(away) + " " + Match.full(home)));
    }

    /** Starts an activity full screen, or in a window (Multiview tile) when bounds are given. */
    static boolean start(Context c, Intent i, Rect bounds) {
        if (i.resolveActivity(c.getPackageManager()) == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (bounds != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setLaunchBounds(bounds);
                Bundle b = o.toBundle();
                c.startActivity(i, b);
            } else {
                c.startActivity(i);
            }
            return true;
        } catch (Exception e) {
            DiagLog.add("couldn't start " + i + ": " + e.getMessage());
            return false;
        }
    }

    static void fail(Context c, JSONObject req, String why) {
        DiagLog.add(req.optJSONObject("away").optString("abbrev") + " at " + req.optJSONObject("home").optString("abbrev") + ": " + why);
        BrainService.result(req.optString("mode"), req.optString("gameId"), false, why);
    }
}
