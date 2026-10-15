package com.ankidungeon.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Shows one card in a WebView laid over the game surface. Only touched from the
 * Android UI thread. The thinking clock keeps running if the app is left.
 */
final class CardOverlay {

    interface Listener {
        void onGraded(int ease, long thinkMs, long totalMs, boolean timedOut);
    }

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static FrameLayout root;
    private static WebView web;
    private static Listener listener;

    private static long thinkLimitMs;
    private static long shownAt;
    private static long thinkMs;
    private static boolean revealed;
    private static boolean timedOut;
    private static boolean finished;

    private static final Runnable timeout = CardOverlay::onTimeout;

    private CardOverlay() {
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static void show(Activity activity, String html, long thinkLimit, Listener onGraded) {
        dismiss();

        listener = onGraded;
        thinkLimitMs = thinkLimit;
        revealed = false;
        timedOut = false;
        finished = false;
        shownAt = SystemClock.elapsedRealtime();

        web = new WebView(activity);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "AnkiBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                scheduleTimeout();
            }
        });
        // the card decides; back does nothing while it is up
        web.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
        web.setFocusableInTouchMode(true);

        root = new FrameLayout(activity);
        root.setClickable(true);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        attach(activity);
        web.loadDataWithBaseURL("file:///android_asset/anki/", html, "text/html", "utf-8", null);
        web.requestFocus();
    }

    /** The game activity rebuilds its content view on resume; put the card back on top. */
    static void reattach(Activity activity) {
        if (root == null) {
            return;
        }
        attach(activity);
        web.requestFocus();
        scheduleTimeout();
    }

    private static void attach(Activity activity) {
        if (root.getParent() instanceof ViewGroup) {
            ((ViewGroup) root.getParent()).removeView(root);
        }
        activity.addContentView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static void scheduleTimeout() {
        handler.removeCallbacks(timeout);
        if (revealed || finished) {
            return;
        }
        long left = thinkLimitMs - (SystemClock.elapsedRealtime() - shownAt);
        handler.postDelayed(timeout, Math.max(0, left));
    }

    private static void onTimeout() {
        if (revealed || finished || web == null) {
            return;
        }
        revealed = true;
        timedOut = true;
        thinkMs = thinkLimitMs;
        web.evaluateJavascript("timeUp()", null);
    }

    private static void onReveal() {
        if (revealed || finished) {
            return;
        }
        revealed = true;
        thinkMs = Math.min(thinkLimitMs, SystemClock.elapsedRealtime() - shownAt);
        handler.removeCallbacks(timeout);
    }

    private static void onGrade(int ease) {
        if (finished || listener == null) {
            return;
        }
        if (!revealed) {
            onReveal();
        }
        finished = true;
        long total = SystemClock.elapsedRealtime() - shownAt;
        Listener done = listener;
        dismiss();
        done.onGraded(timedOut ? 1 : ease, thinkMs, total, timedOut);
    }

    private static void dismiss() {
        handler.removeCallbacks(timeout);
        if (root != null) {
            if (root.getParent() instanceof ViewGroup) {
                ((ViewGroup) root.getParent()).removeView(root);
            }
            web.destroy();
        }
        root = null;
        web = null;
        listener = null;
    }

    private static class Bridge {
        @JavascriptInterface
        public void reveal() {
            handler.post(CardOverlay::onReveal);
        }

        @JavascriptInterface
        public void grade(int ease) {
            handler.post(() -> onGrade(ease));
        }
    }
}
