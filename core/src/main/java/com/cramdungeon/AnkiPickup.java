package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.effects.FloatingText;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

/**
 * Cards on pickup: gold gets a bonus, unknown gear reveals its curse (or
 * everything, if you were quick). Pickup has already spent its turn, so the card
 * shows while the hero waits for input and doesn't hold the actor thread.
 */
public final class AnkiPickup {

    private AnkiPickup() {
    }

    private static boolean studiable(Item item) {
        return (item instanceof EquipableItem || item instanceof Wand) && !item.isIdentified();
    }

    public static void afterPickUp(Hero hero, Item item) {
        if (hero != Dungeon.hero) {
            return;
        }
        if (item instanceof Gold) {
            final int amount = item.quantity();
            if (!AnkiCheck.active(CheckKind.PICKUP)) {
                return;
            }
            AnkiCheck.request(CheckKind.PICKUP, result -> {
                if (result.skipped || !result.correct) {
                    return;
                }
                int bonus = Math.max(1, Math.round(amount * 0.5f * result.performance));
                Dungeon.gold += bonus;
                Statistics.goldCollected += bonus;
                hero.sprite.showStatusWithIcon(CharSprite.NEUTRAL, "+" + bonus, FloatingText.GOLD);
                Item.updateQuickslot();
            });
            return;
        }

        if (!studiable(item) || !AnkiCheck.active(CheckKind.PICKUP)) {
            return;
        }
        AnkiCheck.request(CheckKind.PICKUP, result -> {
            if (result.skipped) {
                return;
            }
            if (!result.correct) {
                GLog.i("You can't make sense of the %s.", item.name());
                return;
            }
            if (result.fast()) {
                item.identify();
                GLog.p("You see right through it: %s.", item.name());
            } else {
                item.cursedKnown = true;
                if (item.cursed) {
                    GLog.n("You sense a malevolent aura: the %s is cursed!", item.name());
                } else {
                    GLog.p("You sense the %s is free of curses.", item.name());
                }
            }
            Item.updateQuickslot();
        });
    }
}
