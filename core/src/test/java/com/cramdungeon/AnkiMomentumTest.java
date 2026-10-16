package com.cramdungeon;

import static com.cramdungeon.AnkiMomentum.Frequency.NORMAL;
import static com.cramdungeon.AnkiMomentum.Frequency.OFTEN;
import static com.cramdungeon.AnkiMomentum.Frequency.REDUCED;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AnkiMomentumTest {

    @Test
    public void capGrowsPerChapter() {
        assertEquals(3, AnkiMomentum.cap(OFTEN, 1));
        assertEquals(3, AnkiMomentum.cap(OFTEN, 5));
        assertEquals(4, AnkiMomentum.cap(OFTEN, 6));
        assertEquals(5, AnkiMomentum.cap(OFTEN, 11));
        assertEquals(6, AnkiMomentum.cap(OFTEN, 16));
        assertEquals(7, AnkiMomentum.cap(OFTEN, 21));
        assertEquals(7, AnkiMomentum.cap(OFTEN, 26));
        assertEquals(5, AnkiMomentum.cap(NORMAL, 1));
        assertEquals(9, AnkiMomentum.cap(NORMAL, 26));
        assertEquals(8, AnkiMomentum.cap(REDUCED, 1));
        assertEquals(12, AnkiMomentum.cap(REDUCED, 26));
    }

    @Test
    public void oftenKeepsTheOldSewersRamp() {
        // streak - 1, capped at 3
        for (int streak = 1; streak <= 6; streak++) {
            assertEquals(Math.min(streak - 1, 3), AnkiMomentum.charges(OFTEN, streak, 3));
        }
    }

    @Test
    public void deeperBanksStartAtHalfTheCap() {
        assertEquals(0, AnkiMomentum.charges(OFTEN, 1, 22));
        assertEquals(3, AnkiMomentum.charges(OFTEN, 2, 22));
        assertEquals(4, AnkiMomentum.charges(OFTEN, 3, 22));
        assertEquals(7, AnkiMomentum.charges(OFTEN, 6, 22));
        assertEquals(7, AnkiMomentum.charges(OFTEN, 20, 22));
        assertEquals(2, AnkiMomentum.charges(OFTEN, 2, 8));
        assertEquals(4, AnkiMomentum.charges(OFTEN, 4, 8));
    }

    @Test
    public void lowerFrequenciesBankFromTheFirstAnswer() {
        assertEquals(2, AnkiMomentum.charges(NORMAL, 1, 1));
        assertEquals(5, AnkiMomentum.charges(NORMAL, 9, 1));
        assertEquals(4, AnkiMomentum.charges(REDUCED, 1, 1));
        assertEquals(8, AnkiMomentum.charges(REDUCED, 5, 1));
        assertEquals(12, AnkiMomentum.charges(REDUCED, 30, 26));
    }
}
