package com.gameday.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** The Goal Zone / CFB Red Zone screen: start it, see what's live and why a game isn't on. */
abstract class ZoneActivity extends Activity {
    abstract String mode();
    abstract String title();
    abstract String blurb();

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView now, games, logView;
    private Button startBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DiagLog.init(this);
        LinearLayout col = Ui.page(this, title(), blurb());
        startBtn = Ui.button(this, "", v -> {
            if (BrainService.running(mode())) BrainService.stop(this, mode()); else BrainService.start(this, mode());
            h.postDelayed(this::refresh, 300);
        });
        col.addView(startBtn);
        LinearLayout r = Ui.row(this);
        Button prev = Ui.button(this, "‹ Previous game", v -> BrainService.prev(this, mode()));
        Button next = Ui.button(this, "Next game ›", v -> BrainService.next(this, mode()));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half.topMargin = Ui.dp(this, 8);
        half.rightMargin = Ui.dp(this, 8);
        prev.setLayoutParams(half);
        next.setLayoutParams(new LinearLayout.LayoutParams(half));
        r.addView(prev);
        r.addView(next);
        col.addView(r);
        col.addView(Ui.text(this, "While it runs, ← / → on the remote do the same (a game you pick stays on, even during its commercials, for 3 minutes).", 14, Ui.MUTED));
        col.addView(Ui.section(this, "On now"));
        now = Ui.text(this, "", 18, Ui.TEXT);
        col.addView(now);
        col.addView(Ui.section(this, "Live games"));
        games = Ui.text(this, "", 16, Ui.TEXT);
        col.addView(games);
        col.addView(Ui.button(this, "Inspect: in 6 seconds, record what the app on screen shows (switch to it now)", v -> {
            DiagLog.add("Inspect in 6 seconds…");
            h.postDelayed(AutomationService::inspect, 6000);
        }));
        col.addView(Ui.button(this, "Sources: which app plays each network", v -> startActivity(new Intent(this, SourcesActivity.class))));
        col.addView(Ui.section(this, "Recent steps"));
        logView = Ui.text(this, "", 13, Ui.MUTED);
        col.addView(logView);
        startBtn.requestFocus();
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() { refresh(); h.postDelayed(this, 2000); }
    };

    @Override protected void onResume() { super.onResume(); h.post(loop); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(loop); }

    private void refresh() {
        boolean on = BrainService.running(mode());
        startBtn.setText(on ? "Stop " + title() : "Start " + title() + " (full screen)");
        JSONObject s = BrainService.status(mode());
        if (!on) now.setText("Not running");
        else if (s == null) now.setText("Starting…");
        else if (s.has("error")) now.setText(s.optString("error"));
        else now.setText(s.optString("onAir").isEmpty() ? "Waiting for a live game" : s.optString("onAir") + (s.optBoolean("manual") ? "  (your pick)" : ""));
        StringBuilder g = new StringBuilder();
        JSONArray arr = s == null ? null : s.optJSONArray("games");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i);
            g.append(x.optBoolean("onAir") ? "▶ " : "   ").append(x.optString("label")).append("   ").append(x.optString("state"))
                    .append("   ").append(x.optString("net"));
            if (!x.optString("why").isEmpty()) g.append("\n      ").append(x.optString("why"));
            g.append('\n');
        }
        games.setText(g.length() == 0 ? (on ? "No games right now." : "") : g.toString());
        logView.setText(DiagLog.recent(25));
    }
}
