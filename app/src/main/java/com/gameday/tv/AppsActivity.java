package com.gameday.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.List;

/** "What works on this box": for each app, what Game Day can do with it, plus a live test. */
public class AppsActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onResume() {
        super.onResume();
        LinearLayout col = Ui.page(this, "What works on this box",
                "For each app: can Game Day open its search, open its links, and read its screen? Pick an app to test it "
                        + "(it opens the app, searches, and after 8 seconds records what it sees in the Log).");
        col.addView(Ui.text(this, "Accessibility switch: " + (AutomationService.isOn() ? "ON" : "OFF — turn it on in Setup first"), 16,
                AutomationService.isOn() ? Ui.TEXT : Ui.RED));
        col.addView(Ui.text(this, "Multiview (several apps at once): " + Multiview.check(this).split("\n")[0], 14, Ui.MUTED));
        PackageManager pm = getPackageManager();
        List<Sources.App> apps = Sources.installedApps(this);
        for (Sources.App a : apps) {
            Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(a.pkg);
            boolean canSearch = search.resolveActivity(pm) != null;
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://tv.youtube.com/search/test")).setPackage(a.pkg);
            boolean yt = web.resolveActivity(pm) != null;
            String caps = (canSearch ? "search: yes" : "search: no") + (yt ? " · opens tv.youtube.com links" : "");
            col.addView(Ui.button(this, a.label + "\n" + caps + " · " + a.pkg, v -> test(a)));
        }
        col.addView(Ui.button(this, "Open the Log", v -> startActivity(new Intent(this, LogActivity.class))));
    }

    private void test(Sources.App a) {
        EditText in = new EditText(this);
        in.setText("Bruins");
        new AlertDialog.Builder(this).setTitle("Test " + a.label + ": search for")
                .setView(in)
                .setPositiveButton("Test", (d, w) -> {
                    String q = in.getText().toString().trim();
                    DiagLog.add("Test " + a.label + ": searching \"" + q + "\"");
                    boolean ok = Tuner.openSearch(this, a.pkg, q, null);
                    if (!ok) {
                        DiagLog.add("Test " + a.label + ": no search entry point; opening the app instead");
                        Intent li = Sources.launchIntent(this, a.pkg);
                        if (li != null) Tuner.start(this, li, null);
                    }
                    h.postDelayed(AutomationService::inspect, 8000);
                })
                .setNegativeButton("Cancel", null).show();
    }
}
