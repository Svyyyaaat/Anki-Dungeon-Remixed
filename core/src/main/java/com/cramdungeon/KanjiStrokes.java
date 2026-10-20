package com.cramdungeon;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kanji drawing cards. A note that carries its kanji's strokes (a field named like
 * "Stroke Data" holding a JSON list of KanjiVG SVG paths, as in the "All in One Kanji"
 * decks) can be answered by drawing; the game does that on its writing (recall) cards.
 */
public final class KanjiStrokes {

    private static final Pattern WRITING_CARD = Pattern.compile(
            "recall|writ|produc|draw|output|kaku|書", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private KanjiStrokes() {
    }

    /** Whether a card type's name says it asks you to produce the kanji. */
    public static boolean isWritingCard(String cardName) {
        return cardName != null && WRITING_CARD.matcher(cardName).find();
    }

    /** The note's stroke paths in order, or null if it has none. */
    public static List<String> find(Map<String, String> fields) {
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!e.getKey().toLowerCase(Locale.ENGLISH).contains("stroke")) {
                continue;
            }
            List<String> strokes = parse(e.getValue());
            if (strokes != null) {
                return strokes;
            }
        }
        return null;
    }

    static List<String> parse(String value) {
        if (value == null) {
            return null;
        }
        String text = TAG.matcher(value).replaceAll("")
                .replace("&quot;", "\"").replace("&nbsp;", " ").replace("&amp;", "&").trim();
        if (!text.startsWith("[")) {
            return null;
        }
        JsonValue root;
        try {
            root = new JsonReader().parse(text);
        } catch (RuntimeException ex) {
            return null;
        }
        if (root == null || !root.isArray() || root.size == 0) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonValue v = root.child; v != null; v = v.next) {
            if (!v.isString()) {
                return null;
            }
            String d = v.asString().trim();
            if (!d.startsWith("M") && !d.startsWith("m")) {
                return null;
            }
            out.add(d);
        }
        return out;
    }
}
