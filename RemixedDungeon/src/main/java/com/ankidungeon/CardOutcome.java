package com.ankidungeon;

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

    private CardOutcome(Kind kind, int ease, long thinkTimeMs, boolean timedOut) {
        this.kind = kind;
        this.ease = ease;
        this.thinkTimeMs = thinkTimeMs;
        this.timedOut = timedOut;
    }

    public static CardOutcome answered(int ease, long thinkTimeMs, boolean timedOut) {
        return new CardOutcome(Kind.ANSWERED, ease, thinkTimeMs, timedOut);
    }

    public static CardOutcome noCards() {
        return new CardOutcome(Kind.NO_CARDS, 0, 0, false);
    }

    public static CardOutcome error() {
        return new CardOutcome(Kind.ERROR, 0, 0, false);
    }
}
