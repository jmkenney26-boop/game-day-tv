package com.gameday.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Plain, remote-friendly screens: big buttons you move between with the d-pad. */
final class Ui {
    private Ui() {}
    static final int BG = Color.rgb(13, 16, 22), PANEL = Color.rgb(28, 33, 43), TEXT = Color.rgb(236, 239, 244),
            MUTED = Color.rgb(150, 160, 175), RED = Color.rgb(214, 48, 49), FOCUS = Color.rgb(64, 132, 255);

    static LinearLayout page(Activity a, String title, String subtitle) {
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 36);
        col.setPadding(pad, dp(a, 24), pad, dp(a, 24));
        sv.addView(col);
        a.setContentView(sv);
        TextView h = text(a, title, 30, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(h);
        if (subtitle != null) {
            TextView s = text(a, subtitle, 15, MUTED);
            s.setPadding(0, dp(a, 4), 0, dp(a, 14));
            col.addView(s);
        }
        return col;
    }

    static TextView text(Activity a, String t, int sp, int color) {
        TextView v = new TextView(a);
        v.setText(t);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return v;
    }

    static Button button(Activity a, String label, View.OnClickListener onClick) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(a, 18), dp(a, 10), dp(a, 18), dp(a, 10));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, round(FOCUS, dp(a, 10)));
        sl.addState(new int[]{android.R.attr.state_pressed}, round(FOCUS, dp(a, 10)));
        sl.addState(new int[]{}, round(PANEL, dp(a, 10)));
        b.setBackground(sl);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 8);
        b.setLayoutParams(lp);
        return b;
    }

    static LinearLayout row(Activity a) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    static View gap(Activity a, int dp) {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(a, dp)));
        return v;
    }

    static TextView section(Activity a, String t) {
        TextView v = text(a, t, 20, TEXT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(a, 18), 0, dp(a, 2));
        return v;
    }

    static GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
}
