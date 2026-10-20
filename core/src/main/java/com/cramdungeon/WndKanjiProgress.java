package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;

/** Shows how far a long kanji deck job (adding notes, suspending cards) has got. */
public class WndKanjiProgress extends Window {

    private static final int WIDTH = 120;
    private static final int MARGIN = 4;

    private final RenderedTextBlock title;
    private final RenderedTextBlock text;

    public WndKanjiProgress(String heading, String message) {
        title = PixelScene.renderTextBlock(heading, 9);
        title.hardlight(TITLE_COLOR);
        title.maxWidth(WIDTH - MARGIN * 2);
        add(title);

        text = PixelScene.renderTextBlock(message, 6);
        text.maxWidth(WIDTH - MARGIN * 2);
        add(text);
        layout();
    }

    public void text(String message) {
        text.text(message);
        layout();
    }

    private void layout() {
        title.setPos(MARGIN, MARGIN);
        text.setPos(MARGIN, title.bottom() + 4);
        resize(WIDTH, (int) (text.bottom() + MARGIN));
    }
}
