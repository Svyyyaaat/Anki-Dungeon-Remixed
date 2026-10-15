package com.ankidungeon;

import com.watabou.pixeldungeon.ui.Slider;
import com.watabou.pixeldungeon.windows.WndMenuCommon;

/** Settings > Flashcards: deck, thinking time, grading buttons and which actions ask cards. */
public class WndAnkiSettings extends WndMenuCommon {

    private static final int THINK_STEP = 5;

    @Override
    protected void createItems() {
        menuItems.add(new MenuCheckBox("Flashcards", AnkiPrefs.enabled()) {
            @Override
            protected void onClick() {
                super.onClick();
                AnkiPrefs.enabled(checked());
            }
        });

        AnkiPlatform platform = AnkiCheck.platform();
        if (platform != null && platform.status() == AnkiPlatform.Status.NOT_INSTALLED) {
            menuItems.add(new MenuButton("Get AnkiDroid") {
                @Override
                protected void onClick() {
                    super.onClick();
                    platform.openAnkiDroid();
                }
            });
        } else {
            menuItems.add(new MenuButton(deckLabel()) {
                @Override
                protected void onClick() {
                    super.onClick();
                    WndAnkiSettings.this.hide();
                    AnkiCheck.chooseDeck();
                }
            });
        }

        Slider think = new Slider("Time to think",
                AnkiPrefs.MIN_THINK_TIME + "s", AnkiPrefs.MAX_THINK_TIME + "s",
                AnkiPrefs.MIN_THINK_TIME / THINK_STEP, AnkiPrefs.MAX_THINK_TIME / THINK_STEP) {
            @Override
            protected void onChange() {
                AnkiPrefs.thinkTimeSeconds(getSelectedValue() * THINK_STEP);
            }
        };
        think.setSelectedValue(AnkiPrefs.thinkTimeSeconds() / THINK_STEP);
        think.setSize(WIDTH, BUTTON_HEIGHT);
        menuItems.add(think);

        menuItems.add(new MenuCheckBox("Only Again / Good", AnkiPrefs.simpleGrading()) {
            @Override
            protected void onClick() {
                super.onClick();
                AnkiPrefs.simpleGrading(checked());
            }
        });

        addTrigger("Cards in combat", AnkiPrefs.TRIGGER_COMBAT);
        addTrigger("Unknown potions/scrolls", AnkiPrefs.TRIGGER_ITEMS);
        addTrigger("Studying loot", AnkiPrefs.TRIGGER_PICKUP);
        addTrigger("Sneaking", AnkiPrefs.TRIGGER_SNEAK);
        addTrigger("Searching", AnkiPrefs.TRIGGER_SEARCH);
    }

    private void addTrigger(String label, String key) {
        menuItems.add(new MenuCheckBox(label, AnkiPrefs.trigger(key)) {
            @Override
            protected void onClick() {
                super.onClick();
                AnkiPrefs.trigger(key, checked());
            }
        });
    }

    private static String deckLabel() {
        if (!AnkiPrefs.hasDeck()) {
            return "Choose deck";
        }
        String name = AnkiPrefs.deckName();
        // nested decks: the leaf name is what fits
        int sep = name.lastIndexOf("::");
        if (sep >= 0) {
            name = name.substring(sep + 2);
        }
        if (name.length() > 14) {
            name = name.substring(0, 12) + "..";
        }
        return "Deck: " + name;
    }
}
