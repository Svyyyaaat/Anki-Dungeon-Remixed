package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;

/** Cooldown for passive-search cards so walking around doesn't turn into a quiz. */
public final class AnkiSearch {

    private static final float PASSIVE_COOLDOWN = 15f;
    private static float lastPassiveCheck = -1000f;

    private AnkiSearch() {
    }

    public static boolean passiveReady() {
        return Actor.now() - lastPassiveCheck >= PASSIVE_COOLDOWN;
    }

    public static void passiveUsed() {
        lastPassiveCheck = Actor.now();
    }

    /** Extra active-search radius for a passed card: 2 when slow, 3 when fast. */
    public static int bonusRadius(CheckResult result) {
        if (result.skipped || !result.correct) {
            return 0;
        }
        return result.fast() ? 3 : 2;
    }
}
