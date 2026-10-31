package io.github.timothy_cloudopsguy.opsdeck;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.AndroidRuntimeException;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A small browser for stories: https pages only, no other apps opened from a page, nothing kept when it closes.
 * Back walks the page history, then returns to the deck.
 */
public final class ReaderActivity extends Activity {
    static final String EXTRA_URL = "url";

    private WebView web;
    private TextView address;
    private ProgressBar progress;

    /** Opens the reader; false when the link is not one it would load. */
    static boolean open(Context context, String url) {
        if (!allowed(url)) return false;
        context.startActivity(new Intent(context, ReaderActivity.class).putExtra(EXTRA_URL, url));
        return true;
    }

    /** Pages only over https: no intent:, market:, file: or javascript: links out of a web page. */
    static boolean allowed(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") && lower.length() > "https://".length();
    }

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String url = getIntent().getStringExtra(EXTRA_URL);
        try {
            web = new WebView(this);
        } catch (AndroidRuntimeException e) {
            // The TV's WebView package is missing or disabled.
            Toast.makeText(this, "This TV has no WebView: scan the code with your phone", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (!allowed(url)) {
            finish();
            return;
        }
        WebSettings settings = web.getSettings();
        // Most article pages do not render without it. No JavaScript interface is added, so a page reaches nothing of the app.
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.setBackgroundColor(Color.WHITE);
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? ProgressBar.INVISIBLE : ProgressBar.VISIBLE);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });
        web.setDownloadListener((link, agent, disposition, type, length) ->
                Toast.makeText(this, "Downloads are off in the reader", Toast.LENGTH_SHORT).show());
        setContentView(layout());
        web.requestFocus();
        web.loadUrl(url);
    }

    private ViewGroup layout() {
        int pad = dp(12);
        address = new TextView(this);
        address.setTextColor(Ui.CYAN);
        address.setTextSize(13);
        address.setSingleLine(true);
        TextView hint = new TextView(this);
        hint.setTextColor(Ui.DIM);
        hint.setTextSize(13);
        hint.setSingleLine(true);
        hint.setGravity(Gravity.END);
        hint.setText("Arrows move   OK opens   Channel keys page   Back returns");

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Ui.BG);
        bar.setPadding(pad * 2, pad / 2, pad * 2, pad / 2);
        bar.addView(address, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.addView(bar);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        FrameLayout page = new FrameLayout(this);
        page.addView(web);
        root.addView(page, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (web != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    web.pageDown(false);
                    return true;
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    web.pageUp(false);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else finish();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            // Nothing outlives the visit: no cookies, history or page cache on a shared family TV.
            CookieManager.getInstance().removeAllCookies(null);
            web.clearCache(true);
            web.clearHistory();
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String next = request.getUrl().toString();
            if (allowed(next)) return false;
            Toast.makeText(ReaderActivity.this, "The reader only opens https pages", Toast.LENGTH_SHORT).show();
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            String host = Uri.parse(url).getHost();
            address.setText(host == null ? "" : host);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            Toast.makeText(ReaderActivity.this, "That site's certificate is not valid", Toast.LENGTH_LONG).show();
        }

        /** A page crashed or was killed for memory: leave rather than take the app down with it. */
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Toast.makeText(ReaderActivity.this, "That page was too heavy for this TV", Toast.LENGTH_LONG).show();
            ((ViewGroup) view.getParent()).removeView(view);
            view.destroy();
            web = null;
            finish();
            return true;
        }
    }
}
