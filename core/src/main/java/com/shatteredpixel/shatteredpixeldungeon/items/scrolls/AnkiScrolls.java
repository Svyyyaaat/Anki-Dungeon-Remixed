package com.shatteredpixel.shatteredpixeldungeon.items.scrolls;

import com.cramdungeon.AnkiCheck;
import com.cramdungeon.CheckKind;
import com.cramdungeon.Txt;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfChallenge;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfEnchantment;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfPsionicBlast;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Random;
import com.watabou.utils.Reflection;

/**
 * Unknown scrolls ask a card: a pass makes the scroll helpful, a miss harmful.
 * When the scroll isn't already of that kind an anonymous scroll of the right
 * kind takes effect and the read scroll stays unidentified. Upgrade scrolls
 * never come out of a stand-in; a real one is lost on a miss.
 */
public final class AnkiScrolls {

    @SuppressWarnings("unchecked")
    private static final Class<? extends Scroll>[] HARMFUL = new Class[]{
            ScrollOfRage.class,
            ScrollOfChallenge.class,
            ScrollOfPsionicBlast.class,
    };

    // no item-selector scrolls: they would need an item and could be cancelled
    @SuppressWarnings("unchecked")
    private static final Class<? extends Scroll>[] HELPFUL = new Class[]{
            ScrollOfMirrorImage.class,
            ScrollOfRecharging.class,
            ScrollOfTeleportation.class,
            ScrollOfLullaby.class,
            ScrollOfMagicMapping.class,
            ScrollOfRetribution.class,
            ScrollOfTerror.class,
    };

    private AnkiScrolls() {
    }

    static boolean harmful(Scroll scroll) {
        for (Class<? extends Scroll> c : HARMFUL) {
            if (c.isInstance(scroll)) {
                return true;
            }
        }
        return false;
    }

    private static boolean upgradeLike(Scroll scroll) {
        return scroll instanceof ScrollOfUpgrade || scroll instanceof ScrollOfEnchantment;
    }

    static boolean wantsCheck(Scroll scroll, Hero hero) {
        return hero == Dungeon.hero && !scroll.isKnown() && AnkiCheck.active(CheckKind.SCROLL);
    }

    static void read(Scroll scroll, Hero hero) {
        AnkiCheck.request(CheckKind.SCROLL, result -> {
            scroll.setCurrent(hero);

            if (result.skipped) {
                scroll.doRead();
                return;
            }

            if (upgradeLike(scroll)) {
                if (result.correct) {
                    scroll.doRead();
                } else {
                    scroll.detach(hero.belongings.backpack);
                    scroll.identify();
                    GLog.n(Txt.get("scroll.lost", scroll.name()));
                    scroll.readAnimation();
                }
                return;
            }

            if (result.correct) {
                GLog.p(Txt.get("scroll.ok"));
            } else {
                GLog.n(Txt.get("scroll.bad"));
            }

            boolean wantHarmful = !result.correct;
            if (harmful(scroll) == wantHarmful) {
                scroll.doRead();
                return;
            }

            scroll.detach(hero.belongings.backpack);
            Class<? extends Scroll>[] pool = wantHarmful ? HARMFUL : HELPFUL;
            Class<? extends Scroll> type = pool[Random.Int(pool.length)];
            Scroll standIn = Reflection.newInstance(type);
            standIn.anonymize();
            standIn.setCurrent(hero);
            standIn.doRead();
        });
    }
}
