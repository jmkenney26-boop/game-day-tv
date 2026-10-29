package com.gameday.tv;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The step-by-step log you can read on the TV (and that tells us which apps work). */
final class DiagLog {
    private DiagLog() {}
    private static final int MAX = 400;
    private static final List<String> lines = new ArrayList<>();
    private static Context app;
    private static int unsaved = 0;

    static synchronized void init(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        String saved = Prefs.get(app, "log", "");
        if (!saved.isEmpty()) for (String l : saved.split("\n")) lines.add(l);
    }

    static synchronized void add(String text) {
        String t = new SimpleDateFormat("h:mm:ss", Locale.US).format(new Date()) + "  " + text;
        lines.add(t);
        while (lines.size() > MAX) lines.remove(0);
        android.util.Log.i("GameDay", text);
        if (app != null && ++unsaved >= 5) save();
    }

    static synchronized void save() {
        if (app == null) return;
        unsaved = 0;
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        Prefs.put(app, "log", b.toString());
    }

    /** Newest first. */
    static synchronized String recent(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = lines.size() - 1, k = 0; i >= 0 && k < n; i--, k++) b.append(lines.get(i)).append('\n');
        return b.toString();
    }

    static synchronized void clear() { lines.clear(); save(); }
}
