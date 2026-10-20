package com.cramdungeon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KanjiStrokesTest {

    @Test
    public void readsTheStrokeDataField() {
        Map<String, String> f = new HashMap<>();
        f.put("Kanji", "未");
        f.put("Stroke Data", "[\"M30.13,30.77c2.62,0.48,5.01,0.34,7.25,0.03\",\"M16.69,51.25c3.17,0.89\"]");
        List<String> s = KanjiStrokes.find(f);
        assertEquals(2, s.size());
        assertTrue(s.get(0).startsWith("M30.13"));
    }

    @Test
    public void toleratesHtmlInTheField() {
        assertEquals(1, KanjiStrokes.parse("<div>[&quot;M1,2c3,4&quot;]</div>").size());
    }

    @Test
    public void ignoresOtherContent() {
        Map<String, String> f = new HashMap<>();
        f.put("Number of Strokes", "5");
        f.put("Stroke order", "<img src=\"x.svg\">");
        assertNull(KanjiStrokes.find(f));
        assertNull(KanjiStrokes.parse("[1, 2]"));
    }

    @Test
    public void knowsWritingCards() {
        assertTrue(KanjiStrokes.isWritingCard("Recall"));
        assertTrue(KanjiStrokes.isWritingCard("Kanji Writing"));
        assertFalse(KanjiStrokes.isWritingCard("Recognition"));
        assertFalse(KanjiStrokes.isWritingCard("Card 1"));
    }
}
