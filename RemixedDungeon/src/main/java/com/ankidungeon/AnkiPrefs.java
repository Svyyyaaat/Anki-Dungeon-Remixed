package com.ankidungeon;

import com.nyrds.platform.storage.Preferences;

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
        return Preferences.INSTANCE.getBoolean(ENABLED, true);
    }

    public static void enabled(boolean value) {
        Preferences.INSTANCE.put(ENABLED, value);
    }

    public static long deckId() {
        try {
            return Long.parseLong(Preferences.INSTANCE.getString(DECK_ID, "-1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String deckName() {
        return Preferences.INSTANCE.getString(DECK_NAME, "");
    }

    public static void deck(long id, String name) {
        Preferences.INSTANCE.put(DECK_ID, Long.toString(id));
        Preferences.INSTANCE.put(DECK_NAME, name);
    }

    public static boolean hasDeck() {
        return deckId() != -1;
    }

    public static int thinkTimeSeconds() {
        int value = Preferences.INSTANCE.getInt(THINK_TIME, DEFAULT_THINK_TIME);
        return Math.max(MIN_THINK_TIME, Math.min(MAX_THINK_TIME, value));
    }

    public static void thinkTimeSeconds(int value) {
        Preferences.INSTANCE.put(THINK_TIME, value);
    }

    public static boolean simpleGrading() {
        return Preferences.INSTANCE.getBoolean(SIMPLE_GRADING, true);
    }

    public static void simpleGrading(boolean value) {
        Preferences.INSTANCE.put(SIMPLE_GRADING, value);
    }

    public static boolean trigger(String key) {
        return Preferences.INSTANCE.getBoolean(key, true);
    }

    public static void trigger(String key, boolean value) {
        Preferences.INSTANCE.put(key, value);
    }
}
