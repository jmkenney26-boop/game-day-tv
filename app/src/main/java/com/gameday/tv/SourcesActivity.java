package com.gameday.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.List;
import java.util.Map;

/** Which app plays each network, and how Game Day opens the game in it. */
public class SourcesActivity extends Activity {
    @Override protected void onResume() { super.onResume(); build(); }

    private void build() {
        LinearLayout col = Ui.page(this, "Sources", "Pick the app for each network. Goal Zone and Red Zone open games there. "
                + "Networks you haven't set use \"Any other network\".");
        for (Map.Entry<String, String> e : Sources.NETWORKS.entrySet()) {
            String key = e.getKey();
            Sources.Source s = Sources.forKey(this, key);
            String now = Sources.appLabel(this, s.pkg) + " · " + Sources.methodLabel(s.method) + (s.usesDefault && !Sources.OTHER.equals(key) ? " (default)" : "");
            col.addView(Ui.button(this, e.getValue() + "\n" + now, v -> pickApp(key, e.getValue())));
        }
    }

    private void pickApp(String key, String label) {
        List<Sources.App> apps = Sources.installedApps(this);
        String[] names = new String[apps.size() + 1];
        names[0] = "Use the default (\"Any other network\")";
        for (int i = 0; i < apps.size(); i++) names[i + 1] = apps.get(i).label;
        new AlertDialog.Builder(this).setTitle("App for " + label).setItems(names, (d, which) -> {
            if (which == 0) { Sources.reset(this, key); build(); return; }
            pickMethod(key, label, apps.get(which - 1));
        }).show();
    }

    private void pickMethod(String key, String label, Sources.App app) {
        String[] m = {
                "Find the game in the app (opens its search, picks the game with both teams; needs the accessibility switch)",
                "Open a link in the app (for apps with links to games)",
                "Just open the app (you pick the game)"};
        new AlertDialog.Builder(this).setTitle(app.label + " for " + label).setItems(m, (d, which) -> {
            if (which == 1) askLink(key, app);
            else { Sources.save(this, key, app.pkg, which == 0 ? "find" : "open", ""); build(); }
        }).show();
    }

    private void askLink(String key, Sources.App app) {
        List<Links.Link> saved = Links.all(this);
        if (!saved.isEmpty()) {
            String[] names = new String[saved.size() + 1];
            for (int i = 0; i < saved.size(); i++) names[i] = saved.get(i).name;
            names[saved.size()] = "Type a link…";
            new AlertDialog.Builder(this).setTitle("Link for " + key).setItems(names, (d, w) -> {
                if (w < saved.size()) { Sources.save(this, key, app.pkg, "link", saved.get(w).url); build(); }
                else typeLink(key, app);
            }).show();
            return;
        }
        typeLink(key, app);
    }

    private void typeLink(String key, Sources.App app) {
        EditText in = new EditText(this);
        in.setHint("https://… use {away} {home} {awayFull} {homeFull} {query}");
        in.setText(Sources.forKey(this, key).link);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        box.addView(in, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle("Link to open in " + app.label).setView(box)
                .setPositiveButton("Save", (d, w) -> { Sources.save(this, key, app.pkg, "link", in.getText().toString().trim()); build(); })
                .setNegativeButton("Cancel", null).show();
    }
}
