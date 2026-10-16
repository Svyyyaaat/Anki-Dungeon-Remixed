package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;

/**
 * Combat checks: every exchange with the hero asks a card (or spends momentum,
 * see {@link AnkiMomentum}), and the answer scales the exchange.
 */
public final class AnkiCombat {

    // modifiers for the single exchange being resolved right now
    private static Char modAttacker;
    private static Char modDefender;
    private static float modPerformance;

    private AnkiCombat() {
    }

    private static boolean heroAttacks(Char attacker, Char defender) {
        Hero hero = Dungeon.hero;
        return attacker == hero && defender != null && defender != hero
                && defender.alignment != Char.Alignment.ALLY;
    }

    private static boolean heroDefends(Char attacker, Char defender) {
        Hero hero = Dungeon.hero;
        return defender == hero && attacker != null && attacker != hero
                && attacker.alignment == Char.Alignment.ENEMY;
    }

    public static boolean shouldCheck(Char attacker, Char defender) {
        if (Dungeon.hero == null) {
            return false;
        }
        if (heroAttacks(attacker, defender)) {
            return AnkiCheck.active(CheckKind.ATTACK);
        }
        if (heroDefends(attacker, defender)) {
            return AnkiCheck.active(CheckKind.DEFENSE);
        }
        return false;
    }

    /** Resolves an exchange after a card (or banked momentum); {@code resolve} performs the actual attack. */
    public static void check(Char attacker, Char defender, Runnable resolve) {
        final boolean attacking = heroAttacks(attacker, defender);
        final Hero hero = Dungeon.hero;

        AnkiCheck.request(attacking ? CheckKind.ATTACK : CheckKind.DEFENSE, result -> {
            if (result.skipped) {
                resolve.run();
                return;
            }
            if (!result.momentum) {
                if (!result.correct) {
                    hero.sprite.showStatus(CharSprite.NEGATIVE, attacking ? "Fumble!" : "Exposed!");
                } else if (result.fast()) {
                    hero.sprite.showStatus(CharSprite.POSITIVE, attacking ? "Precise!" : "Ready!");
                }
            }
            resolveWith(attacker, defender, result.performance, resolve);
        });
    }

    private static void resolveWith(Char attacker, Char defender, float performance, Runnable resolve) {
        modAttacker = attacker;
        modDefender = defender;
        modPerformance = performance;
        try {
            resolve.run();
        } finally {
            modAttacker = null;
            modDefender = null;
        }
    }

    private static boolean modsApply(Char attacker, Char defender) {
        return attacker != null && attacker == modAttacker && defender == modDefender;
    }

    /** Multiplier for the attacker's accuracy roll. */
    public static float accuracy(Char attacker, Char defender) {
        if (modsApply(attacker, defender) && attacker == Dungeon.hero) {
            return 0.5f + modPerformance;
        }
        return 1f;
    }

    /** Multiplier for the defender's evasion roll. */
    public static float evasion(Char defender, Char attacker) {
        if (modsApply(attacker, defender) && defender == Dungeon.hero) {
            return 0.5f + modPerformance;
        }
        return 1f;
    }

    /**
     * Damage taken by {@code victim} while an exchange is being resolved. Spells hit
     * with a bolt object as the damage source, so this keys off the exchange's
     * defender rather than the source.
     */
    public static int damageTaken(Char victim, int dmg) {
        if (modAttacker == null || victim != modDefender) {
            return dmg;
        }
        if (victim == Dungeon.hero) {
            return Math.round(dmg * (1.25f - 0.5f * modPerformance));
        }
        if (modAttacker == Dungeon.hero) {
            return Math.round(dmg * (0.75f + 0.5f * modPerformance));
        }
        return dmg;
    }

    /** Ranged attacks (spells, beams, wand bolts): {@code land} resolves the shot. */
    public static void ranged(Char attacker, Char target, Runnable land) {
        if (target != null && shouldCheck(attacker, target)) {
            check(attacker, target, land);
        } else {
            land.run();
        }
    }
}
