package com.gameday.tv;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;

/** Every step Game Day took, newest first. */
public class LogActivity extends Activity {
    @Override
    protected void onResume() {
        super.onResume();
        DiagLog.init(this);
        LinearLayout col = Ui.page(this, "Log", "Newest first. Take a photo of this if something doesn't work.");
        col.addView(Ui.button(this, "Refresh", v -> onResume()));
        col.addView(Ui.button(this, "Clear the log", v -> { DiagLog.clear(); onResume(); }));
        col.addView(Ui.text(this, DiagLog.recent(300), 13, Ui.TEXT));
    }
}
