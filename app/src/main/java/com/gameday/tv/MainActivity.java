package com.gameday.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

/** Game Day Multiview: the home screen. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DiagLog.init(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinearLayout col = Ui.page(this, "Game Day Multiview", "Goal Zone, CFB Red Zone and Multiview, playing from the apps you choose.");
        if (!AutomationService.isOn()) {
            col.addView(Ui.text(this, "First: turn on the accessibility switch in Setup, so Game Day can pick games inside your apps and use your remote's arrows.", 16, Ui.RED));
        }
        col.addView(Ui.button(this, "Goal Zone  ·  every NHL goal, OT and power play", v -> go(GoalZoneActivity.class)));
        col.addView(Ui.button(this, "CFB Red Zone  ·  Top 25, SEC and Big Ten", v -> go(RedZoneActivity.class)));
        col.addView(Ui.button(this, "Multiview  ·  several apps on screen at once (experimental)", v -> go(MultiviewActivity.class)));
        col.addView(Ui.button(this, "Sources  ·  which app plays each network", v -> go(SourcesActivity.class)));
        col.addView(Ui.button(this, "What works on this box  ·  test your apps", v -> go(AppsActivity.class)));
        col.addView(Ui.button(this, "Setup  ·  accessibility switch, remote, timing", v -> go(SetupActivity.class)));
        col.addView(Ui.button(this, "Log  ·  what happened, step by step", v -> go(LogActivity.class)));
    }

    private void go(Class<?> c) { startActivity(new Intent(this, c)); }
}
