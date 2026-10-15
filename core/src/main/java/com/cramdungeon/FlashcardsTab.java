package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.ui.OptionSlider;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.ui.Component;

import java.util.ArrayList;

/** Settings tab: deck, thinking time, grading buttons and which actions ask cards. */
public class FlashcardsTab extends Component {

    private static final int SLIDER_HEIGHT = 21;
    private static final int BTN_HEIGHT = 16;
    private static final float GAP = 1;
    private static final int THINK_STEP = 5;

    private RenderedTextBlock title;
    private ColorBlock sep1;
    private CheckBox chkEnabled;
    private RedButton btnDeck;
    private OptionSlider optThink;
    private CheckBox chkSimple;
    private ColorBlock sep2;
    // set up in createChildren(): Component's constructor calls it before field
    // initializers run, so an initializer here would still be null at that point
    private ArrayList<CheckBox> triggers;

    private String shownDeckLabel;

    @Override
    protected void createChildren() {
        triggers = new ArrayList<>();

        title = PixelScene.renderTextBlock("Flashcards", 9);
        title.hardlight(Window.TITLE_COLOR);
        add(title);

        sep1 = new ColorBlock(1, 1, 0xFF000000);
        add(sep1);

        chkEnabled = new CheckBox("Quiz me with AnkiDroid cards") {
            @Override
            protected void onClick() {
                super.onClick();
                AnkiPrefs.enabled(checked());
            }
        };
        chkEnabled.checked(AnkiPrefs.enabled());
        add(chkEnabled);

        shownDeckLabel = deckLabel();
        btnDeck = new RedButton(shownDeckLabel) {
            @Override
            protected void onClick() {
                AnkiPlatform platform = AnkiCheck.platform();
                if (platform != null && platform.status() == AnkiPlatform.Status.NOT_INSTALLED) {
                    platform.openAnkiDroid();
                } else {
                    AnkiCheck.chooseDeck();
                }
            }
        };
        add(btnDeck);

        optThink = new OptionSlider("Time to think",
                AnkiPrefs.MIN_THINK_TIME + "s", AnkiPrefs.MAX_THINK_TIME + "s",
                AnkiPrefs.MIN_THINK_TIME / THINK_STEP, AnkiPrefs.MAX_THINK_TIME / THINK_STEP) {
            @Override
            protected void onChange() {
                AnkiPrefs.thinkTimeSeconds(getSelectedValue() * THINK_STEP);
            }
        };
        optThink.setSelectedValue(AnkiPrefs.thinkTimeSeconds() / THINK_STEP);
        add(optThink);

        chkSimple = new CheckBox("Only Again / Good") {
            @Override
            protected void onClick() {
                super.onClick();
                AnkiPrefs.simpleGrading(checked());
            }
        };
        chkSimple.checked(AnkiPrefs.simpleGrading());
        add(chkSimple);

        sep2 = new ColorBlock(1, 1, 0xFF000000);
        add(sep2);

        addTrigger("Combat", AnkiPrefs.TRIGGER_COMBAT);
        addTrigger("Potions, scrolls", AnkiPrefs.TRIGGER_ITEMS);
        addTrigger("Loot", AnkiPrefs.TRIGGER_PICKUP);
        addTrigger("Sneaking", AnkiPrefs.TRIGGER_SNEAK);
        addTrigger("Searching", AnkiPrefs.TRIGGER_SEARCH);
    }

    private void addTrigger(String label, String key) {
        CheckBox box = new CheckBox(label) {
            @Override
            protected void onClick() {
                super.onClick();
                AnkiPrefs.trigger(key, checked());
            }
        };
        box.checked(AnkiPrefs.trigger(key));
        triggers.add(box);
        add(box);
    }

    @Override
    protected void layout() {
        title.setPos((width - title.width()) / 2, y + GAP);
        sep1.size(width, 1);
        sep1.y = title.bottom() + 3 * GAP;

        chkEnabled.setRect(0, sep1.y + 1 + GAP, width, BTN_HEIGHT);
        btnDeck.setRect(0, chkEnabled.bottom() + GAP, width, BTN_HEIGHT);
        optThink.setRect(0, btnDeck.bottom() + GAP, width, SLIDER_HEIGHT);
        chkSimple.setRect(0, optThink.bottom() + GAP, width, BTN_HEIGHT);

        sep2.size(width, 1);
        sep2.y = chkSimple.bottom() + GAP;

        // two columns when the window is wide enough
        boolean wide = width > 200;
        float colWidth = wide ? width / 2f - 1 : width;
        float top = sep2.y + 1 + GAP;
        float bottom = top;
        for (int i = 0; i < triggers.size(); i++) {
            int row = wide ? i / 2 : i;
            float x = wide && i % 2 == 1 ? width / 2f + 1 : 0;
            CheckBox box = triggers.get(i);
            box.setRect(x, top + row * (BTN_HEIGHT + GAP), colWidth, BTN_HEIGHT);
            bottom = Math.max(bottom, box.bottom());
        }
        height = bottom;
    }

    @Override
    public void update() {
        super.update();
        // the deck may change from the deck picker opened on top of this window
        String label = deckLabel();
        if (!label.equals(shownDeckLabel)) {
            shownDeckLabel = label;
            btnDeck.text(label);
        }
        if (chkEnabled.checked() != AnkiPrefs.enabled()) {
            chkEnabled.checked(AnkiPrefs.enabled());
        }
    }

    private static String deckLabel() {
        AnkiPlatform platform = AnkiCheck.platform();
        if (platform != null && platform.status() == AnkiPlatform.Status.NOT_INSTALLED) {
            return "Get AnkiDroid";
        }
        if (!AnkiPrefs.hasDeck()) {
            return "Choose a deck";
        }
        String name = AnkiPrefs.deckName();
        // nested decks: the leaf name is what fits
        int sep = name.lastIndexOf("::");
        if (sep >= 0) {
            name = name.substring(sep + 2);
        }
        if (name.length() > 18) {
            name = name.substring(0, 17) + "..";
        }
        return "Deck: " + name;
    }
}
