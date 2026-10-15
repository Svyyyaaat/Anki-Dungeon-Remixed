package com.ankidungeon;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;

/**
 * Combat checks with momentum: every exchange with the hero asks a card, but a
 * streak of fast correct answers banks free exchanges played at that speed.
 */
public final class AnkiCombat {

    private static final int MAX_CHARGES = 3;
    private static final float MOMENTUM_TIMEOUT = 10f;
    private static final float CHARGE_THRESHOLD = 0.6f;

    private static int streak;
    private static int charges;
    private static float chargePerformance;
    private static float lastCheckTime = -1000f;

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

        if (Actor.now() - lastCheckTime > MOMENTUM_TIMEOUT) {
            streak = 0;
            charges = 0;
        }
        lastCheckTime = Actor.now();

        if (charges > 0) {
            charges--;
            hero.sprite.showStatus(CharSprite.POSITIVE, "Momentum!");
            resolveWith(attacker, defender, chargePerformance, resolve);
            return;
        }

        AnkiCheck.request(attacking ? CheckKind.ATTACK : CheckKind.DEFENSE, result -> {
            lastCheckTime = Actor.now();
            if (result.skipped) {
                resolve.run();
                return;
            }
            if (result.correct) {
                streak++;
                if (result.performance >= CHARGE_THRESHOLD) {
                    charges = Math.min(streak - 1, MAX_CHARGES);
                    chargePerformance = result.performance;
                }
                if (result.fast()) {
                    hero.sprite.showStatus(CharSprite.POSITIVE, attacking ? "Precise!" : "Ready!");
                }
            } else {
                streak = 0;
                charges = 0;
                hero.sprite.showStatus(CharSprite.NEGATIVE, attacking ? "Fumble!" : "Exposed!");
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
