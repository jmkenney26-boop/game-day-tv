# Game Day TV

Android TV app (NVIDIA Shield, VSeeBox, Google TV) version of Game Day Multiview:

- **Goal Zone** and **CFB Red Zone**: their own icons, and inside Game Day Multiview. They pick games exactly like the
  Chrome extension (goals, power plays, late close games, skipping commercials) and open each game in the app you
  chose for its network.
- **Sources**: pick the app for each network, and how the game is opened (find it in the app, open a link, or just
  open the app).
- **What works on this box**: tests each installed app (search, links, readable screen) and logs the result.
- **Multiview (experimental)**: several apps on screen at once, if the box allows freeform windows.

Every push to `main` builds `GameDayTV.apk` (GitHub Actions) and attaches it to the **latest** release.

The game-picking logic lives in the extension (`nhl.js`, `director-core.js`, `cfb.js`, `cfb-director.js`) and is packed
into `app/src/main/assets/brain/bundle.js` by `tools/bundle.py`.
