package com.ankidungeon;

public class AnkiDeck {
    public final long id;
    public final String name;
    public final int learn;
    public final int review;
    public final int fresh;

    public AnkiDeck(long id, String name, int learn, int review, int fresh) {
        this.id = id;
        this.name = name;
        this.learn = learn;
        this.review = review;
        this.fresh = fresh;
    }

    public int due() {
        return learn + review + fresh;
    }
}
