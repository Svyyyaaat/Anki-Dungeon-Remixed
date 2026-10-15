package com.ankidungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CheckResultTest {

    private static final long LIMIT = 20_000;

    @Test
    public void instantGoodIsFullPerformance() {
        CheckResult r = CheckResult.from(CardOutcome.answered(CardOutcome.GOOD, 0, false), LIMIT);
        assertTrue(r.correct);
        assertFalse(r.skipped);
        assertEquals(1f, r.performance, 1e-6);
        assertTrue(r.fast());
    }

    @Test
    public void slowGoodBottomsOutAtHalf() {
        CheckResult r = CheckResult.from(CardOutcome.answered(CardOutcome.GOOD, LIMIT, false), LIMIT);
        assertTrue(r.correct);
        assertEquals(0.5f, r.performance, 1e-6);
        assertFalse(r.fast());
    }

    @Test
    public void againAndTimeoutFail() {
        assertFalse(CheckResult.from(CardOutcome.answered(CardOutcome.AGAIN, 1000, false), LIMIT).correct);
        CheckResult timedOut = CheckResult.from(CardOutcome.answered(CardOutcome.GOOD, LIMIT, true), LIMIT);
        assertFalse(timedOut.correct);
        assertEquals(0f, timedOut.performance, 1e-6);
    }

    @Test
    public void hardAndEasyShiftPerformance() {
        float good = CheckResult.from(CardOutcome.answered(CardOutcome.GOOD, 5000, false), LIMIT).performance;
        float hard = CheckResult.from(CardOutcome.answered(CardOutcome.HARD, 5000, false), LIMIT).performance;
        float easy = CheckResult.from(CardOutcome.answered(CardOutcome.EASY, 5000, false), LIMIT).performance;
        assertTrue(hard < good);
        assertTrue(easy > good);
        assertTrue(easy <= 1f);
    }

    @Test
    public void noCardsOrErrorsAreSkipped() {
        assertTrue(CheckResult.from(CardOutcome.noCards(), LIMIT).skipped);
        assertTrue(CheckResult.from(CardOutcome.error(), LIMIT).skipped);
    }
}
