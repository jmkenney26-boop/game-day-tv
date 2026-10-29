package com.gameday.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Multiview: up to four web pages on screen at once, each filling its tile with the page's video.
 *   ← ↑ → ↓   move between tiles (the selected tile has the sound and a thin outline)
 *   OK        tile menu: pick a link, Goal Zone, Red Zone, an app, full screen, use the page…
 *   CH+ / CH− (or ⏭ / ⏮)  next / previous saved link in this tile (or game, for Goal Zone / Red Zone)
 *   Back      leaves full screen, then Multiview
 */
public class MvPlayerActivity extends Activity {
    static MvPlayerActivity self;
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final Handler h = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private final List<Tile> tiles = new ArrayList<>();
    private int sel = 0;
    private int full = -1;          // tile shown full screen, or -1
    private Tile interacting;       // tile whose page gets the remote's keys
    private String tileScript = "";

    final class Tile {
        final int index;
        final FrameLayout box;
        final WebView web;
        final TextView label, outline;
        String type = "none", url = "", name = "", pkg = "";
        long loadedAt = 0;
        final Runnable labelHide = new Runnable() { @Override public void run() { Tile.this.label.setVisibility(View.GONE); } };

        Tile(int index) {
            this.index = index;
            box = new FrameLayout(MvPlayerActivity.this);
            box.setBackgroundColor(Color.BLACK);
            web = makeWeb(this);
            box.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            outline = new TextView(MvPlayerActivity.this);
            box.addView(outline, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            label = new TextView(MvPlayerActivity.this);
            label.setTextColor(Color.WHITE);
            label.setTextSize(15);
            label.setPadding(Ui.dp(MvPlayerActivity.this, 10), Ui.dp(MvPlayerActivity.this, 6), Ui.dp(MvPlayerActivity.this, 10), Ui.dp(MvPlayerActivity.this, 6));
            label.setBackgroundColor(0xCC000000);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            box.addView(label, lp);
            label.setVisibility(View.GONE);
        }

        void load(String newType, String newUrl, String newName) {
            type = newType;
            url = newUrl == null ? "" : newUrl;
            name = newName == null ? "" : newName;
            loadedAt = SystemClock.elapsedRealtime();
            if (url.isEmpty()) web.loadUrl("about:blank");
            else web.loadUrl(url);
            flash(this, label());
            saveTiles();
        }

        String label() {
            String what = "gz".equals(type) ? "Goal Zone" : "rz".equals(type) ? "Red Zone" : "app".equals(type) ? Sources.appLabel(MvPlayerActivity.this, pkg) : "";
            if (!name.isEmpty()) what = what.isEmpty() ? name : what + " · " + name;
            return (index + 1) + "  " + (what.isEmpty() ? "Empty: press OK to pick" : what);
        }
    }

    // ---------- set up ----------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        self = this;
        DiagLog.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        tileScript = asset("mv/tile.js");
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        Multiview.setActive(this, true);
        int n = Multiview.tileCount(this);
        for (int i = 0; i < n; i++) {
            Tile t = new Tile(i);
            tiles.add(t);
            root.addView(t.box);
        }
        layoutTiles();
        restoreTiles();
        select(0);
        h.postDelayed(keepFilled, 3000);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        for (Tile t : tiles) { try { t.web.destroy(); } catch (Exception ignored) {} }
        if (self == this) self = null;
        Multiview.setActive(this, false);
        BrainService.stop(this, "gz");
        BrainService.stop(this, "rz");
        super.onDestroy();
    }

