package com.ankidungeon;

import com.watabou.utils.GameSettings;

public final class AnkiPrefs {
    public static final String ENABLED = "anki_enabled";
    public static final String DECK_ID = "anki_deck_id";
    public static final String DECK_NAME = "anki_deck_name";
    public static final String THINK_TIME = "anki_think_time";
    public static final String SIMPLE_GRADING = "anki_simple_grading";

    public static final String TRIGGER_COMBAT = "anki_trigger_combat";
    public static final String TRIGGER_ITEMS = "anki_trigger_items";
    public static final String TRIGGER_PICKUP = "anki_trigger_pickup";
    public static final String TRIGGER_SNEAK = "anki_trigger_sneak";
    public static final String TRIGGER_SEARCH = "anki_trigger_search";

    public static final int MIN_THINK_TIME = 5;
    public static final int MAX_THINK_TIME = 60;
    public static final int DEFAULT_THINK_TIME = 20;

    private AnkiPrefs() {
    }

    public static boolean enabled() {
        return GameSettings.getBoolean(ENABLED, true);
    }

    public static void enabled(boolean value) {
        GameSettings.put(ENABLED, value);
    }

    public static long deckId() {
        return GameSettings.getLong(DECK_ID, -1);
    }

    public static String deckName() {
        return GameSettings.getString(DECK_NAME, "");
    }

    public static void deck(long id, String name) {
        GameSettings.put(DECK_ID, id);
        GameSettings.put(DECK_NAME, name);
    }

    public static boolean hasDeck() {
        return deckId() != -1;
    }

    public static int thinkTimeSeconds() {
        return GameSettings.getInt(THINK_TIME, DEFAULT_THINK_TIME, MIN_THINK_TIME, MAX_THINK_TIME);
    }

    public static void thinkTimeSeconds(int value) {
        GameSettings.put(THINK_TIME, value);
    }

    public static boolean simpleGrading() {
        return GameSettings.getBoolean(SIMPLE_GRADING, true);
    }

    public static void simpleGrading(boolean value) {
        GameSettings.put(SIMPLE_GRADING, value);
    }

    public static boolean trigger(String key) {
        return GameSettings.getBoolean(key, true);
    }

    public static void trigger(String key, boolean value) {
        GameSettings.put(key, value);
    }
}
