package com.cramdungeon.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.cramdungeon.AnkiDeck;
import com.cramdungeon.KanjiStrokes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.zip.GZIPInputStream;

/**
 * The two kanji decks, through AnkiDroid's provider (io thread only):
 * <ul>
 * <li>"Learning kanji": the All in One Kanji (Heisig) deck the player gets from
 * AnkiWeb. Found by its note type; its recognition cards can be suspended so only
 * writing cards come up.</li>
 * <li>"Reading, not writing": the game's own writing deck, built from open data
 * (KANJIDIC2, KanjiVG, JMdict) and added to AnkiDroid as a normal deck.</li>
 * </ul>
 */
final class KanjiDecks {

    private static final String TAG = "CramDungeon";

    static final String HEISIG_URL = "https://ankiweb.net/shared/info/1862058740";

    static final String WRITING_DECK = "Cram Dungeon Kanji – Writing";
    static final String WRITING_MODEL = "Cram Dungeon Kanji (writing)";
    private static final String[] WRITING_FIELDS = {
            "Kanji", "Meaning", "Onyomi", "Kunyomi", "Words", "Stroke Data", "Grade", "Frequency"};
    private static final String WRITING_DESC = "The 2,136 Jouyou kanji, one writing card each: an everyday word "
            + "with the kanji blanked out, to write from memory. Made by Cram Dungeon from KANJIDIC2 and JMdict "
            + "(EDRDG, CC BY-SA) and KanjiVG (Ulrich Apel, CC BY-SA 3.0).";

    private static final String SEP = "\u001f";

    private static final Uri BASE = Uri.parse("content://" + AnkiDroidBridge.AUTHORITY);
    private static final Uri NOTES = Uri.withAppendedPath(BASE, "notes");
    private static final Uri MODELS = Uri.withAppendedPath(BASE, "models");
    private static final Uri DECKS = Uri.withAppendedPath(BASE, "decks");
    private static final Uri SCHEDULE = Uri.withAppendedPath(BASE, "schedule");

    private static final int NOTE_BATCH = 200;

    private final Context context;

    KanjiDecks(Context context) {
        this.context = context;
    }

    private ContentResolver resolver() {
        return context.getContentResolver();
    }

    // --- finding

    /** The All in One Kanji note type: it has stroke data and Heisig keywords. */
    private long heisigModel() {
        try (Cursor c = resolver().query(MODELS, new String[]{"_id", "name", "field_names"}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                List<String> fields = splitFields(c.getString(2));
                if (!WRITING_MODEL.equals(c.getString(1)) && fields.contains("Stroke Data")
                        && fields.contains("Keyword")) {
                    return c.getLong(0);
                }
            }
        }
        return -1;
    }

    private long writingModel() {
        try (Cursor c = resolver().query(MODELS, new String[]{"_id", "name"}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (WRITING_MODEL.equals(c.getString(1))) {
                    return c.getLong(0);
                }
            }
        }
        return -1;
    }

    /** The deck the note type's cards are in (the deck of its first note), or null. */
    private AnkiDeck deckOfModel(long modelId) {
        if (modelId == -1) {
            return null;
        }
        long noteId = -1;
        try (Cursor c = resolver().query(NOTES, new String[]{"_id"}, "mid:" + modelId, null, null)) {
            if (c != null && c.moveToFirst()) {
                noteId = c.getLong(0);
            }
        }
        if (noteId == -1) {
            return null;
        }
        long deckId = -1;
        Uri cards = Uri.withAppendedPath(Uri.withAppendedPath(NOTES, Long.toString(noteId)), "cards");
        try (Cursor c = resolver().query(cards, new String[]{"deck_id"}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                deckId = c.getLong(0);
            }
        }
        return deckId == -1 ? null : deck(deckId);
    }

    private AnkiDeck deck(long deckId) {
        try (Cursor c = resolver().query(DECKS, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (c.getLong(c.getColumnIndexOrThrow("deck_id")) != deckId) {
                    continue;
                }
                int learn = 0, review = 0, fresh = 0;
                int countsIdx = c.getColumnIndex("deck_count");
                if (countsIdx >= 0 && c.getString(countsIdx) != null) {
                    JSONArray counts = new JSONArray(c.getString(countsIdx));
                    learn = counts.optInt(0);
                    review = counts.optInt(1);
                    fresh = counts.optInt(2);
                }
                return new AnkiDeck(deckId, c.getString(c.getColumnIndexOrThrow("deck_name")), learn, review, fresh);
            }
        } catch (Exception e) {
            Log.w(TAG, "reading deck " + deckId + " failed", e);
        }
        return null;
    }

    AnkiDeck findHeisig() {
        return deckOfModel(heisigModel());
    }

    AnkiDeck findWriting() {
        return deckOfModel(writingModel());
    }

    // --- Heisig: only writing cards

