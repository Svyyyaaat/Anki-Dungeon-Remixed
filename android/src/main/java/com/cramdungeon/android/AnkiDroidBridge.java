package com.cramdungeon.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.cramdungeon.AnkiDeck;
import com.cramdungeon.AnkiPlatform;
import com.cramdungeon.CardOutcome;
import com.cramdungeon.CardRequest;
import com.watabou.noosa.Game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Talks to AnkiDroid through its public content provider (FlashCardsContract).
 * Provider calls run on a background thread; results go back to the game thread.
 */
public class AnkiDroidBridge implements AnkiPlatform {

    private static final String TAG = "CramDungeon";

    static final String ANKIDROID_PACKAGE = "com.ichi2.anki";
    static final String AUTHORITY = "com.ichi2.anki.flashcards";
    static final String PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE";

    private static final Uri BASE = Uri.parse("content://" + AUTHORITY);
    private static final Uri NOTES = Uri.withAppendedPath(BASE, "notes");
    private static final Uri MODELS = Uri.withAppendedPath(BASE, "models");
    private static final Uri DECKS = Uri.withAppendedPath(BASE, "decks");
    private static final Uri SCHEDULE = Uri.withAppendedPath(BASE, "schedule");

    // AnkiDroid hands sound tags through as text; we can't play them
    private static final Pattern SOUND_TAG = Pattern.compile("\\[(sound|anki:play)[^\\]]*\\]");

    private static final int PERMISSION_REQUEST = 0xA4C1;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Activity activity;
    private Consumer<Boolean> permissionCallback;

    public AnkiDroidBridge(Activity activity) {
        this.activity = activity;
    }

    // new cards the player asked to learn once the due ones ran out (noteId, ord)
    private final ArrayDeque<long[]> extraNew = new ArrayDeque<>();
    private final Map<Long, String> cssByModel = new HashMap<>();
    private String lastCardKey = "";
    private String template;

    private static class CardRef {
        final long noteId;
        final int ord;

        CardRef(long noteId, int ord) {
            this.noteId = noteId;
            this.ord = ord;
        }

        String key() {
            return noteId + ":" + ord;
        }
    }

    /** True while a card covers the game; the game should ignore back presses then. */
    public static boolean cardShowing() {
        return CardOverlay.isShowing();
    }

    private Context context() {
        return activity;
    }

    private ContentResolver resolver() {
        return activity.getContentResolver();
    }

    /** Results go back to the game on its render thread. */
    private static void post(Runnable r) {
        Game.runOnRenderThread(r::run);
    }

    private void onUiThread(Runnable r) {
        activity.runOnUiThread(r);
    }

    // status() is asked every turn by sleeping mobs; package manager lookups are binder calls
    private static final long STATUS_TTL_MS = 3000;
    private volatile Status cachedStatus;
    private volatile long cachedAt;

    @Override
    public Status status() {
        long now = SystemClock.elapsedRealtime();
        if (cachedStatus != null && now - cachedAt < STATUS_TTL_MS) {
            return cachedStatus;
        }
        cachedStatus = queryStatus();
        cachedAt = now;
        return cachedStatus;
    }

    private Status queryStatus() {
        Context ctx = context();
        if (ctx.getPackageManager().resolveContentProvider(AUTHORITY, 0) == null) {
            return Status.NOT_INSTALLED;
        }
        if (Build.VERSION.SDK_INT >= 23
                && ctx.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return Status.NO_PERMISSION;
        }
        return Status.READY;
    }

    @Override
    public void requestPermission(Consumer<Boolean> onDone) {
        if (Build.VERSION.SDK_INT < 23) {
            post(() -> onDone.accept(true));
            return;
        }
        onUiThread(() -> {
            permissionCallback = onDone;
            activity.requestPermissions(new String[]{PERMISSION}, PERMISSION_REQUEST);
        });
    }

