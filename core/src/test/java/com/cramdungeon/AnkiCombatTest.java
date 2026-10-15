package com.cramdungeon;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AnkiCombatTest {

    @Test
    public void capGrowsPerChapter() {
        assertEquals(3, AnkiCombat.cap(1));
        assertEquals(3, AnkiCombat.cap(5));
        assertEquals(4, AnkiCombat.cap(6));
        assertEquals(5, AnkiCombat.cap(11));
        assertEquals(6, AnkiCombat.cap(16));
        assertEquals(7, AnkiCombat.cap(21));
        assertEquals(7, AnkiCombat.cap(26));
    }

    @Test
    public void sewersRampIsUnchanged() {
        // the old rule: streak - 1, capped at 3
        for (int streak = 1; streak <= 6; streak++) {
            assertEquals(Math.min(streak - 1, 3), AnkiCombat.charges(streak, 3));
        }
    }

    @Test
    public void deeperBanksStartAtHalfTheCap() {
        assertEquals(0, AnkiCombat.charges(1, 22));
        assertEquals(3, AnkiCombat.charges(2, 22));
        assertEquals(4, AnkiCombat.charges(3, 22));
        assertEquals(7, AnkiCombat.charges(6, 22));
        assertEquals(7, AnkiCombat.charges(20, 22));
        assertEquals(2, AnkiCombat.charges(2, 8));
        assertEquals(4, AnkiCombat.charges(4, 8));
    }
}
