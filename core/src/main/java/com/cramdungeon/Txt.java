package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;

/** Cram Dungeon's own text, from messages/cram/cram*.properties in the game's language. */
public final class Txt {

    private Txt() {
    }

    public static String get(String key, Object... args) {
        return Messages.get("cram." + key, args);
    }
}
