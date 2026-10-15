package com.cramdungeon;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CardTtsTest {

    private static Map<String, String> fields(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    public void readsTtsFilters() {
        Map<String, String> f = fields("Front", "<b>猫</b>&nbsp;です", "Back", "cat");
        List<CardTts.Speech> q = CardTts.side("{{Front}} {{tts ja_JP speed=1.2:Front}}", f,
                CardTts.none(), 0, true);
        assertEquals("[ja_JP@1.2:猫 です]", q.toString());
        List<CardTts.Speech> a = CardTts.side("{{FrontSide}}<hr id=answer>{{tts en_US:Back}}", f, q, 0, false);
        assertEquals("[ja_JP@1.2:猫 です, en_US@1.0:cat]", a.toString());
    }

    @Test
    public void readsInlineTagsInFieldsAndTemplates() {
        Map<String, String> f = fields("Front", "x [anki:tts lang=de_DE]Hund[/anki:tts] y");
        List<CardTts.Speech> q = CardTts.side("[anki:tts lang=fr_FR]chien[/anki:tts]{{Front}}", f,
                CardTts.none(), 0, true);
        assertEquals("[fr_FR@1.0:chien, de_DE@1.0:Hund]", q.toString());
    }

    @Test
    public void skipsSectionsThatDontRender() {
        Map<String, String> f = fields("Audio", "", "Word", "dog");
        String t = "{{#Audio}}{{tts en_US:Audio}}{{/Audio}}{{^Audio}}{{tts en_US:Word}}{{/Audio}}";
        assertEquals("[en_US@1.0:dog]", CardTts.side(t, f, CardTts.none(), 0, true).toString());
    }

    @Test
    public void handlesClozeAndReadings() {
        Map<String, String> f = fields("Text", "{{c1::Paris}} is in {{c2::France}}", "Reading", "日本[にほん]");
        assertEquals("[en_US@1.0:... is in France]",
                CardTts.side("{{tts en_US:cloze:Text}}", f, CardTts.none(), 1, true).toString());
        assertEquals("[en_US@1.0:Paris is in France]",
                CardTts.side("{{tts en_US:cloze:Text}}", f, CardTts.none(), 1, false).toString());
        assertEquals("[ja_JP@1.0:にほん]",
                CardTts.side("{{tts ja_JP:kana:Reading}}", f, CardTts.none(), 0, true).toString());
    }
}
