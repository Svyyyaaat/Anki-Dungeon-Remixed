package com.ankidungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.AnkiPotions;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon;

/** Thrown items: missiles ask a combat card, unknown potions a potion card, before they land. */
public final class AnkiThrow {

    private AnkiThrow() {
    }

    /** {@code impact} is the vanilla landing code (it also ends the hero's turn). */
    public static void impact(Hero user, Item item, Char enemy, Runnable impact) {
        if (item instanceof MissileWeapon && enemy != null && AnkiCombat.shouldCheck(user, enemy)) {
            AnkiCombat.check(user, enemy, impact);
        } else if (item instanceof Potion && AnkiPotions.wantsThrowCheck((Potion) item)) {
            AnkiPotions.thrown((Potion) item, impact);
        } else {
            impact.run();
        }
    }
}
