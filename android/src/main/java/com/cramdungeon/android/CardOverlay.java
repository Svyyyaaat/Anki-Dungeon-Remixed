package com.cramdungeon.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.cramdungeon.CardMedia;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Shows cards in a WebView laid over the game surface. One overlay session can
 * go through several cards (bury/suspend bring a new one, back brings one back)
 * and ends when a card is graded. Only touched from the Android UI thread. The
 * thinking clock keeps running if the app is left.
 *
 * <p>Pages load from a made-up https origin so that the card's own references
 * ({@code <img src="cat.jpg">}, deck fonts) resolve to the imported card media,
 * and the page chrome lives under {@code /_cram/}. Audio plays natively.
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

    static final String HOST = "cram.media";
    static final String BASE_URL = "https://" + HOST + "/";
    // page chrome: /_cram/<file> is assets/anki/<file>, /_cram/fonts/<file> is assets/fonts/<file>
    private static final String CHROME = "_cram";

    private static volatile FrameLayout root;
    private static WebView web;
    private static Listener listener;
    private static CardMedia media;

    private static final ArrayDeque<File> soundQueue = new ArrayDeque<>();
    private static MediaPlayer player;

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
    static void show(Activity activity, CardMedia cardMedia, String html, long thinkLimit, Listener onEvents) {
        dismiss();

        listener = onEvents;
        media = cardMedia;
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

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serve(view.getContext(), request.getUrl());
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
        stopSounds();
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null);
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
        stopSounds();
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

    // --- page resources (WebView IO thread)

    private static WebResourceResponse serve(android.content.Context context, Uri url) {
        if (!HOST.equals(url.getHost())) {
            return null;
        }
        List<String> path = url.getPathSegments();
        try {
            if (path.size() >= 2 && CHROME.equals(path.get(0))) {
                List<String> rest = path.subList(1, path.size());
                if (rest.contains("..")) {
                    return notFound();
                }
                String asset = rest.size() >= 2 && "fonts".equals(rest.get(0))
                        ? "fonts/" + rest.get(1)
                        : "anki/" + android.text.TextUtils.join("/", rest);
                return new WebResourceResponse(mimeType(asset), null, context.getAssets().open(asset));
            }
            CardMedia store = media;
            File file = path.size() == 1 && store != null ? store.file(path.get(0)) : null;
            if (file != null) {
                return new WebResourceResponse(mimeType(file.getName()), null, new FileInputStream(file));
            }
        } catch (IOException e) {
            // fall through to a 404
        }
        return notFound();
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "svg":
                return "image/svg+xml";
            case "ttf":
                return "font/ttf";
            case "otf":
                return "font/otf";
            case "woff":
                return "font/woff";
            case "woff2":
                return "font/woff2";
            case "css":
                return "text/css";
            default:
                String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return type != null ? type : "application/octet-stream";
        }
    }

    // --- audio (UI thread)

    private static void playSounds(String namesJson) {
        stopSounds();
        CardMedia store = media;
        if (store == null || web == null) {
            return;
        }
        try {
            JSONArray names = new JSONArray(namesJson);
            for (int i = 0; i < names.length(); i++) {
                File f = store.file(names.optString(i));
                if (f != null) {
                    soundQueue.add(f);
                }
            }
        } catch (Exception e) {
            return;
        }
        playNext();
    }

    private static void playNext() {
        releasePlayer();
        File next = soundQueue.poll();
        if (next == null) {
            return;
        }
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setDataSource(next.getAbsolutePath());
            p.setOnCompletionListener(mp -> {
                if (player == mp) {
                    playNext();
                }
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (player == mp) {
                    playNext();
                }
                return true;
            });
            p.setOnPreparedListener(mp -> {
                if (player == mp) {
                    mp.start();
                }
            });
            p.prepareAsync();
        } catch (IOException | RuntimeException e) {
            playNext();
        }
    }

    private static void releasePlayer() {
        if (player != null) {
            MediaPlayer p = player;
            player = null;
            try {
                p.release();
            } catch (RuntimeException ignored) {
                // already released
            }
        }
    }

    private static void stopSounds() {
        soundQueue.clear();
        releasePlayer();
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

        /** Plays the named card media files one after another, replacing whatever was playing. */
        @JavascriptInterface
        public void play(String namesJson) {
            handler.post(() -> playSounds(namesJson));
        }

        @JavascriptInterface
        public void stopAudio() {
            handler.post(CardOverlay::stopSounds);
        }

        @JavascriptInterface
        public void skip(String action) {
            if (BURY.equals(action) || SUSPEND.equals(action)) {
                handler.post(() -> onSkip(action));
            }
        }
    }
}
