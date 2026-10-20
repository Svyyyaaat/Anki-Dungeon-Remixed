package com.cramdungeon;

public class CardOutcome {
    public static final int AGAIN = 1;
    public static final int HARD = 2;
    public static final int GOOD = 3;
    public static final int EASY = 4;

    public enum Kind {
        ANSWERED,
        NO_CARDS,
        // buried or suspended with nothing else due: the check plays without a card
        SKIPPED,
        ERROR
    }

    public final Kind kind;
    public final int ease;
    public final long thinkTimeMs;
    public final boolean timedOut;
    /** The card had never been studied before this answer. */
    public final boolean newCard;
    /** The card's own time limit (drawing cards have a longer one), or 0 for the usual one. */
    public final long limitMs;

    private CardOutcome(Kind kind, int ease, long thinkTimeMs, boolean timedOut, boolean newCard, long limitMs) {
        this.kind = kind;
        this.ease = ease;
        this.thinkTimeMs = thinkTimeMs;
        this.timedOut = timedOut;
        this.newCard = newCard;
        this.limitMs = limitMs;
    }

    public static CardOutcome answered(int ease, long thinkTimeMs, boolean timedOut) {
        return answered(ease, thinkTimeMs, timedOut, false, 0);
    }

    public static CardOutcome answered(int ease, long thinkTimeMs, boolean timedOut, boolean newCard) {
        return answered(ease, thinkTimeMs, timedOut, newCard, 0);
    }

    public static CardOutcome answered(int ease, long thinkTimeMs, boolean timedOut, boolean newCard, long limitMs) {
        return new CardOutcome(Kind.ANSWERED, ease, thinkTimeMs, timedOut, newCard, limitMs);
    }

    public static CardOutcome noCards() {
        return new CardOutcome(Kind.NO_CARDS, 0, 0, false, false, 0);
    }

    public static CardOutcome skipped() {
        return new CardOutcome(Kind.SKIPPED, 0, 0, false, false, 0);
    }

    public static CardOutcome error() {
        return new CardOutcome(Kind.ERROR, 0, 0, false, false, 0);
    }
}