    /** Suspends every recognition (non-writing) card of the Heisig deck; returns how many. */
    int suspendHeisigRecognition(IntConsumer progress) {
        long modelId = heisigModel();
        if (modelId == -1) {
            return 0;
        }
        List<int[]> ords = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Uri templates = Uri.withAppendedPath(Uri.withAppendedPath(MODELS, Long.toString(modelId)), "templates");
        try (Cursor c = resolver().query(templates, new String[]{"ord", "card_template_name"}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (!KanjiStrokes.isWritingCard(c.getString(1))) {
                    ords.add(new int[]{c.getInt(0)});
                    names.add(c.getString(1));
                }
            }
        }
        int done = 0;
        for (int t = 0; t < ords.size(); t++) {
            // only notes whose recognition card isn't suspended yet, so running it again is quick
            String search = "mid:" + modelId + " \"card:" + names.get(t).replace("\"", "") + "\" -is:suspended";
            List<Long> notes = new ArrayList<>();
            try (Cursor c = resolver().query(NOTES, new String[]{"_id"}, search, null, null)) {
                while (c != null && c.moveToNext()) {
                    notes.add(c.getLong(0));
                }
            }
            for (long noteId : notes) {
                ContentValues values = new ContentValues();
                values.put("note_id", noteId);
                values.put("ord", ords.get(t)[0]);
                values.put("suspended", 1);
                try {
                    resolver().update(SCHEDULE, values, null, null);
                    done++;
                } catch (Exception e) {
                    Log.w(TAG, "suspending a recognition card of note " + noteId + " failed", e);
                }
                if (done % 100 == 0) {
                    progress.accept(done);
                }
            }
        }
        return done;
    }

    // --- the game's own writing deck

    /** Creates the writing deck, its note type and every note; returns the deck. */
    AnkiDeck addWriting(IntConsumer progress) throws Exception {
        AnkiDeck existing = findWriting();
        if (existing != null) {
            return existing;
        }
        JSONArray data = new JSONArray(writingData());

        long deckId = createDeck();
        long modelId = writingModel();
        if (modelId == -1) {
            modelId = createModel(deckId);
        }

        Uri notesUri = NOTES.buildUpon().appendQueryParameter("deckId", Long.toString(deckId)).build();
        List<ContentValues> batch = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject k = data.getJSONObject(i);
            String[] fields = {
                    k.getString("k"),
                    k.optString("m"),
                    k.optString("on"),
                    k.optString("kun"),
                    htmlSafe(k.getJSONArray("w").toString()),
                    htmlSafe(k.getJSONArray("s").toString()),
                    Integer.toString(k.optInt("g")),
                    k.optInt("f") > 0 ? Integer.toString(k.optInt("f")) : ""};
            ContentValues values = new ContentValues();
            values.put("mid", modelId);
            values.put("flds", String.join(SEP, fields));
            values.put("tags", "cram_dungeon kanji grade" + k.optInt("g"));
            batch.add(values);
            if (batch.size() == NOTE_BATCH || i == data.length() - 1) {
                resolver().bulkInsert(notesUri, batch.toArray(new ContentValues[0]));
                batch.clear();
                progress.accept(i + 1);
            }
        }
        AnkiDeck deck = deck(deckId);
        return deck != null ? deck : new AnkiDeck(deckId, WRITING_DECK, 0, 0, 0);
    }

    private long createDeck() {
        try (Cursor c = resolver().query(DECKS, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (WRITING_DECK.equals(c.getString(c.getColumnIndexOrThrow("deck_name")))) {
                    return c.getLong(c.getColumnIndexOrThrow("deck_id"));
                }
            }
        }
        ContentValues values = new ContentValues();
        values.put("deck_name", WRITING_DECK);
        values.put("deck_desc", WRITING_DESC);
        Uri uri = resolver().insert(DECKS, values);
        return Long.parseLong(uri.getLastPathSegment());
    }

    private long createModel(long deckId) throws IOException {
        ContentValues values = new ContentValues();
        values.put("name", WRITING_MODEL);
        values.put("field_names", String.join(SEP, WRITING_FIELDS));
        values.put("num_cards", 1);
        values.put("css", readAsset("anki/kanji_writing.css", false));
        values.put("deck_id", deckId);
        values.put("sort_field_index", 0);
        Uri model = resolver().insert(MODELS, values);
        long modelId = Long.parseLong(model.getLastPathSegment());

        ContentValues template = new ContentValues();
        template.put("card_template_name", "Writing");
        template.put("question_format", readAsset("anki/kanji_writing_front.html", false));
        template.put("answer_format", readAsset("anki/kanji_writing_back.html", false));
        resolver().update(Uri.withAppendedPath(Uri.withAppendedPath(model, "templates"), "0"), template, null, null);
        return modelId;
    }

    // --- helpers

    /** The deck data. The build unpacks .gz assets and drops the extension, so try both forms. */
    private String writingData() throws IOException {
        try {
            return readAsset("anki/kanji_writing.json", false);
        } catch (IOException e) {
            return readAsset("anki/kanji_writing.json.gz", true);
        }
    }

    private String readAsset(String name, boolean gzipped) throws IOException {
        try (InputStream raw = context.getAssets().open(name);
             InputStream in = gzipped ? new GZIPInputStream(raw) : raw) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** JSON that can sit in a note field and a {@code <script>} block: no markup characters. */
    private static String htmlSafe(String json) {
        return json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
    }

    private static List<String> splitFields(String names) {
        List<String> out = new ArrayList<>();
        if (names != null) {
            for (String f : names.split(SEP, -1)) {
                out.add(f);
            }
        }
        return out;
    }
}
