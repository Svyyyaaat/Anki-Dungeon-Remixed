package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.ui.OptionSlider;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
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
    private CheckBox chkTts;
    private RedButton btnMedia;
    private RedButton btnFrequency;
    private ColorBlock sep2;
    // set up in createChildren(): Component's constructor calls it before field
    // initializers run, so an initializer here would still be null at that point
    private ArrayList<CheckBox> triggers;

    private String shownDeckLabel;

    @Override
    protected void createChildren() {
        triggers = new ArrayList<>();

        title = PixelScene.renderTextBlock(Txt.get("tab.title"), 9);
        title.hardlight(Window.TITLE_COLOR);
        add(title);

        sep1 = new ColorBlock(1, 1, 0xFF000000);
        add(sep1);

        chkEnabled = new CheckBox(Txt.get("tab.enabled")) {
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

        optThink = new OptionSlider(Txt.get("tab.think_time"),
                AnkiPrefs.MIN_THINK_TIME + "s", AnkiPrefs.MAX_THINK_TIME + "s",
                AnkiPrefs.MIN_THINK_TIME / THINK_STEP, AnkiPrefs.MAX_THINK_TIME / THINK_STEP) {
            @Override
            protected void onChange() {
                AnkiPrefs.thinkTimeSeconds(getSelectedValue() * THINK_STEP);
            }
        };
        optThink.setSelectedValue(AnkiPrefs.thinkTimeSeconds() / THINK_STEP);
        add(optThink);

        chkSimple = new CheckBox(Txt.get("tab.simple")) {
            @Override
            protected void onClick() {
                super.onClick();
                AnkiPrefs.simpleGrading(checked());
            }
        };
        chkSimple.checked(AnkiPrefs.simpleGrading());
        add(chkSimple);

        chkTts = new CheckBox(Txt.get("tab.tts")) {
            @Override
            protected void onClick() {
                super.onClick();
                AnkiPrefs.tts(checked());
            }
        };
        chkTts.checked(AnkiPrefs.tts());
        add(chkTts);

        btnMedia = new RedButton(Txt.get("tab.media")) {
            @Override
            protected void onClick() {
                showMediaOptions();
            }
        };
        add(btnMedia);

        btnFrequency = new RedButton(frequencyLabel()) {
            @Override
            protected void onClick() {
                showFrequencyOptions(this);
            }
        };
        add(btnFrequency);

        sep2 = new ColorBlock(1, 1, 0xFF000000);
        add(sep2);

        addTrigger(Txt.get("tab.combat"), AnkiPrefs.TRIGGER_COMBAT);
        addTrigger(Txt.get("tab.items"), AnkiPrefs.TRIGGER_ITEMS);
        addTrigger(Txt.get("tab.loot"), AnkiPrefs.TRIGGER_PICKUP);
        addTrigger(Txt.get("tab.sneak"), AnkiPrefs.TRIGGER_SNEAK);
        addTrigger(Txt.get("tab.search"), AnkiPrefs.TRIGGER_SEARCH);

        // not a trigger, but it fills the free slot of the grid
        CheckBox soft = new CheckBox(Txt.get("tab.soft_start")) {
            @Override
            protected void onClick() {
                super.onClick();
                AnkiPrefs.softStart(checked());
                if (checked()) {
                    ShatteredPixelDungeon.scene().addToFront(new WndMessage(Txt.get("tab.soft_start_info")));
                }
            }
        };
        soft.checked(AnkiPrefs.softStart());
        triggers.add(soft);
        add(soft);
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
        // two columns when the window is wide enough
        boolean wide = width > 200;
        if (wide) {
            chkSimple.setRect(0, optThink.bottom() + GAP, width / 2f - 1, BTN_HEIGHT);
            chkTts.setRect(width / 2f + 1, chkSimple.top(), width / 2f - 1, BTN_HEIGHT);
        } else {
            chkSimple.setRect(0, optThink.bottom() + GAP, width, BTN_HEIGHT);
            chkTts.setRect(0, chkSimple.bottom() + GAP, width, BTN_HEIGHT);
        }
        if (wide) {
            btnFrequency.setRect(0, chkTts.bottom() + GAP, width / 2f - 1, BTN_HEIGHT);
            btnMedia.setRect(width / 2f + 1, btnFrequency.top(), width / 2f - 1, BTN_HEIGHT);
        } else {
            btnFrequency.setRect(0, chkTts.bottom() + GAP, width, BTN_HEIGHT);
            btnMedia.setRect(0, btnFrequency.bottom() + GAP, width, BTN_HEIGHT);
        }

        sep2.size(width, 1);
        sep2.y = btnMedia.bottom() + GAP;

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

    private static String frequencyLabel() {
        return Txt.get("tab.cramming", AnkiPrefs.frequency().label());
    }

    private static void showFrequencyOptions(RedButton button) {
        AnkiMomentum.Frequency[] all = AnkiMomentum.Frequency.values();
        String[] labels = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            labels[i] = all[i] == AnkiPrefs.frequency() ? Txt.get("freq.now", all[i].label()) : all[i].label();
        }
        ShatteredPixelDungeon.scene().addToFront(new WndOptions(Txt.get("freq.title"),
                Txt.get("freq.desc"), labels) {
            @Override
            protected void onSelect(int index) {
                AnkiPrefs.frequency(all[index]);
                button.text(frequencyLabel());
            }
        });
    }

    private static void showMediaOptions() {
        AnkiPlatform platform = AnkiCheck.platform();
        if (platform == null) {
            return;
        }
        String message = platform.mediaSummary() + "\n\n"
                + Txt.get("media.help");
        ShatteredPixelDungeon.scene().addToFront(new WndOptions(Txt.get("media.title"), message,
                Txt.get("media.add"), Txt.get("media.remove_all")) {
            @Override
            protected void onSelect(int index) {
                if (index == 0) {
                    platform.importMedia();
                } else if (index == 1) {
                    confirmClear(platform);
                }
            }
        });
    }

    private static void confirmClear(AnkiPlatform platform) {
        ShatteredPixelDungeon.scene().addToFront(new WndOptions(Txt.get("media.confirm_title"),
                Txt.get("media.confirm_text"),
                Txt.get("media.remove"), Txt.get("media.keep")) {
            @Override
            protected void onSelect(int index) {
                if (index == 0) {
                    platform.clearMedia(() -> { });
                }
            }
        });
    }

    private static String deckLabel() {
        AnkiPlatform platform = AnkiCheck.platform();
        if (platform != null && platform.status() == AnkiPlatform.Status.NOT_INSTALLED) {
            return Txt.get("tab.get_ankidroid");
        }
        if (!AnkiPrefs.hasDeck()) {
            return Txt.get("tab.choose_deck");
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
        return Txt.get("tab.deck", name);
    }
}