    private String asset(String path) {
        try (InputStream in = getAssets().open(path)) {
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), "UTF-8");
        } catch (Exception e) { return ""; }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView makeWeb(Tile t) {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(DESKTOP_UA);
        s.setSupportMultipleWindows(true);            // …so pop-ups come to onCreateWindow, which refuses them
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setFocusable(false);
        w.setFocusableInTouchMode(false);
        w.setBackgroundColor(Color.BLACK);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // In every frame, including players embedded from other sites.
            WebViewCompat.addDocumentStartJavaScript(w, tileScript, Collections.singleton("*"));
        }
        w.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture, Message resultMsg) {
                DiagLog.add("Tile " + (t.index + 1) + ": blocked a pop-up");
                return false;
            }
        });
        w.setWebViewClient(new WebViewClient() {
            // Ads love to send the whole page elsewhere. Only allow that while the page first loads, or when you're using the page.
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                String scheme = r.getUrl().getScheme();
                if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) return true;   // app-store / intent links
                boolean early = SystemClock.elapsedRealtime() - t.loadedAt < 8000;
                if (early || interacting == t || sameSite(r.getUrl(), t.url)) return false;
                DiagLog.add("Tile " + (t.index + 1) + ": blocked a jump to " + r.getUrl().getHost());
                return true;
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) v.evaluateJavascript(tileScript, null);
                tend(t);
            }
        });
        return w;
    }

    private static boolean sameSite(Uri a, String b) {
        try {
            String ha = a.getHost(), hb = Uri.parse(b).getHost();
            if (ha == null || hb == null) return false;
            String[] pa = ha.split("\\."), pb = hb.split("\\.");
            String ra = pa.length >= 2 ? pa[pa.length - 2] + "." + pa[pa.length - 1] : ha;
            String rb = pb.length >= 2 ? pb[pb.length - 2] + "." + pb[pb.length - 1] : hb;
            return ra.equalsIgnoreCase(rb);
        } catch (Exception e) { return false; }
    }

    // ---------- layout ----------
    private void layoutTiles() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        int w = m.widthPixels, hgt = m.heightPixels, n = tiles.size();
        for (int i = 0; i < n; i++) {
            int[] r;   // x, y, w, h
            if (full >= 0) r = i == full ? new int[]{0, 0, w, hgt} : new int[]{0, 0, 0, 0};
            else if (n == 1) r = new int[]{0, 0, w, hgt};
            else if (n == 2) { int tw = w / 2, th = tw * 9 / 16; r = new int[]{i * tw, (hgt - th) / 2, tw, th}; }
            else if (n == 3) {
                int bw = w * 2 / 3, bh = bw * 9 / 16, sw = w - bw, sh = sw * 9 / 16, top = (hgt - bh) / 2;
                r = i == 0 ? new int[]{0, top, bw, bh} : new int[]{bw, top + (i - 1) * sh, sw, sh};
            } else { int tw = w / 2, th = hgt / 2; r = new int[]{(i % 2) * tw, (i / 2) * th, tw, th}; }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(r[2], 1), Math.max(r[3], 1));
            lp.leftMargin = r[0];
            lp.topMargin = r[1];
            Tile t = tiles.get(i);
            t.box.setLayoutParams(lp);
            t.box.setVisibility(r[2] == 0 ? View.INVISIBLE : View.VISIBLE);
        }
    }

    // ---------- selection, sound, outline ----------
    private void select(int i) {
        sel = Math.max(0, Math.min(tiles.size() - 1, i));
        for (Tile t : tiles) {
            boolean on = t.index == sel;
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.TRANSPARENT);
            if (on && tiles.size() > 1) g.setStroke(Ui.dp(this, 2), Color.WHITE);
            t.outline.setBackground(g);
            t.web.evaluateJavascript("window.__gdmv&&__gdmv.mute(" + !on + ")", null);
        }
        flash(tiles.get(sel), tiles.get(sel).label());
    }

    private void flash(Tile t, String text) {
        t.label.setText(text);
        t.label.setVisibility(View.VISIBLE);
        h.removeCallbacks(t.labelHide);
        h.postDelayed(t.labelHide, 3000);
    }

    // keep each page's video filling its tile and playing (pages keep changing)
    private final Runnable keepFilled = new Runnable() {
        @Override public void run() {
            for (Tile t : tiles) tend(t);
            h.postDelayed(this, 3000);
        }
    };

    private void tend(Tile t) {
        if (t.url.isEmpty() || interacting == t) return;
        t.web.evaluateJavascript("window.__gdmv&&(__gdmv.fill(),__gdmv.play(),__gdmv.mute(" + (t.index != sel) + "))", null);
    }

    // ---------- remote ----------
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (interacting != null) {
            if (k == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) stopInteracting();
                return true;
            }
            return super.dispatchKeyEvent(e);   // the page gets the keys
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return isOurs(k) || super.dispatchKeyEvent(e);
        Tile t = tiles.get(sel);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: menu(t); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: stepTile(t, 1); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_REWIND: stepTile(t, -1); return true;
            case KeyEvent.KEYCODE_BACK:
                if (full >= 0) { full = -1; layoutTiles(); return true; }
                finish();
                return true;
            default: return super.dispatchKeyEvent(e);
        }
    }

    private static boolean isOurs(int k) {
        return k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK
                || k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_CHANNEL_DOWN;
    }

    /** Arrow keys: to the tile in that direction. */
    private void move(int dx, int dy) {
        if (full >= 0) { stepTile(tiles.get(sel), dx != 0 ? dx : dy); return; }   // full screen: arrows change what's in it
        int n = tiles.size(), next = sel;
        if (n == 2) next = Math.max(0, Math.min(1, sel + dx));
        else if (n == 3) {
            if (dx < 0) next = 0;
            else if (dx > 0 && sel == 0) next = 1;
            else if (dy != 0 && sel > 0) next = dy > 0 ? 2 : 1;
        } else if (n == 4) {
            int col = sel % 2 + dx, row = sel / 2 + dy;
            if (col >= 0 && col <= 1 && row >= 0 && row <= 1) next = row * 2 + col;
        }
        if (next != sel) select(next);
    }

    /** CH+ / CH−: the next / previous saved link here, or the next / previous game for Goal Zone / Red Zone. */
    private void stepTile(Tile t, int step) {
        if ("gz".equals(t.type) || "rz".equals(t.type)) {
            if (step > 0) BrainService.next(this, t.type); else BrainService.prev(this, t.type);
            flash(t, (t.index + 1) + "  " + ("gz".equals(t.type) ? "Goal Zone" : "Red Zone") + (step > 0 ? ": next game" : ": previous game"));
            return;
        }
        Links.Link l = Links.step(this, t.url, step);
        if (l == null) { flash(t, "No saved links yet: add some from your phone (OK › Add links)"); return; }
        t.load("link", l.url, l.name);
    }

    // ---------- tile menu ----------
    private void menu(Tile t) {
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        List<Links.Link> links = Links.all(this);
        for (Links.Link l : links) { items.add("▶  " + l.name); acts.add(() -> t.load("link", l.url, l.name)); }
        items.add("Goal Zone (NHL) in this tile"); acts.add(() -> startZone(t, "gz"));
        items.add("CFB Red Zone in this tile"); acts.add(() -> startZone(t, "rz"));
        items.add(full == t.index ? "Back to all tiles" : "Full screen this tile"); acts.add(() -> { full = full == t.index ? -1 : t.index; layoutTiles(); });
        items.add("Use the page (the remote moves around the page; Back to finish)"); acts.add(() -> startInteracting(t));
        items.add("Tap the middle of the video (starts some players)"); acts.add(() -> tapMiddle(t));
        items.add("Reload"); acts.add(() -> { if (!t.url.isEmpty()) t.load(t.type, t.url, t.name); });
        items.add("Open an app (experimental: takes over the screen unless your box allows windows)"); acts.add(() -> pickApp(t));
        items.add("Add links from your phone…"); acts.add(this::showAddLinks);
        items.add("Layout: " + tiles.size() + " tiles (change)"); acts.add(this::pickLayout);
        items.add("Empty this tile"); acts.add(() -> { stopZoneIfOwned(t); t.load("none", "", ""); });
        new AlertDialog.Builder(this).setTitle("Tile " + (t.index + 1) + (t.name.isEmpty() ? "" : ": " + t.name))
                .setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private void startZone(Tile t, String mode) {
        for (Tile o : tiles) if (o != t && mode.equals(o.type)) o.load("none", "", "");   // one tile per zone
        stopZoneIfOwned(t);
        t.load(mode, "", "");
        flash(t, (t.index + 1) + "  " + ("gz".equals(mode) ? "Goal Zone" : "Red Zone") + ": looking for a live game…");
        BrainService.start(this, mode);
    }

    private void stopZoneIfOwned(Tile t) {
        if ("gz".equals(t.type) || "rz".equals(t.type)) BrainService.stop(this, t.type);
    }

    private void pickApp(Tile t) {
        List<Sources.App> apps = Sources.installedApps(this);
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).label;
        new AlertDialog.Builder(this).setTitle("App for tile " + (t.index + 1)).setItems(names, (d, w) -> {
            Sources.App a = apps.get(w);
            stopZoneIfOwned(t);
            t.pkg = a.pkg;
            t.load("app", "", "");
            android.content.Intent li = Sources.launchIntent(this, a.pkg);
            boolean ok = li != null && Tuner.start(this, li, Multiview.windowsAllowed(this) ? Multiview.tileRect(this, t.index) : null);
            DiagLog.add("Tile " + (t.index + 1) + ": " + a.label + (ok ? " opened" + (Multiview.windowsAllowed(this) ? " in a window" : " (full screen: this box doesn't allow windows)") : " couldn't be opened"));
        }).show();
    }

    private void pickLayout() {
        String[] opts = {"1 tile", "2 side by side", "3 (one big, two small)", "4 (2 × 2)"};
        new AlertDialog.Builder(this).setTitle("Layout").setItems(opts, (d, w) -> {
            JSONObject cfg = Multiview.config(this);
            try { cfg.put("layout", String.valueOf(w + 1)); } catch (Exception ignored) {}
            Multiview.save(this, cfg);
            recreate();
        }).show();
    }

    private void showAddLinks() {
        LinkServer.start(this, null);
        new AlertDialog.Builder(this).setTitle("Add links from your phone")
                .setMessage("On your iPhone (same Wi-Fi as this box), open Safari and go to:\n\n" + LinkServer.address()
                        + "\n\nPaste a link and tap Add link. It appears in each tile's menu right away.")
                .setPositiveButton("Done", null).show();
    }

    // ---------- using the page itself ----------
    private void startInteracting(Tile t) {
        interacting = t;
        t.web.setFocusable(true);
        t.web.setFocusableInTouchMode(true);
        t.web.requestFocus();
        flash(t, (t.index + 1) + "  Using the page: arrows move, OK clicks, Back when done");
    }

    private void stopInteracting() {
        Tile t = interacting;
        interacting = null;
        if (t == null) return;
        t.web.setFocusable(false);
        t.web.setFocusableInTouchMode(false);
        tend(t);
        select(t.index);
    }

    private void tapMiddle(Tile t) {
        long now = SystemClock.uptimeMillis();
        float x = t.web.getWidth() / 2f, y = t.web.getHeight() / 2f;
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
        t.web.dispatchTouchEvent(down);
        t.web.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    // ---------- Goal Zone / Red Zone play their games in their tile ----------
    /** Called by {@link Tuner}: play this link in the tile running that zone. False if there's no such tile. */
    boolean playInZoneTile(String mode, String url, String label) {
        for (Tile t : tiles) {
            if (mode.equals(t.type)) {
                t.type = mode;
                t.url = url;
                t.name = label;
                t.loadedAt = SystemClock.elapsedRealtime();
                t.web.loadUrl(url);
                flash(t, t.label());
                return true;
            }
        }
        return false;
    }

    boolean hasZoneTile(String mode) {
        for (Tile t : tiles) if (mode.equals(t.type)) return true;
        return false;
    }

    // ---------- remembering what's in each tile ----------
    private void saveTiles() {
        JSONObject cfg = Multiview.config(this);
        try {
            JSONArray arr = cfg.getJSONArray("tiles");
            for (Tile t : tiles) {
                JSONObject o = new JSONObject().put("type", t.type);
                if ("link".equals(t.type)) o.put("url", t.url).put("name", t.name);
                if ("app".equals(t.type)) o.put("pkg", t.pkg);
                arr.put(t.index, o);
            }
        } catch (Exception ignored) {}
        Multiview.save(this, cfg);
    }

    private void restoreTiles() {
        JSONArray arr = Multiview.config(this).optJSONArray("tiles");
        for (Tile t : tiles) {
            JSONObject o = arr == null ? null : arr.optJSONObject(t.index);
            String type = o == null ? "none" : o.optString("type", "none");
            if ("link".equals(type)) t.load("link", o.optString("url"), o.optString("name"));
            else if ("gz".equals(type) || "rz".equals(type)) startZone(t, type);
            else { t.type = "none"; flash(t, t.label()); }
        }
    }
}
