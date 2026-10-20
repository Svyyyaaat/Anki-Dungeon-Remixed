package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;

/**
 * Momentum: a streak of good answers banks free checks, played at the speed of
 * the answer that banked them. Every kind of check builds and spends the same
 * bank. How quickly it fills is the card frequency setting; the bank also grows
 * by one per chapter, since deeper floors ask many more checks.
 */
public final class AnkiMomentum {

    public enum Frequency {
        // banks after 2 good answers in a row, 3 checks (+1 per chapter)
        OFTEN(2, 3, 7, 0.6f),
        // banks after any good answer, 5 checks (+1 per chapter)
        NORMAL(1, 5, 9, 0.5f),
        // banks after any passed card, Hard too, 8 checks (+1 per chapter)
        REDUCED(1, 8, 12, 0.4f);

        final int minStreak;
        final int baseCharges;
        final int maxCharges;
        final float threshold;

        Frequency(int minStreak, int baseCharges, int maxCharges, float threshold) {
            this.minStreak = minStreak;
            this.baseCharges = baseCharges;
            this.maxCharges = maxCharges;
            this.threshold = threshold;
        }

        public String label() {
            return Txt.get("freq." + name().toLowerCase(java.util.Locale.ENGLISH));
        }
    }

    private static final int FLOORS_PER_CHAPTER = 5;
    // turns without any check before the bank empties
    private static final float TIMEOUT = 20f;

    private static int streak;
    private static int charges;
    private static float chargePerformance;
    private static float lastCheckTime = -1000f;

    private AnkiMomentum() {
    }

    private static synchronized void expire() {
        if (Actor.now() - lastCheckTime > TIMEOUT) {
            streak = 0;
            charges = 0;
        }
        lastCheckTime = Actor.now();
    }

    /** Spends a banked check if there is one; the result stands in for a card. */
    static CheckResult spend() {
        float performance;
        synchronized (AnkiMomentum.class) {
            expire();
            if (charges <= 0) {
                return null;
            }
            charges--;
            performance = chargePerformance;
        }
        if (Dungeon.hero != null && Dungeon.hero.sprite != null) {
            Dungeon.hero.sprite.showStatus(CharSprite.POSITIVE, Txt.get("status.momentum"));
        }
        return CheckResult.momentum(performance);
    }

    /** Counts an answered card towards the streak. */
    static synchronized void record(CheckResult result) {
        if (result.skipped) {
            return;
        }
        lastCheckTime = Actor.now();
        if (!result.correct) {
            streak = 0;
            charges = 0;
            return;
        }
        streak++;
        Frequency f = AnkiPrefs.frequency();
        if (result.performance >= f.threshold) {
            int banked = charges(f, streak, Dungeon.depth);
            if (banked > 0) {
                charges = banked;
                chargePerformance = result.performance;
            }
        }
    }

    /** Drops the bank, e.g. for a new run or another deck. */
    public static synchronized void reset() {
        streak = 0;
        charges = 0;
        lastCheckTime = -1000f;
    }

    /** Most free checks the bank holds on this floor. */
    static int cap(Frequency f, int depth) {
        int chapter = Math.max(0, depth - 1) / FLOORS_PER_CHAPTER;
        return Math.min(f.maxCharges, f.baseCharges + chapter);
    }

    /**
     * Free checks banked after {@code streak} good answers in a row: none before the
     * streak is long enough, then half the cap, then one more per answer up to the cap.
     */
    static int charges(Frequency f, int streak, int depth) {
        if (streak < f.minStreak) {
            return 0;
        }
        int cap = cap(f, depth);
        return Math.min(cap, cap / 2 + streak - f.minStreak);
    }
}