    /** Forwarded from the activity's onRequestPermissionsResult. */
    public void onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST || permissionCallback == null) {
            return;
        }
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Consumer<Boolean> done = permissionCallback;
        permissionCallback = null;
        cachedStatus = null;
        post(() -> done.accept(granted));
    }

    @Override
    public void listDecks(Consumer<List<AnkiDeck>> onDone) {
        io.execute(() -> {
            List<AnkiDeck> decks = new ArrayList<>();
            try (Cursor c = resolver().query(DECKS, null, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    long id = c.getLong(c.getColumnIndexOrThrow("deck_id"));
                    String name = c.getString(c.getColumnIndexOrThrow("deck_name"));
                    int learn = 0, review = 0, fresh = 0;
                    int countsIdx = c.getColumnIndex("deck_count");
                    if (countsIdx >= 0 && c.getString(countsIdx) != null) {
                        JSONArray counts = new JSONArray(c.getString(countsIdx));
                        learn = counts.optInt(0);
                        review = counts.optInt(1);
                        fresh = counts.optInt(2);
                    }
                    decks.add(new AnkiDeck(id, name, learn, review, fresh));
                }
            } catch (Exception e) {
                Log.e(TAG, "listing decks failed", e);
            }
            Collections.sort(decks, (a, b) -> a.name.compareToIgnoreCase(b.name));
            post(() -> onDone.accept(decks));
        });
    }

    /** A card skipped with bury/suspend; applied in AnkiDroid only once the check is graded. */
    private static class Skipped {
        final CardRef card;
        final String action;
        final long elapsedMs;

        Skipped(CardRef card, String action, long elapsedMs) {
            this.card = card;
            this.action = action;
            this.elapsedMs = elapsedMs;
        }
    }

    /** One flashcard check: the card on screen plus the cards skipped on the way to it. */
    private class Session implements CardOverlay.Listener {
        final long deckId;
        final CardRequest request;
        final Consumer<CardOutcome> onDone;
        final ArrayDeque<Skipped> skipped = new ArrayDeque<>();
        CardRef current;

        Session(long deckId, CardRequest request, Consumer<CardOutcome> onDone) {
            this.deckId = deckId;
            this.request = request;
            this.onDone = onDone;
        }

        private Set<String> shownKeys() {
            Set<String> keys = new HashSet<>();
            keys.add(current.key());
            for (Skipped s : skipped) {
                keys.add(s.card.key());
            }
            return keys;
        }

        @Override
        public void onGraded(int ease, long thinkMs, long totalMs, boolean timedOut) {
            final CardRef graded = current;
            final ArrayDeque<Skipped> toApply = new ArrayDeque<>(skipped);
            io.execute(() -> {
                // AnkiDroid's API can't undo a bury/suspend, so they wait until the check is over
                for (Skipped s : toApply) {
                    buryOrSuspend(s.card, s.action);
                }
                answer(graded, ease, totalMs);
            });
            post(() -> onDone.accept(CardOutcome.answered(ease, thinkMs, timedOut)));
        }

        @Override
        public void onSkip(String action, long elapsedMs) {
            io.execute(() -> {
                try {
                    CardRef next = nextCard(deckId, shownKeys());
                    if (next == null) {
                        onUiThread(CardOverlay::noOtherCards);
                        return;
                    }
                    skipped.push(new Skipped(current, action, elapsedMs));
                    current = next;
                    lastCardKey = next.key();
                    String page = render(next, request, 0, true);
                    onUiThread(() -> CardOverlay.replace(page, 0));
                } catch (Exception e) {
                    Log.e(TAG, "loading the next card failed", e);
                    onUiThread(CardOverlay::noOtherCards);
                }
            });
        }

        @Override
        public void onUndoSkip() {
            io.execute(() -> {
                Skipped back = skipped.poll();
                if (back == null) {
                    onUiThread(CardOverlay::noOtherCards);
                    return;
                }
                try {
                    current = back.card;
                    lastCardKey = back.card.key();
                    String page = render(back.card, request, back.elapsedMs, !skipped.isEmpty());
                    onUiThread(() -> CardOverlay.replace(page, back.elapsedMs));
                } catch (Exception e) {
                    Log.e(TAG, "reloading a skipped card failed", e);
                    onUiThread(CardOverlay::noOtherCards);
                }
            });
        }
    }

    @Override
    public void presentCard(long deckId, CardRequest request, Consumer<CardOutcome> onDone) {
        io.execute(() -> {
            Session session = new Session(deckId, request, onDone);
            String html;
            try {
                session.current = nextCard(deckId, new HashSet<>());
                if (session.current == null) {
                    post(() -> onDone.accept(CardOutcome.noCards()));
                    return;
                }
                html = render(session.current, request, 0, false);
            } catch (Exception e) {
                Log.e(TAG, "loading a card failed", e);
                post(() -> onDone.accept(CardOutcome.error()));
                return;
            }

            lastCardKey = session.current.key();
            final String page = html;
            onUiThread(() -> CardOverlay.show(activity, page, request.thinkTimeMs, session));
        });
    }

    @Override
    public void learnNewCards(long deckId, String deckName, int count, Consumer<Integer> onDone) {
        io.execute(() -> {
            int found = 0;
            String search = "deck:\"" + deckName.replace("\"", "\\\"") + "\" is:new";
            try (Cursor notes = resolver().query(NOTES, new String[]{"_id"}, search, null, null)) {
                while (notes != null && notes.moveToNext() && found < count) {
                    long noteId = notes.getLong(0);
                    Uri cards = Uri.withAppendedPath(Uri.withAppendedPath(NOTES, Long.toString(noteId)), "cards");
                    try (Cursor c = resolver().query(cards, new String[]{"ord", "type"}, null, null, null)) {
                        while (c != null && c.moveToNext() && found < count) {
                            if (c.getInt(1) == 0) { // never studied
                                extraNew.add(new long[]{noteId, c.getInt(0)});
                                found++;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "looking up new cards failed", e);
            }
            final int result = found;
            post(() -> onDone.accept(result));
        });
    }

    @Override
    public void openAnkiDroid() {
        onUiThread(() -> {
            Context ctx = activity;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ANKIDROID_PACKAGE));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=" + ANKIDROID_PACKAGE));
                ctx.startActivity(intent);
            }
        });
    }

    @Override
    public void deckChanged() {
        io.execute(() -> {
            extraNew.clear();
            lastCardKey = "";
        });
    }

    // --- provider access (io thread only)

    /** Next due card that isn't already part of this check ({@code exclude}), else a learned-new one. */
    private CardRef nextCard(long deckId, Set<String> exclude) {
        List<CardRef> due = new ArrayList<>();
        try (Cursor c = resolver().query(SCHEDULE, null, "limit=?,deckID=?",
                new String[]{Integer.toString(exclude.size() + 3), Long.toString(deckId)}, null)) {
            while (c != null && c.moveToNext()) {
                CardRef card = new CardRef(c.getLong(c.getColumnIndexOrThrow("note_id")),
                        c.getInt(c.getColumnIndexOrThrow("ord")));
                if (!exclude.contains(card.key())) {
                    due.add(card);
                }
            }
        }
        // don't show the card just answered again right away if anything else is due
        for (CardRef card : due) {
            if (!card.key().equals(lastCardKey)) {
                return card;
            }
        }
        if (!due.isEmpty()) {
            return due.get(0);
        }
        long[] fresh;
        while ((fresh = extraNew.poll()) != null) {
            CardRef card = new CardRef(fresh[0], (int) fresh[1]);
            if (!exclude.contains(card.key())) {
                return card;
            }
        }
        return null;
    }

    private String render(CardRef card, CardRequest request, long startElapsedMs, boolean canUndo)
            throws Exception {
        Uri noteUri = Uri.withAppendedPath(NOTES, Long.toString(card.noteId));
        Uri cardUri = Uri.withAppendedPath(Uri.withAppendedPath(noteUri, "cards"), Integer.toString(card.ord));

        String question;
        String answer;
        try (Cursor c = resolver().query(cardUri, new String[]{"question", "answer"}, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                throw new IllegalStateException("card " + card.key() + " not found");
            }
            question = c.getString(0);
            answer = c.getString(1);
        }

        String css = "";
        try (Cursor c = resolver().query(noteUri, new String[]{"mid"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                css = modelCss(c.getLong(0));
            }
        }

        JSONObject config = new JSONObject();
        config.put("title", request.title);
        config.put("thinkMs", request.thinkTimeMs);
        config.put("simple", request.simpleGrading);
        config.put("startElapsedMs", startElapsedMs);
        config.put("canUndo", canUndo);

        return template()
                .replace("%%CONFIG%%", config.toString())
                .replace("%%CSS%%", css)
                .replace("%%QUESTION%%", clean(question))
                .replace("%%ANSWER%%", clean(answer));
    }

    private String modelCss(long modelId) {
        String css = cssByModel.get(modelId);
        if (css != null) {
            return css;
        }
        css = "";
        Uri modelUri = Uri.withAppendedPath(MODELS, Long.toString(modelId));
        try (Cursor c = resolver().query(modelUri, new String[]{"css"}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) {
                css = c.getString(0);
            }
        }
        cssByModel.put(modelId, css);
        return css;
    }

    private static String clean(String html) {
        if (html == null) {
            return "";
        }
        return SOUND_TAG.matcher(html).replaceAll("");
    }

    private void buryOrSuspend(CardRef card, String action) {
        ContentValues values = new ContentValues();
        values.put("note_id", card.noteId);
        values.put("ord", card.ord);
        values.put(CardOverlay.SUSPEND.equals(action) ? "suspended" : "buried", 1);
        try {
            resolver().update(SCHEDULE, values, null, null);
        } catch (Exception e) {
            Log.e(TAG, action + " of card " + card.key() + " failed", e);
        }
    }

    private void answer(CardRef card, int ease, long totalMs) {
        ContentValues values = new ContentValues();
        values.put("note_id", card.noteId);
        values.put("ord", card.ord);
        values.put("answer_ease", ease);
        values.put("time_taken", totalMs);
        try {
            resolver().update(SCHEDULE, values, null, null);
        } catch (Exception e) {
            Log.e(TAG, "answering card " + card.key() + " failed", e);
        }
    }

    private String template() throws IOException {
        if (template == null) {
            try (InputStream in = context().getAssets().open("anki/card.html")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                template = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return template;
    }
}
