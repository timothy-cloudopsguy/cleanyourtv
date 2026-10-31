package io.github.timothy_cloudopsguy.opsdeck;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.AndroidRuntimeException;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The card's screen. Feeds run only while it is visible; nothing stays behind when it closes. */
public final class DeckActivity extends Activity implements Deck.Actions {
    private static final int BANNER_W = 240;
    private static final int BANNER_H = 135;

    private Store store;
    private Sfx sfx;
    private DeckView view;
    private FrameLayout root;
    private final List<WebView> liveViews = new ArrayList<>();
    private boolean visible;
    private boolean liveVisible = true;
    private Deck deck;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // A wall dashboard: stay on while it is open. DeckView drifts a little against burn-in.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SharedPreferences prefs = getSharedPreferences("deck", Context.MODE_PRIVATE);
        store = new Store(this, getString(R.string.github_user), getString(R.string.github_watch_repo));
        sfx = new Sfx(this, prefs);
        deck = new Deck(store, sfx, prefs, this);
        Panel[] panels = {
                new BridgePanel(deck),
                new IntelPanel(deck),
                new StatusPanel(deck),
                new PlanetPanel(deck),
                new VolcanoPanel(deck, this),
                new AirPanel(deck, this),
                new GitPanel(deck),
                new SystemPanel(deck, this),
                new GamePanel(deck),
        };
        view = new DeckView(this, deck, panels);
        root = new FrameLayout(this);
        root.addView(view);
        setContentView(root);
        view.requestFocus();
        new Thread(this::loadApps, "apps").start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        store.start();
        view.resume();
    }

    @Override
    protected void onStop() {
        view.pause();
        store.stop();
        // Decoders and memory go back as soon as the app is out of sight. The panel starts them again on return.
        visible = false;
        applyLive(Collections.emptyList(), Collections.emptyList());
        deck.liveKey = "";
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        sfx.release();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // DeckView takes Back while it has somewhere to go.
        finish();
    }

    @Override
    public void openPanel(int index) {
        view.enterPanel(index);
    }

    @Override
    public void launch(String packageName) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(packageName);
        if (intent == null) intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null) return;
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "That app would not open", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void openLink(String url) {
        if (!ReaderActivity.open(this, url)) {
            Toast.makeText(this, "No page for this one: try the code with your phone", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void watch(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Nothing on this TV can play that stream", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void showLive(List<String> videoIds, List<float[]> boxes) {
        // Panels ask from onDraw, and adding or removing a view during a draw crashes the renderer.
        root.post(() -> applyLive(videoIds, boxes));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void applyLive(List<String> videoIds, List<float[]> boxes) {
        if (!visible && !videoIds.isEmpty()) return;
        for (WebView old : liveViews) {
            root.removeView(old);
            old.destroy();
        }
        liveViews.clear();
        float scale = view.getWidth() / DeckView.W;
        for (int i = 0; i < videoIds.size(); i++) {
            WebView web;
            try {
                web = new WebView(this);
            } catch (AndroidRuntimeException e) {
                return;
            }
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMediaPlaybackRequiresUserGesture(false);
            web.setFocusable(false);
            web.setFocusableInTouchMode(false);
            web.setBackgroundColor(Ui.BG);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    return true;
                }
            });
            float[] box = boxes.get(i);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    Math.round((box[2] - box[0]) * scale), Math.round((box[3] - box[1]) * scale));
            params.leftMargin = Math.round(box[0] * scale);
            params.topMargin = Math.round(box[1] * scale);
            web.setVisibility(liveVisible ? View.VISIBLE : View.INVISIBLE);
            root.addView(web, params);
            liveViews.add(web);
            String html = "<!doctype html><html><body style='margin:0;background:#060a12'>"
                    + "<iframe src='https://www.youtube-nocookie.com/embed/" + videoIds.get(i)
                    + "?autoplay=1&mute=1&controls=0&playsinline=1&modestbranding=1' style='border:0;width:100vw;height:100vh'"
                    + " allow='autoplay; encrypted-media' referrerpolicy='strict-origin-when-cross-origin'></iframe></body></html>";
            // YouTube's player refuses to run without a referring page, so the frame needs a real-looking origin.
            web.loadDataWithBaseURL("https://" + getPackageName().replace('_', '-') + "/", html, "text/html", "utf-8", null);
        }
    }

    @Override
    public void setLiveVisible(boolean show) {
        if (show == liveVisible) return;
        liveVisible = show;
        root.post(this::applyLiveVisible);
    }

    private void applyLiveVisible() {
        for (WebView web : liveViews) web.setVisibility(liveVisible ? View.VISIBLE : View.INVISIBLE);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
        List<Deck.App> apps = new ArrayList<>();
        for (ResolveInfo info : found) {
            ActivityInfo a = info.activityInfo;
            if (getPackageName().equals(a.packageName)) continue;
            apps.add(new Deck.App(String.valueOf(a.loadLabel(pm)), a.packageName, banner(a, pm)));
        }
        Collections.sort(apps, (x, y) -> x.label.toLowerCase(Locale.ROOT).compareTo(y.label.toLowerCase(Locale.ROOT)));
        deck.apps = apps;
    }

    /** The app's TV banner, or its icon centred when it has none, at the row's size so memory stays small. */
    private static Bitmap banner(ActivityInfo a, PackageManager pm) {
        Drawable banner = a.loadBanner(pm);
        Bitmap out = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (banner != null) {
            banner.setBounds(0, 0, BANNER_W, BANNER_H);
            banner.draw(c);
            return out;
        }
        c.drawColor(Ui.PANEL_HI);
        Drawable icon = a.loadIcon(pm);
        int side = BANNER_H * 2 / 3;
        int left = (BANNER_W - side) / 2;
        int top = (BANNER_H - side) / 2;
        icon.setBounds(left, top, left + side, top + side);
        icon.draw(c);
        return out;
    }
}
