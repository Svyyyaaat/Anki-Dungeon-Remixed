package com.cramdungeon;

/**
 * What a flashcard check means for the game.
 * {@link #skipped} checks (feature off, no cards, ...) should fall back to vanilla behaviour.
 * {@link #performance} is 0 for a failed card and 0.5..1 for a passed one (faster is higher).
 */
public class CheckResult {
    public static final CheckResult SKIPPED = new CheckResult(true, false, 0.5f);

    public final boolean skipped;
    public final boolean correct;
    public final float performance;
    /** Played from banked momentum instead of a card. */
    public final boolean momentum;

    public CheckResult(boolean skipped, boolean correct, float performance) {
        this(skipped, correct, performance, false);
    }

    private CheckResult(boolean skipped, boolean correct, float performance, boolean momentum) {
        this.skipped = skipped;
        this.correct = correct;
        this.performance = performance;
        this.momentum = momentum;
    }

    static CheckResult momentum(float performance) {
        return new CheckResult(false, true, performance, true);
    }

    public static CheckResult from(CardOutcome outcome, long thinkTimeLimitMs) {
        if (outcome.kind != CardOutcome.Kind.ANSWERED) {
            return SKIPPED;
        }
        if (outcome.ease <= CardOutcome.AGAIN || outcome.timedOut) {
            // nobody can know a card they've never seen: missing one plays out as if no card was asked
            return outcome.newCard ? SKIPPED : new CheckResult(false, false, 0f);
        }
        float used = Math.min(1f, outcome.thinkTimeMs / (float) Math.max(1, thinkTimeLimitMs));
        float p = 1f - 0.5f * used;
        if (outcome.ease == CardOutcome.HARD) {
            p *= 0.8f;
        } else if (outcome.ease == CardOutcome.EASY) {
            p = Math.min(1f, p + 0.1f);
        }
        return new CheckResult(false, true, p);
    }

    public boolean fast() {
        return correct && performance >= 0.75f;
    }
}
