package com.ankidungeon;

import com.watabou.pixeldungeon.Dungeon;
import com.watabou.pixeldungeon.Statistics;
import com.watabou.pixeldungeon.actors.Char;
import com.watabou.pixeldungeon.items.EquipableItem;
import com.watabou.pixeldungeon.items.Gold;
import com.watabou.pixeldungeon.items.Item;
import com.watabou.pixeldungeon.items.wands.Wand;
import com.watabou.pixeldungeon.sprites.CharSprite;
import com.watabou.pixeldungeon.utils.GLog;

/** Cards on pickup: gold gets a bonus, unknown gear reveals its curse (and more if you were quick). */
public final class AnkiPickup {

    private AnkiPickup() {
    }

    private static boolean studiable(Item item) {
        return (item instanceof EquipableItem || item instanceof Wand) && !item.isIdentified();
    }

    public static void afterPickUp(Char hero, Item item) {
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
                new Gold(bonus).collect(hero);
                Statistics.goldCollected += bonus;
                hero.showStatus_a(CharSprite.NEUTRAL, "+%d", bonus);
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
                GLog.i("You can't make sense of %s.", item.name());
                return;
            }
            if (result.fast()) {
                item.identify();
                GLog.p("You see right through it: %s.", item.name());
            } else {
                item.setCursedKnown(true);
                if (item.isCursed()) {
                    GLog.n("You sense a malevolent aura: %s is cursed!", item.name());
                } else {
                    GLog.p("You sense %s is free of curses.", item.name());
                }
            }
        });
    }
}
