package com.cramdungeon.android;

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
 * Shows cards in a WebView laid over the game surface. One overlay session can
 * go through several cards (bury/suspend bring a new one, back brings one back)
 * and ends when a card is graded. Only touched from the Android UI thread. The
 * thinking clock keeps running if the app is left.
 */
final class CardOverlay {

    interface Listener {
        void onGraded(int ease, long thinkMs, long totalMs, boolean timedOut);

        /** "bury" or "suspend"; {@code elapsedMs} is the clock of the card being skipped. */
        void onSkip(String action, long elapsedMs);

        /** Bring back the card skipped last. */
        void onUndoSkip();
    }

    static final String BURY = "bury";
    static final String SUSPEND = "suspend";

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static volatile FrameLayout root;
    private static WebView web;
    private static Listener listener;

    private static long thinkLimitMs;
    // the clock: elapsed thinking time is now - shownAt while the question is up
    private static long shownAt;
    private static long thinkMs;
    private static boolean revealed;
    private static boolean timedOut;
    private static boolean finished;
    // waiting for the next card after a bury/suspend/undo
    private static boolean busy;

    private static final Runnable timeout = CardOverlay::onTimeout;

    private CardOverlay() {
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static void show(Activity activity, String html, long thinkLimit, Listener onEvents) {
        dismiss();

        listener = onEvents;
        thinkLimitMs = thinkLimit;
        finished = false;

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
        // the card has to be answered; back does nothing while it is up
        web.setOnKeyListener((v, keyCode, event) -> keyCode == KeyEvent.KEYCODE_BACK);
        web.setFocusableInTouchMode(true);

        root = new FrameLayout(activity);
        root.setClickable(true);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (root.getParent() instanceof ViewGroup) {
            ((ViewGroup) root.getParent()).removeView(root);
        }
        activity.addContentView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        load(html, 0);
        web.requestFocus();
    }

    /** Swaps in another card of the same session, its clock starting at {@code elapsedMs}. */
    static void replace(String html, long elapsedMs) {
        if (web == null || finished) {
            return;
        }
        load(html, elapsedMs);
    }

    /** A bury/suspend found nothing else to show; the current card stays. */
    static void noOtherCards() {
        busy = false;
        if (web != null) {
            web.evaluateJavascript("noOtherCards()", null);
        }
    }

    static boolean isShowing() {
        return root != null;
    }

    private static void load(String html, long elapsedMs) {
        handler.removeCallbacks(timeout);
        shownAt = SystemClock.elapsedRealtime() - elapsedMs;
        thinkMs = 0;
        revealed = false;
        timedOut = false;
        busy = false;
        web.loadDataWithBaseURL("file:///android_asset/anki/", html, "text/html", "utf-8", null);
    }

    private static long elapsed() {
        return Math.min(thinkLimitMs, SystemClock.elapsedRealtime() - shownAt);
    }

    private static void scheduleTimeout() {
        handler.removeCallbacks(timeout);
        if (revealed || finished || busy) {
            return;
        }
        long left = thinkLimitMs - (SystemClock.elapsedRealtime() - shownAt);
        handler.postDelayed(timeout, Math.max(0, left));
    }

    private static void onTimeout() {
        if (revealed || finished || busy || web == null) {
            return;
        }
        revealed = true;
        timedOut = true;
        thinkMs = thinkLimitMs;
        web.evaluateJavascript("timeUp()", null);
    }

    private static void onReveal() {
        if (revealed || finished || busy) {
            return;
        }
        revealed = true;
        thinkMs = elapsed();
        handler.removeCallbacks(timeout);
    }

    /** Answer side back to the question; the clock carries on from where it stopped. */
    private static void onBackToQuestion() {
        if (!revealed || timedOut || finished || busy || web == null) {
            return;
        }
        revealed = false;
        shownAt = SystemClock.elapsedRealtime() - thinkMs;
        scheduleTimeout();
        web.evaluateJavascript("showQuestion(" + thinkMs + ")", null);
    }

    private static void onSkip(String action) {
        if (finished || busy || timedOut || listener == null) {
            return;
        }
        busy = true;
        handler.removeCallbacks(timeout);
        listener.onSkip(action, revealed ? thinkMs : elapsed());
    }

    private static void onUndoSkip() {
        if (finished || busy || revealed || listener == null) {
            return;
        }
        busy = true;
        handler.removeCallbacks(timeout);
        listener.onUndoSkip();
    }

    private static void onGrade(int ease) {
        if (finished || busy || listener == null) {
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
        busy = false;
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

        @JavascriptInterface
        public void back() {
            handler.post(CardOverlay::onBackToQuestion);
        }

        @JavascriptInterface
        public void undoSkip() {
            handler.post(CardOverlay::onUndoSkip);
        }

        @JavascriptInterface
        public void skip(String action) {
            if (BURY.equals(action) || SUSPEND.equals(action)) {
                handler.post(() -> onSkip(action));
            }
        }
    }
}
