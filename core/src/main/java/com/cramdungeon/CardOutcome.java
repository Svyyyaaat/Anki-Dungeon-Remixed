package com.cramdungeon;

public class CardOutcome {
    public static final int AGAIN = 1;
    public static final int HARD = 2;
    public static final int GOOD = 3;
    public static final int EASY = 4;

    public enum Kind {
        ANSWERED,
        NO_CARDS,
        ERROR
    }

    public final Kind kind;
    public final int ease;
    public final long thinkTimeMs;
    public final boolean timedOut;
    /** The card had never been studied before this answer. */
    public final boolean newCard;

    private CardOutcome(Kind kind, int ease, long thinkTimeMs, boolean timedOut, boolean newCard) {
        this.kind = kind;
        this.ease = ease;
        this.thinkTimeMs = thinkTimeMs;
        this.timedOut = timedOut;
        this.newCard = newCard;
    }

    public static CardOutcome answered(int ease, long thinkTimeMs, boolean timedOut) {
        return answered(ease, thinkTimeMs, timedOut, false);
    }

    public static CardOutcome answered(int ease, long thinkTimeMs, boolean timedOut, boolean newCard) {
        return new CardOutcome(Kind.ANSWERED, ease, thinkTimeMs, timedOut, newCard);
    }

    public static CardOutcome noCards() {
        return new CardOutcome(Kind.NO_CARDS, 0, 0, false, false);
    }

    public static CardOutcome error() {
        return new CardOutcome(Kind.ERROR, 0, 0, false, false);
    }
}
