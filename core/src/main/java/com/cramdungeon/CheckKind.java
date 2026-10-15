package com.cramdungeon;

public enum CheckKind {
    ATTACK("Strike!", AnkiPrefs.TRIGGER_COMBAT),
    DEFENSE("Defend!", AnkiPrefs.TRIGGER_COMBAT),
    POTION("An unknown potion", AnkiPrefs.TRIGGER_ITEMS),
    SCROLL("An unknown scroll", AnkiPrefs.TRIGGER_ITEMS),
    PICKUP("You look it over", AnkiPrefs.TRIGGER_PICKUP),
    SNEAK("Sneaking past", AnkiPrefs.TRIGGER_SNEAK),
    SEARCH("Searching", AnkiPrefs.TRIGGER_SEARCH);

    public final String title;
    public final String triggerKey;

    CheckKind(String title, String triggerKey) {
        this.title = title;
        this.triggerKey = triggerKey;
    }
}
