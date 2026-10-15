package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sneaking past sleeping mobs. Each turn the hero could wake a sleeping mob, the
 * vanilla wake-up chance is added to that mob's tension instead of rolled; when
 * it fills up the hero gets a card. A pass keeps the mob asleep, a miss wakes it.
 */
public final class AnkiStealth {

    private static final Map<Char, Float> tension = new WeakHashMap<>();

    private AnkiStealth() {
    }

    public static boolean active(Char mob, Char noticer) {
        return noticer == Dungeon.hero && mob.alignment == Char.Alignment.ENEMY
                && AnkiCheck.active(CheckKind.SNEAK);
    }

    /** Adds this turn's wake-up chance; true when the meter is full and a card is due. */
    public static synchronized boolean addTension(Char mob, float chance) {
        float value = tension.getOrDefault(mob, 0f) + chance;
        tension.put(mob, value);
        return value >= 1f;
    }

    /** Applies a sneak card's result; true if the mob wakes up. */
    public static boolean resolve(Char mob, CheckResult result) {
        Hero hero = Dungeon.hero;
        synchronized (AnkiStealth.class) {
            if (result.skipped) {
                tension.put(mob, 0f);
                return false;
            }
            // a pass gives a little grace before this mob asks again
            tension.put(mob, result.correct ? -1f : 0f);
        }
        hero.sprite.showStatus(result.correct ? CharSprite.POSITIVE : CharSprite.NEGATIVE,
                result.correct ? "Unnoticed" : "Spotted!");
        return !result.correct;
    }
}
