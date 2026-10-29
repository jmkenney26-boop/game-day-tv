package com.gameday.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.LinearLayout;

import org.json.JSONObject;

import java.util.List;

/** Multiview: web pages in tiles (works on any box); app tiles stay experimental. */
public class MultiviewActivity extends Activity {
    @Override protected void onResume() { super.onResume(); LinkServer.start(this, this::build); build(); }
    @Override protected void onPause() { super.onPause(); LinkServer.stop(); }

    private void build() {
        LinearLayout col = Ui.page(this, "Multiview",
                "Up to four of your links on screen at once, each page's video filling its tile. The selected tile has the sound.");
        col.addView(Ui.button(this, "▶  Start Multiview", v -> Multiview.open(this)));
        col.addView(Ui.button(this, "Layout: " + Multiview.tileCount(this) + " tile" + (Multiview.tileCount(this) > 1 ? "s" : "") + "  ·  change", v -> {
            String[] opts = {"1 tile", "2 side by side", "3 (one big, two small)", "4 (2 × 2)"};
            new AlertDialog.Builder(this).setTitle("Layout").setItems(opts, (d, w) -> {
                JSONObject cfg = Multiview.config(this);
                try { cfg.put("layout", String.valueOf(w + 1)); } catch (Exception ignored) {}
                Multiview.save(this, cfg);
                build();
            }).show();
        }));
        col.addView(Ui.text(this, "In Multiview:  ← ↑ → ↓ pick a tile  ·  OK = tile menu (your links, Goal Zone, Red Zone, full screen…)  ·  "
                + "CH+ / CH− = next / previous link in that tile  ·  Back = leave", 15, Ui.MUTED));

        col.addView(Ui.section(this, "Your links"));
        col.addView(Ui.text(this, "Add them from your iPhone (same Wi-Fi): open Safari and go to  " + LinkServer.address()
                + "  ·  paste a link, tap Add link. This list updates by itself.", 16, Ui.TEXT));
        List<Links.Link> links = Links.all(this);
        if (links.isEmpty()) col.addView(Ui.text(this, "No links yet.", 15, Ui.MUTED));
        for (int i = 0; i < links.size(); i++) {
            Links.Link l = links.get(i);
            int idx = i;
            col.addView(Ui.button(this, l.name + "\n" + l.url, v -> new AlertDialog.Builder(this).setTitle(l.name)
                    .setItems(new String[]{"Remove this link"}, (d, w) -> { Links.remove(this, idx); build(); }).show()));
        }

        col.addView(Ui.section(this, "Apps in windows (experimental)"));
        col.addView(Ui.text(this, Multiview.check(this), 14, Ui.MUTED));
        col.addView(Ui.text(this, Multiview.ADB_STEPS, 13, Ui.MUTED));
    }
}
