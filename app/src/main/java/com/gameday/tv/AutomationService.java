package com.gameday.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The accessibility helper (turned on once in the TV's Accessibility settings). It lets Game Day:
 *  - see the screen of the app it opened, find the game whose name has both teams, and press it / "Watch live";
 *  - catch your remote's ← / → while Goal Zone or Red Zone runs, to change games.
 * It only reads the screen while it's looking for a game, or when you ask it to inspect an app.
 */
public class AutomationService extends AccessibilityService {
    private static AutomationService self;
    private static final Handler main = new Handler(Looper.getMainLooper());

    // the current "find this game" job
    private static JSONObject job;
    private static String jobPkg, jobQuery;
    private static long jobStarted, clickedAt;
    private static boolean typing, typed, clickedGame, pressedWatch;
    private static final long JOB_MS = 40_000;

    private static String foreground = "";

    static boolean isOn() { return self != null; }
    static String foreground() { return foreground; }

    static void find(JSONObject req, String pkg, String query) {
        job = req; jobPkg = pkg; jobQuery = query;
        jobStarted = System.currentTimeMillis(); clickedAt = 0;
        typing = false; typed = false; clickedGame = false; pressedWatch = false;
        main.removeCallbacks(step);
        main.postDelayed(step, 1500);
    }
    static void needsTyping() { typing = true; }
    static void cancel() { job = null; main.removeCallbacks(step); }

    @Override
    protected void onServiceConnected() {
        self = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            setServiceInfo(info);
        }
        DiagLog.init(this);
        DiagLog.add("Accessibility helper is on");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        self = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
            foreground = e.getPackageName().toString();
        }
    }

    @Override
    public void onInterrupt() {}

    // ---------- remote: ← / → change games while Goal Zone or Red Zone runs ----------
    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        String mode = BrainService.remoteMode();
        if (mode == null || job != null) return false;            // not running, or busy picking a game
        if (getPackageName().equals(foreground)) return false;    // in our own screens, arrows move around normally
        int k = ev.getKeyCode();
        boolean arrows = Prefs.getBool(this, "remoteArrows", true);
        boolean next = k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_MEDIA_NEXT || (arrows && k == KeyEvent.KEYCODE_DPAD_RIGHT);
        boolean prev = k == KeyEvent.KEYCODE_CHANNEL_DOWN || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS || (arrows && k == KeyEvent.KEYCODE_DPAD_LEFT);
        if (!next && !prev) return false;
        if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
            if (next) BrainService.next(this, mode); else BrainService.prev(this, mode);
        }
        return true;   // the app on screen doesn't get it (so it won't seek)
    }

    // ---------- finding the game in the app on screen ----------
    private static final Runnable step = new Runnable() {
        @Override public void run() {
            if (job == null || self == null) return;
            long now = System.currentTimeMillis();
            if (now - jobStarted > JOB_MS) { finish(false, timeoutReason()); return; }
            try { self.findStep(now); } catch (Exception e) { DiagLog.add("finder error: " + e); }
            if (job != null) main.postDelayed(this, 700);
        }
    };

    private void findStep(long now) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !jobPkg.equals(root.getPackageName().toString())) return;   // app not up yet
        JSONObject away = job.optJSONObject("away"), home = job.optJSONObject("home");
        List<String> aw = Match.words(away), hw = Match.words(home);

        if (clickedGame) {
            // After choosing the game: press "Watch live" (or similar) once if the app asks.
            if (!pressedWatch) {
                AccessibilityNodeInfo w = findButton(root, WATCH_LIVE);
                if (w == null) w = findButton(root, WATCH);
                if (w != null && click(w)) { pressedWatch = true; DiagLog.add("pressed \"" + label(w) + "\""); }
            }
            if (now - clickedAt > 7000) finish(true, "");
            return;
        }
        // Search box needed? (the app opened on its home page)
        if (typing && !typed) {
            AccessibilityNodeInfo edit = findEditable(root);
            if (edit != null) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, jobQuery);
                edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                typed = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                DiagLog.add(typed ? "typed \"" + jobQuery + "\" in the search box" : "couldn't type in the search box");
                return;
            }
            AccessibilityNodeInfo s = findButton(root, SEARCH);
            if (s != null && click(s)) { DiagLog.add("opened the app's search"); return; }
        }
        AccessibilityNodeInfo game = findGame(root, aw, hw);
        if (game != null) {
            DiagLog.add("found \"" + clip(subtreeText(game, 0), 80) + "\"");
            if (click(game)) { clickedGame = true; clickedAt = now; }
        }
    }

    private static void finish(boolean ok, String why) {
        JSONObject j = job;
        job = null;
        main.removeCallbacks(step);
        if (j == null) return;
        String what = j.optJSONObject("away").optString("abbrev") + " at " + j.optJSONObject("home").optString("abbrev");
        DiagLog.add(what + (ok ? ": playing (picked in the app)" : ": couldn't pick it: " + why));
        BrainService.result(j.optString("mode"), j.optString("gameId"), ok, why);
    }

    private static String timeoutReason() {
        if (self == null) return "accessibility helper stopped";
        AccessibilityNodeInfo root = self.getRootInActiveWindow();
        String pkg = root == null || root.getPackageName() == null ? "nothing" : root.getPackageName().toString();
        if (!jobPkg.equals(pkg)) return "the app never came up (on screen: " + pkg + ")";
        if (clickedGame) return "clicked the game but it didn't start";
        List<String> seen = new ArrayList<>();
        collectTexts(root, seen, 0);
        return "no item naming both teams after searching \"" + jobQuery + "\". On screen: " + clip(String.join(" | ", seen), 220);
    }

    // ---------- reading the screen ----------
    static final Pattern WATCH_LIVE = Pattern.compile("^(watch live|go live|jump to live|join live|live)\\b");
    static final Pattern WATCH = Pattern.compile("^(watch|watch now|start watching|play|resume|tune in)\\b");
    static final Pattern SEARCH = Pattern.compile("^search\\b");

    /** The smallest clickable item whose text names both teams (live first, never replays). */
    private AccessibilityNodeInfo findGame(AccessibilityNodeInfo root, List<String> aw, List<String> hw) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo n : all) {
            if (!(n.isClickable() || n.isFocusable())) continue;
            String t = subtreeText(n, 0);
            if (t.isEmpty() || t.length() > 400 || !Match.names(t, aw, hw)) continue;
            int score = 100 - t.length() / 10;
            if (Pattern.compile("\\blive\\b").matcher(Match.norm(t)).find()) score += 30;
            if (Match.looksLikeReplay(t)) score -= 200;
            if (score > bestScore) { bestScore = score; best = n; }
        }
        return best;
    }

    private static AccessibilityNodeInfo findButton(AccessibilityNodeInfo root, Pattern p) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        for (AccessibilityNodeInfo n : all) {
            if (!(n.isClickable() || n.isFocusable())) continue;
            String l = Match.norm(label(n));
            if (l.length() > 0 && l.length() < 40 && p.matcher(l).find()) return n;
        }
        return null;
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        for (AccessibilityNodeInfo n : all) if (n.isEditable()) return n;
        return null;
    }

    private static boolean click(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo x = n; x != null; x = x.getParent()) {
            if (x.isClickable() && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        // TV apps often act on focus + select instead of click
        if (n.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
            return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText(), d = n.getContentDescription();
        if (t != null && t.length() > 0) return t.toString();
        return d == null ? "" : d.toString();
    }

    private static String subtreeText(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 6) return "";
        StringBuilder b = new StringBuilder();
        CharSequence t = n.getText(), d = n.getContentDescription();
        if (t != null) b.append(t).append(' ');
        if (d != null && (t == null || !d.toString().equals(t.toString()))) b.append(d).append(' ');
        for (int i = 0; i < n.getChildCount() && b.length() < 500; i++) b.append(subtreeText(n.getChild(i), depth + 1));
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    private static void flatten(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40 || out.size() > 3000) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) flatten(n.getChild(i), out, depth + 1);
    }

    private static void collectTexts(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 40 || out.size() > 40) return;
        String l = label(n).trim();
        if (!l.isEmpty() && l.length() < 80 && !out.contains(l)) out.add(l);
        for (int i = 0; i < n.getChildCount(); i++) collectTexts(n.getChild(i), out, depth + 1);
    }

    private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    /** "Inspect": write what the app on screen shows (as accessibility sees it) to the log. */
    static void inspect() {
        if (self == null) { DiagLog.add("Inspect: turn on the accessibility switch first (Setup)"); return; }
        AccessibilityNodeInfo root = self.getRootInActiveWindow();
        if (root == null) { DiagLog.add("Inspect: this app doesn't share its screen with accessibility (nothing readable)"); return; }
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        flatten(root, all, 0);
        int readable = 0, clickable = 0;
        List<String> sample = new ArrayList<>();
        for (AccessibilityNodeInfo n : all) {
            String l = label(n).trim();
            if (!l.isEmpty()) { readable++; if (sample.size() < 25 && l.length() < 70) sample.add((n.isClickable() || n.isFocusable() ? "[" + l + "]" : l)); }
            if (n.isClickable() || n.isFocusable()) clickable++;
        }
        DiagLog.add("Inspect " + root.getPackageName() + ": " + all.size() + " items, " + readable + " with text, " + clickable
                + " selectable. [brackets] = selectable. " + String.join(" | ", sample));
    }
}
