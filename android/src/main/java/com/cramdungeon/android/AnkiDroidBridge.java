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
import com.cramdungeon.AnkiPrefs;
import com.cramdungeon.CardTts;
import com.cramdungeon.KanjiSetup;
import com.cramdungeon.KanjiStrokes;
import com.cramdungeon.CardMedia;
import com.cramdungeon.CardOutcome;
import com.cramdungeon.CardRequest;
import com.cramdungeon.Txt;
import com.watabou.noosa.Game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
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
import java.util.regex.Matcher;
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

    // AnkiDroid hands audio over as [sound:file] and TTS as bare [anki:play:a:3] references
    private static final Pattern SOUND_TAG = Pattern.compile("\\[sound:([^\\]]+)\\]");
    private static final Pattern PLAY_TAG = Pattern.compile("\\[anki:play[^\\]]*\\]");
    private static final Pattern SRC_ATTR = Pattern.compile("\\bsrc\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
            Pattern.CASE_INSENSITIVE);

    private static final int PERMISSION_REQUEST = 0xA4C1;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Activity activity;
    private Consumer<Boolean> permissionCallback;

    private final CardMedia media;

    public AnkiDroidBridge(Activity activity) {
        this.activity = activity;
        this.media = MediaImportActivity.store(activity);
    }

    // new cards the player asked to learn once the due ones ran out (noteId, ord)
    private final ArrayDeque<long[]> extraNew = new ArrayDeque<>();
    private final Map<Long, NoteType> noteTypes = new HashMap<>();

    /** What rendering needs from a note type, cached per id. */
    private static class NoteType {
        String css = "";
        String[] fieldNames = new String[0];
        boolean cloze;
        // ord -> {question format, answer format}; loaded on first use (TTS only)
        Map<Integer, String[]> templates;
    }
    private String lastCardKey = "";
    private String template;

    private static class CardRef {
        final long noteId;
        final int ord;
        // never studied; filled in when the card is rendered
        boolean isNew;
        // thinking time for this card: drawing cards get their own
        long limitMs;

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
            post(() -> onDone.accept(CardOutcome.answered(ease, thinkMs, timedOut, graded.isNew, graded.limitMs)));
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
                    onUiThread(() -> CardOverlay.replace(page, 0, next.limitMs));
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
                    onUiThread(() -> CardOverlay.replace(page, back.elapsedMs, back.card.limitMs));
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
            final long limit = session.current.limitMs;
            onUiThread(() -> CardOverlay.show(activity, media, page, limit, session));
        });
    }

    @Override
    public void learnNewCards(long deckId, String deckName, int count, Consumer<Integer> onDone) {
        io.execute(() -> {
            int found = 0;
            try {
                for (long[] card : nextNewCards(deckName, count)) {
                    extraNew.add(card);
                    found++;
                }
            } catch (Exception e) {
                Log.e(TAG, "looking up new cards failed", e);
            }
            final int result = found;
            post(() -> onDone.accept(result));
        });
    }

    // positions in Anki's new queue are searched in growing windows until enough cards turn up
    private static final int FIRST_POSITION_WINDOW = 64;
    private static final int MAX_POSITION_WINDOW = 1 << 22;

    /**
     * The deck's next new cards in Anki's own order (new-queue position), skipping
     * suspended and buried ones, as {noteId, ord}.
     */
    private List<long[]> nextNewCards(String deckName, int count) {
        String base = "deck:\"" + deckName.replace("\"", "\\\"") + "\" is:new -is:suspended -is:buried";
        List<long[]> candidates = new ArrayList<>(); // {noteId, ord, position}
        for (int window = FIRST_POSITION_WINDOW; ; window *= 2) {
            boolean last = window >= MAX_POSITION_WINDOW;
            candidates.clear();
            String search = last ? base : base + " prop:pos<=" + window;
            try (Cursor notes = resolver().query(NOTES, new String[]{"_id"}, search, null, null)) {
                while (notes != null && notes.moveToNext()) {
                    addNewCards(notes.getLong(0), last ? Long.MAX_VALUE : window, candidates);
                }
            } catch (RuntimeException e) {
                if (last) {
                    throw e;
                }
                // an AnkiDroid too old for prop:pos: search the whole deck at once
                window = MAX_POSITION_WINDOW / 2;
                continue;
            }
            if (candidates.size() >= count || last) {
                break;
            }
        }
        Collections.sort(candidates, (x, y) -> Long.compare(x[2], y[2]));
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < candidates.size() && i < count; i++) {
            out.add(new long[]{candidates.get(i)[0], candidates.get(i)[1]});
        }
        return out;
    }

    private void addNewCards(long noteId, long maxPosition, List<long[]> into) {
        Uri cards = Uri.withAppendedPath(Uri.withAppendedPath(NOTES, Long.toString(noteId)), "cards");
        try (Cursor c = resolver().query(cards, new String[]{"ord", "type", "queue", "due"}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                // type 0: never studied; queue 0: in the new queue (not suspended or buried)
                if (c.getInt(1) == 0 && c.getInt(2) == 0 && c.getLong(3) <= maxPosition) {
                    into.add(new long[]{noteId, c.getInt(0), c.getLong(3)});
                }
            }
        } catch (RuntimeException e) {
            // older AnkiDroid without queue/due columns: the search already left out suspended and buried
            try (Cursor c = resolver().query(cards, new String[]{"ord", "type"}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (c.getInt(1) == 0) {
                        into.add(new long[]{noteId, c.getInt(0), noteId});
                    }
                }
            }
        }
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
    public String mediaSummary() {
        int count = media.count();
        if (count == 0) {
            return Txt.get("media.none");
        }
        return Txt.get("media.count", count, MediaImportActivity.megabytes(media.bytes()));
    }

    @Override
    public void importMedia() {
        onUiThread(() -> MediaImportActivity.pick(activity));
    }

    @Override
    public void clearMedia(Runnable onDone) {
        io.execute(() -> {
            media.clear();
            post(onDone);
        });
    }

    // kanji decks: adding or suspending thousands of cards must not hold up card checks
    private final ExecutorService slow = Executors.newSingleThreadExecutor();
    private KanjiDecks kanjiDecks;

    private KanjiDecks kanjiDecks() {
        if (kanjiDecks == null) {
            kanjiDecks = new KanjiDecks(activity);
        }
        return kanjiDecks;
    }

    @Override
    public void findKanjiDeck(KanjiSetup.Mode mode, Consumer<AnkiDeck> onDone) {
        slow.execute(() -> {
            AnkiDeck deck = null;
            try {
                deck = mode == KanjiSetup.Mode.HEISIG ? kanjiDecks().findHeisig() : kanjiDecks().findWriting();
            } catch (Exception e) {
                Log.e(TAG, "looking for the kanji deck failed", e);
            }
            final AnkiDeck found = deck;
            post(() -> onDone.accept(found));
        });
    }

    @Override
    public void suspendKanjiRecognition(Consumer<Integer> progress, Consumer<Integer> onDone) {
        slow.execute(() -> {
            int done = 0;
            try {
                done = kanjiDecks().suspendHeisigRecognition(n -> post(() -> progress.accept(n)));
            } catch (Exception e) {
                Log.e(TAG, "suspending recognition cards failed", e);
            }
            final int total = done;
            io.execute(() -> {
                queue.clear();
                answered.clear();
            });
            post(() -> onDone.accept(total));
        });
    }

    @Override
    public void addKanjiWritingDeck(Consumer<Integer> progress, Consumer<AnkiDeck> onDone) {
        slow.execute(() -> {
            AnkiDeck deck = null;
            try {
                deck = kanjiDecks().addWriting(n -> post(() -> progress.accept(n)));
            } catch (Exception e) {
                Log.e(TAG, "adding the kanji writing deck failed", e);
            }
            final AnkiDeck added = deck;
            post(() -> onDone.accept(added));
        });
    }

    @Override
    public void openUrl(String url) {
        onUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "no browser for " + url, e);
            }
        });
    }

    @Override
    public void deckChanged() {
        io.execute(() -> {
            extraNew.clear();
            queue.clear();
            answered.clear();
            lastCardKey = "";
        });
    }

    // --- provider access (io thread only)

    // How many cards of AnkiDroid's queue are taken in one go. The provider rebuilds the
    // queue on every request, and a rebuilt queue never starts with a new card: Anki spreads
    // new cards evenly through the reviews. Asking for just the head every time would only
    // ever show reviews, so the game works through a long stretch of the queue in order.
    private static final int QUEUE_BATCH = 100;
    private final ArrayDeque<CardRef> queue = new ArrayDeque<>();
    // cards answered from the current batch; they can come back in a refetched one
    private final Set<String> answered = new HashSet<>();

    /** Next card for this check (never one in {@code exclude}): Anki's order, else a learned-new one. */
    private CardRef nextCard(long deckId, Set<String> exclude) {
        // a learning card that is due right now sits at the head of a fresh queue: it goes first
        CardRef head = null;
        CardRef repeat = null; // the card just answered, if nothing else is left
        for (CardRef card : fetchQueue(deckId, exclude.size() + 3)) {
            if (exclude.contains(card.key())) {
                continue;
            }
            if (!card.key().equals(lastCardKey)) {
                head = card;
                break;
            }
            repeat = card;
        }
        if (head != null && cardQueue(head) == QUEUE_LEARNING) {
            return head;
        }

        CardRef next = fromBatch(deckId, exclude, true);
        if (next == null) {
            next = fromBatch(deckId, exclude, false);
        }
        if (next == null) {
            next = head != null ? head : repeat;
        }
        if (next != null) {
            return next;
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

    private static final int QUEUE_LEARNING = 1;
    private static final int QUEUE_UNKNOWN = -100;

    /** The next card of the batch that is still waiting; {@code useCurrent} false fetches a new batch first. */
    private CardRef fromBatch(long deckId, Set<String> exclude, boolean useCurrent) {
        if (!useCurrent) {
            queue.clear();
            answered.clear();
            queue.addAll(fetchQueue(deckId, QUEUE_BATCH));
        }
        CardRef card;
        while ((card = queue.poll()) != null) {
            if (exclude.contains(card.key()) || answered.contains(card.key())) {
                continue;
            }
            int q = cardQueue(card);
            // buried or suspended meanwhile; learning cards go first by themselves once they are due
            if (q == QUEUE_LEARNING || (q < 0 && q != QUEUE_UNKNOWN)) {
                continue;
            }
            return card;
        }
        return null;
    }

    private List<CardRef> fetchQueue(long deckId, int limit) {
        List<CardRef> cards = new ArrayList<>();
        try (Cursor c = resolver().query(SCHEDULE, null, "limit=?,deckID=?",
                new String[]{Integer.toString(limit), Long.toString(deckId)}, null)) {
            while (c != null && c.moveToNext()) {
                cards.add(new CardRef(c.getLong(c.getColumnIndexOrThrow("note_id")),
                        c.getInt(c.getColumnIndexOrThrow("ord"))));
            }
        }
        return cards;
    }

    /** Anki's queue code for the card; QUEUE_UNKNOWN when AnkiDroid doesn't report it. */
    private int cardQueue(CardRef card) {
        Uri cardUri = Uri.withAppendedPath(Uri.withAppendedPath(
                Uri.withAppendedPath(NOTES, Long.toString(card.noteId)), "cards"), Integer.toString(card.ord));
        try (Cursor c = resolver().query(cardUri, new String[]{"queue"}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getInt(0) : QUEUE_UNKNOWN;
        } catch (Exception e) {
            return QUEUE_UNKNOWN;
        }
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
        card.isNew = isNew(cardUri);

        long modelId = -1;
        String flds = "";
        try (Cursor c = resolver().query(noteUri, new String[]{"mid", "flds"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                modelId = c.getLong(0);
                flds = c.getString(1);
            }
        }
        NoteType type = noteType(modelId);
        Map<String, String> fields = new HashMap<>();
        String[] values = (flds == null ? "" : flds).split("\u001f", -1);
        for (int i = 0; i < type.fieldNames.length && i < values.length; i++) {
            fields.put(type.fieldNames[i], values[i]);
        }

        List<CardTts.Speech> questionTts = CardTts.none();
        List<CardTts.Speech> answerTts = CardTts.none();
        boolean hasTts = PLAY_TAG.matcher(question).find() || PLAY_TAG.matcher(answer).find();
        if (hasTts && AnkiPrefs.tts() && modelId != -1) {
            try {
                String[] tmpl = template(modelId, type, type.cloze ? 0 : card.ord);
                int clozeOrd = type.cloze ? card.ord + 1 : 0;
                questionTts = CardTts.side(tmpl[0], fields, CardTts.none(), clozeOrd, true);
                answerTts = CardTts.side(tmpl[1], fields, questionTts, clozeOrd, false);
            } catch (Exception e) {
                Log.w(TAG, "working out TTS for card " + card.key() + " failed", e);
            }
        }

        List<String> strokes = AnkiPrefs.drawKanji() ? KanjiStrokes.find(fields) : null;
        if (strokes != null && !KanjiStrokes.isWritingCard(cardName(cardUri))) {
            strokes = null;
        }
        card.limitMs = (strokes != null ? AnkiPrefs.drawTimeSeconds() : AnkiPrefs.thinkTimeSeconds()) * 1000L;

        JSONObject config = new JSONObject();
        config.put("title", request.title);
        config.put("thinkMs", card.limitMs);
        config.put("simple", request.simpleGrading);
        config.put("startElapsedMs", startElapsedMs);
        config.put("canUndo", canUndo);
        config.put("missingMedia", missingMedia(question, answer));
        config.put("newCard", card.isNew);
        config.put("text", cardTexts());
        if (strokes != null) {
            config.put("draw", new JSONObject().put("strokes", new JSONArray(strokes)));
        }

        return template()
                .replace("%%CONFIG%%", config.toString())
                .replace("%%CSS%%", type.css)
                .replace("%%QUESTION%%", clean(question, questionTts))
                .replace("%%ANSWER%%", clean(answer, answerTts));
    }

    private static final String[] CARD_TEXTS = {
            "back", "bury", "suspend", "new_card", "honesty", "show_answer", "again", "again_sub", "hard",
            "good", "good_sub", "easy", "times_up", "continue", "continue_sub", "no_other", "image_missing",
            "media_missing", "check", "draw_correct", "draw_wrong", "disagree", "disagree_sub", "draw_accepted",
            "counts_good"};

    /** The card screen's own strings in the game's language. */
    private static JSONObject cardTexts() throws org.json.JSONException {
        JSONObject text = new JSONObject();
        for (String key : CARD_TEXTS) {
            text.put(key, Txt.get("card." + key));
        }
        return text;
    }

    /** The card template's name ("Recall", "Recognition", ...), or "" if AnkiDroid doesn't say. */
    private String cardName(Uri cardUri) {
        try (Cursor c = resolver().query(cardUri, new String[]{"card_name"}, null, null, null)) {
            return c != null && c.moveToFirst() && c.getString(0) != null ? c.getString(0) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Whether the card was never studied. Older AnkiDroid versions don't report it: then not new. */
    private boolean isNew(Uri cardUri) {
        try (Cursor c = resolver().query(cardUri, new String[]{"type"}, null, null, null)) {
            return c != null && c.moveToFirst() && c.getInt(0) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private NoteType noteType(long modelId) {
        NoteType type = noteTypes.get(modelId);
        if (type != null) {
            return type;
        }
        type = new NoteType();
        if (modelId != -1) {
            Uri modelUri = Uri.withAppendedPath(MODELS, Long.toString(modelId));
            try (Cursor c = resolver().query(modelUri, new String[]{"css", "field_names", "type"},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    type.css = c.getString(0) != null ? c.getString(0) : "";
                    type.fieldNames = c.getString(1) != null ? c.getString(1).split("\u001f", -1) : new String[0];
                    type.cloze = c.getInt(2) == 1;
                }
            } catch (Exception e) {
                Log.w(TAG, "reading note type " + modelId + " failed", e);
            }
        }
        noteTypes.put(modelId, type);
        return type;
    }

    /** {question format, answer format} of a card template. */
    private String[] template(long modelId, NoteType type, int ord) {
        if (type.templates == null) {
            type.templates = new HashMap<>();
            Uri uri = Uri.withAppendedPath(Uri.withAppendedPath(MODELS, Long.toString(modelId)), "templates");
            try (Cursor c = resolver().query(uri, new String[]{"ord", "question_format", "answer_format"},
                    null, null, null)) {
                while (c != null && c.moveToNext()) {
                    type.templates.put(c.getInt(0), new String[]{c.getString(1), c.getString(2)});
                }
            }
        }
        String[] t = type.templates.get(ord);
        return t != null ? t : new String[]{"", ""};
    }

    /**
     * Sound tags become play buttons (the page autoplays them). TTS references are
     * numbered in the same order as {@code tts}; they become buttons too, or go if
     * TTS is off or couldn't be worked out.
     */
    private static String clean(String html, List<CardTts.Speech> tts) {
        if (html == null) {
            return "";
        }
        Matcher play = PLAY_TAG.matcher(html);
        StringBuffer spoken = new StringBuffer();
        int k = 0;
        while (play.find()) {
            String button = "";
            if (k < tts.size()) {
                CardTts.Speech sp = tts.get(k);
                button = "<button class=\"snd\" data-tts=\"" + escapeAttr(sp.text) + "\" data-lang=\""
                        + escapeAttr(sp.lang) + "\" data-rate=\"" + sp.rate + "\" title=\""
                        + escapeAttr(sp.text) + "\">\u25B6</button>";
            }
            k++;
            play.appendReplacement(spoken, Matcher.quoteReplacement(button));
        }
        play.appendTail(spoken);
        html = spoken.toString();
        Matcher m = SOUND_TAG.matcher(html);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String name = escapeAttr(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(
                    "<button class=\"snd\" data-snd=\"" + name + "\" title=\"" + name + "\">\u25B6</button>"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescapeAttr(String s) {
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /** How many local media files the card refers to that haven't been imported. */
    private int missingMedia(String question, String answer) {
        Set<String> names = new HashSet<>();
        for (String html : new String[]{question, answer}) {
            if (html == null) {
                continue;
            }
            Matcher sound = SOUND_TAG.matcher(html);
            while (sound.find()) {
                names.add(sound.group(1));
            }
            Matcher src = SRC_ATTR.matcher(html);
            while (src.find()) {
                String value = unescapeAttr(src.group(1) != null ? src.group(1) : src.group(2)).trim();
                // remote images and inline data aren't card media
                if (!value.isEmpty() && !value.contains("/") && !value.contains(":")) {
                    names.add(value);
                }
            }
        }
        int missing = 0;
        for (String name : names) {
            if (media.file(name) == null && media.file(urlDecode(name)) == null) {
                missing++;
            }
        }
        return missing;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
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
        answered.add(card.key());
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
