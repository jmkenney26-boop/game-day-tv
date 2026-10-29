package com.gameday.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

/** The accessibility switch, the remote, and timing. */
public class SetupActivity extends Activity {
    @Override protected void onResume() { super.onResume(); build(); }

    private void build() {
        LinearLayout col = Ui.page(this, "Setup", null);
        col.addView(Ui.section(this, "1. Accessibility switch  ·  " + (AutomationService.isOn() ? "ON" : "OFF")));
        col.addView(Ui.text(this, "Lets Game Day pick the game inside your apps and use the remote's ← / → to change games. "
                + "Open Accessibility, choose \"Game Day\", and turn it on.", 15, Ui.MUTED));
        col.addView(Ui.button(this, "Open Accessibility settings", v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception e) { DiagLog.add("This box has no Accessibility settings screen to open: " + e.getMessage()); }
        }));
        col.addView(Ui.text(this, "Greyed out? Android 14 blocks this for apps not from the Play Store. Unlocking it needs "
                + "\"network debugging\" from Developer options. If your box hides that menu, try the button below.", 14, Ui.MUTED));
        col.addView(Ui.button(this, "Open Developer options (even if the box hides the menu)", v -> openDeveloperOptions()));
        col.addView(Ui.section(this, "2. Remote"));
        boolean arrows = Prefs.getBool(this, "remoteArrows", true);
        col.addView(Ui.button(this, "← / → change games while Goal Zone / Red Zone run: " + (arrows ? "ON" : "OFF")
                + "\n(While on, those buttons don't move around inside the app on screen. Channel and skip buttons always work.)", v -> {
            Prefs.putBool(this, "remoteArrows", !arrows);
            build();
        }));
        col.addView(Ui.section(this, "3. Timing"));
        number(col, "streamDelaySeconds", 30, "How far your streaming app runs behind live (seconds)");
        number(col, "commercialLeadSeconds", 10, "Leave a game this many seconds before its commercial reaches your screen");
        number(col, "commercialTailSeconds", 25, "After a commercial or intermission, wait this long before going back to that game");
        number(col, "rotateSeconds", 90, "With nothing happening, change games every (seconds)");
        boolean skip = Prefs.getBool(this, "skipCommercials", true);
        col.addView(Ui.button(this, "Skip games on commercial (automatic picks only): " + (skip ? "ON" : "OFF"), v -> {
            Prefs.putBool(this, "skipCommercials", !skip);
            BrainService.settingsChanged(this);
            build();
        }));
    }

    /** Developer options may be hidden from the menu but still there: try the standard page, then the TV settings app's. */
    private void openDeveloperOptions() {
        Intent[] tries = {
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                new Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.system.development.DevelopmentActivity"),
                new Intent().setClassName("com.android.settings", "com.android.settings.Settings$DevelopmentSettingsDashboardActivity"),
                new Intent().setClassName("com.android.settings", "com.android.settings.DevelopmentSettings"),
        };
        for (Intent i : tries) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                DiagLog.add("Opened Developer options via " + i);
                return;
            } catch (Exception e) {
                DiagLog.add("Developer options not at " + i + ": " + e.getClass().getSimpleName());
            }
        }
        DiagLog.add("This box doesn't let any app open Developer options.");
        new AlertDialog.Builder(this).setTitle("Developer options are locked")
                .setMessage("This box doesn't let any app open Developer options, so the accessibility switch can't be unlocked on it. "
                        + "Goal Zone and Red Zone still work: they open the right app and search for the game, and you pick it.")
                .setPositiveButton("OK", null).show();
    }

    private void number(LinearLayout col, String key, int def, String label) {
        int now = Prefs.getInt(this, key, def);
        col.addView(Ui.button(this, label + ":  " + now, v -> {
            EditText in = new EditText(this);
            in.setInputType(InputType.TYPE_CLASS_NUMBER);
            in.setText(String.valueOf(now));
            new AlertDialog.Builder(this).setTitle(label).setView(in).setPositiveButton("Save", (d, w) -> {
                try { Prefs.putInt(this, key, Integer.parseInt(in.getText().toString().trim())); } catch (Exception ignored) {}
                BrainService.settingsChanged(this);
                build();
            }).setNegativeButton("Cancel", null).show();
        }));
    }
}
