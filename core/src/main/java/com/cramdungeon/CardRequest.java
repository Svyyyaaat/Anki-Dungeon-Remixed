package com.cramdungeon;

public class CardRequest {
    public final String title;
    public final long thinkTimeMs;
    public final boolean simpleGrading;

    public CardRequest(String title, long thinkTimeMs, boolean simpleGrading) {
        this.title = title;
        this.thinkTimeMs = thinkTimeMs;
        this.simpleGrading = simpleGrading;
    }
}
