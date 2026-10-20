package com.cramdungeon;

public enum CheckKind {
    ATTACK("attack", AnkiPrefs.TRIGGER_COMBAT),
    DEFENSE("defense", AnkiPrefs.TRIGGER_COMBAT),
    POTION("potion", AnkiPrefs.TRIGGER_ITEMS),
    SCROLL("scroll", AnkiPrefs.TRIGGER_ITEMS),
    PICKUP("pickup", AnkiPrefs.TRIGGER_PICKUP),
    SNEAK("sneak", AnkiPrefs.TRIGGER_SNEAK),
    SEARCH("search", AnkiPrefs.TRIGGER_SEARCH);

    private final String textKey;
    public final String triggerKey;

    CheckKind(String textKey, String triggerKey) {
        this.textKey = textKey;
        this.triggerKey = triggerKey;
    }

    /** Heading of the card window. */
    public String title() {
        return Txt.get("check." + textKey);
    }
}
