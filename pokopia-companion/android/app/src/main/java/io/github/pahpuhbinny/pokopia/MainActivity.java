package io.github.pahpuhbinny.pokopia;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

/**
 * Hosts the Pokopia Companion web app in a full-screen WebView.
 *
 * The pages ship inside the APK (assets/www) and are served from
 * https://appassets.androidplatform.net, so the app works offline and
 * its saved progress (localStorage) lives in the app's private storage.
 */
public class MainActivity extends Activity {

    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + ASSET_HOST + "/assets/www/index.html";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.app_background));
        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.app_background));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        setUpEdgeToEdge(root);
        setUpWebView();

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(START_URL);
        }
    }

    /** Draw behind the system bars, then pad the content so nothing hides under them. */
    private void setUpEdgeToEdge(View root) {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        boolean light = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light ? mask : 0, mask);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(false);
            }
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int types = WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime();
                android.graphics.Insets bars = insets.getInsets(types);
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            root.setSystemUiVisibility(flags);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            });
        }
        root.requestApplyInsets();
    }

    private void setUpWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(ASSET_HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (ASSET_HOST.equals(uri.getHost())) {
                    return false;
                }
                // Any outside link opens in the phone's browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    // No app can open it; stay on the current page.
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new NativeBridge(), "PokopiaAndroid");
    }

    /** Lets the web app copy and share its save code with Android. */
    private final class NativeBridge {
        @JavascriptInterface
        public void copyText(String text) {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Pokopia save code", text));
            }
        }

        @JavascriptInterface
        public void shareText(String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "Pokopia Companion save code");
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "Share save code"));
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    /** Back steps through the guide's sections before leaving the app. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
