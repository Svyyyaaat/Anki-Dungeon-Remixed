package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.ui.ScrollingListPane;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;

import java.util.List;

/** Scrolling deck picker; the number is how many cards are due today. */
public class WndAnkiDecks extends Window {

    private static final int WIDTH = 120;
    private static final int MAX_HEIGHT = 144;

    public WndAnkiDecks(List<AnkiDeck> decks) {
        ScrollingListPane list = new ScrollingListPane();
        add(list);

        list.addTitle(Txt.get("decks.title"));

        long current = AnkiPrefs.deckId();
        for (AnkiDeck deck : decks) {
            ScrollingListPane.ListItem item = new ScrollingListPane.ListItem(
                    null, null, deck.name + " (" + deck.due() + ")") {
                @Override
                public boolean onClick(float x, float y) {
                    if (inside(x, y)) {
                        AnkiCheck.selectDeck(deck);
                        hide();
                        return true;
                    }
                    return false;
                }
            };
            if (deck.id == current) {
                item.hardlight(Window.TITLE_COLOR);
            }
            list.addItem(item);
        }

        resize(WIDTH, Math.min(MAX_HEIGHT, (int) list.content().height()));
        list.setRect(0, 0, width, height);
    }
}
