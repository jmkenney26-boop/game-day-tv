package com.gameday.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Experimental: apps side by side (needs freeform windows switched on with ADB). */
public class MultiviewActivity extends Activity {
    @Override protected void onResume() { super.onResume(); build(); }

    private void build() {
        LinearLayout col = Ui.page(this, "Multiview (experimental)",
                "Puts apps on screen at once. Android TV boxes block this unless \"freeform windows\" are switched on, "
                        + "and some apps refuse to play in a window or two at a time. This screen tells you what your box allows.");
        col.addView(Ui.section(this, "This box"));
        col.addView(Ui.text(this, Multiview.check(this), 15, Ui.TEXT));
        JSONObject cfg = Multiview.config(this);
        col.addView(Ui.section(this, "Layout"));
        col.addView(Ui.button(this, "Layout: " + (Multiview.tileCount(this) == 4 ? "4 (2 × 2)" : "2 (side by side)") + "  ·  change", v -> {
            try { cfg.put("layout", Multiview.tileCount(this) == 4 ? "2" : "4"); } catch (Exception ignored) {}
            Multiview.save(this, cfg);
            build();
        }));
        JSONArray tiles = cfg.optJSONArray("tiles");
        for (int i = 0; i < Multiview.tileCount(this); i++) {
            JSONObject t = tiles.optJSONObject(i);
            String type = t == null ? "none" : t.optString("type", "none");
            String what = "app".equals(type) ? Sources.appLabel(this, t.optString("pkg")) : "gz".equals(type) ? "Goal Zone" : "rz".equals(type) ? "CFB Red Zone" : "(empty)";
            int idx = i;
            col.addView(Ui.button(this, "Tile " + (i + 1) + ": " + what, v -> pickTile(cfg, idx)));
        }
        col.addView(Ui.section(this, "Go"));
        col.addView(Ui.button(this, Multiview.active(this) ? "Launch again" : "Launch Multiview", v -> Multiview.launch(this)));
        col.addView(Ui.button(this, "Stop Multiview (back to full screen)", v -> { Multiview.stopAll(this); build(); }));
        col.addView(Ui.section(this, "Switching freeform windows on"));
        col.addView(Ui.text(this, Multiview.ADB_STEPS, 14, Ui.MUTED));
    }

    private void pickTile(JSONObject cfg, int idx) {
        List<Sources.App> apps = Sources.installedApps(this);
        String[] names = new String[apps.size() + 3];
        names[0] = "Goal Zone";
        names[1] = "CFB Red Zone";
        names[2] = "(empty)";
        for (int i = 0; i < apps.size(); i++) names[i + 3] = apps.get(i).label;
        new AlertDialog.Builder(this).setTitle("Tile " + (idx + 1)).setItems(names, (d, which) -> {
            try {
                JSONObject t = new JSONObject();
                if (which == 0) t.put("type", "gz");
                else if (which == 1) t.put("type", "rz");
                else if (which == 2) t.put("type", "none");
                else { t.put("type", "app"); t.put("pkg", apps.get(which - 3).pkg); }
                cfg.getJSONArray("tiles").put(idx, t);
            } catch (Exception ignored) {}
            Multiview.save(this, cfg);
            build();
        }).show();
    }
}
