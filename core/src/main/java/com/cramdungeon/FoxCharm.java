package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.util.ArrayList;

/** Testing item for the Kitsune: changes how many tails she shows. */
public class FoxCharm extends Item {

    private static final String AC_TAILS = "TAILS";

    {
        image = ItemSpriteSheet.SOMETHING;
        defaultAction = AC_TAILS;
        unique = true;
    }

    @Override
    public ArrayList<String> actions(Hero hero) {
        ArrayList<String> actions = super.actions(hero);
        if (Kitsune.is(hero)) {
            actions.add(AC_TAILS);
        }
        return actions;
    }

    @Override
    public String actionName(String action, Hero hero) {
        if (AC_TAILS.equals(action)) {
            return "Tails";
        }
        return super.actionName(action, hero);
    }

    @Override
    public void execute(Hero hero, String action) {
        super.execute(hero, action);
        if (AC_TAILS.equals(action) && Kitsune.is(hero)) {
            hero.tails = hero.tails % Kitsune.MAX_TAILS + 1;
            GLog.i(hero.tails == 1 ? "You now have 1 tail." : "You now have " + hero.tails + " tails.");
        }
    }

    @Override
    public String name() {
        return "fox charm (test)";
    }

    @Override
    public String desc() {
        return "A testing charm. Use it to cycle the Kitsune's tails between 1 and " + Kitsune.MAX_TAILS + ".";
    }

    @Override
    public boolean isUpgradable() {
        return false;
    }

    @Override
    public boolean isIdentified() {
        return true;
    }
}
