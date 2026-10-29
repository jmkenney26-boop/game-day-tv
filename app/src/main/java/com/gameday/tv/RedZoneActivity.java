package com.gameday.tv;

/** CFB Red Zone (its own icon on the home screen, and inside Game Day Multiview). */
public class RedZoneActivity extends ZoneActivity {
    @Override String mode() { return "rz"; }
    @Override String title() { return "CFB Red Zone"; }
    @Override String blurb() { return "Top 25, SEC and Big Ten, NFL RedZone style. Skips commercials; favours close, late games."; }
}
