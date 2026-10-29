package com.gameday.tv;

/** Goal Zone (its own icon on the home screen, and inside Game Day Multiview). */
public class GoalZoneActivity extends ZoneActivity {
    @Override String mode() { return "gz"; }
    @Override String title() { return "Goal Zone"; }
    @Override String blurb() { return "Every NHL goal, OT and power play. Skips commercials; favours close, late games."; }
}
