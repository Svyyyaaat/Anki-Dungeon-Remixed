package com.watabou.pixeldungeon.items.scrolls;

import com.ankidungeon.AnkiCheck;
import com.ankidungeon.CheckKind;
import com.nyrds.platform.audio.Sample;
import com.watabou.pixeldungeon.Assets;
import com.watabou.pixeldungeon.Dungeon;
import com.watabou.pixeldungeon.actors.Char;
import com.watabou.pixeldungeon.utils.GLog;
import com.watabou.utils.Random;

import java.util.HashSet;

/**
 * Unknown scrolls ask a card: a pass makes the scroll helpful, a miss harmful.
 * When the scroll isn't already of that kind a random scroll of the right kind
 * takes effect and the read scroll stays unidentified. Upgrade scrolls never
 * come out of a stand-in; a real one is lost on a miss.
 */
public final class AnkiScrolls {

    @SuppressWarnings("unchecked")
    private static final Class<? extends Scroll>[] HARMFUL = new Class[]{
            ScrollOfCurse.class,
            ScrollOfChallenge.class,
            ScrollOfSummoning.class,
            ScrollOfPsionicBlast.class,
    };

    // no item-selector scrolls here: their cancel path would hand the stand-in back
    @SuppressWarnings("unchecked")
    private static final Class<? extends Scroll>[] HELPFUL = new Class[]{
            ScrollOfMagicMapping.class,
            ScrollOfRecharging.class,
            ScrollOfRemoveCurse.class,
            ScrollOfTeleportation.class,
            ScrollOfTerror.class,
            ScrollOfLullaby.class,
            ScrollOfMirrorImage.class,
            ScrollOfDomination.class,
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
        return scroll instanceof ScrollOfUpgrade || scroll instanceof ScrollOfWeaponUpgrade;
    }

    static boolean wantsCheck(Scroll scroll, Char chr) {
        return chr == Dungeon.hero && !scroll.isKnown() && !(scroll instanceof BlankScroll)
                && AnkiCheck.active(CheckKind.SCROLL);
    }

    static void read(Scroll scroll, Char chr) {
        AnkiCheck.request(CheckKind.SCROLL, result -> {
            if (result.skipped) {
                scroll.readVanilla(chr);
                return;
            }

            if (upgradeLike(scroll)) {
                if (result.correct) {
                    scroll.readVanilla(chr);
                } else {
                    scroll.detach(chr.getBelongings().backpack);
                    scroll.setKnown();
                    GLog.n("The %s slipped from your hands and the draft carried it away!", scroll.name());
                    Sample.INSTANCE.play(Assets.SND_READ);
                    chr.spend(Scroll.TIME_TO_READ);
                    chr.readyAndIdle();
                }
                return;
            }

            if (result.correct) {
                GLog.p("The runes fall into place.");
            } else {
                GLog.n("You stumble over the runes!");
            }

            boolean wantHarmful = !result.correct;
            if (harmful(scroll) == wantHarmful) {
                scroll.readVanilla(chr);
                return;
            }

            scroll.detach(chr.getBelongings().backpack);
            Scroll standIn = standIn(wantHarmful);
            HashSet<Class<? extends Scroll>> known = Scroll.knownSnapshot();
            standIn.doRead(chr);
            Scroll.restoreKnown(known);
            chr.readyAndIdle();
        });
    }

    private static Scroll standIn(boolean harmful) {
        Class<? extends Scroll>[] pool = harmful ? HARMFUL : HELPFUL;
        Scroll standIn;
        try {
            standIn = pool[Random.Int(pool.length)].newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return standIn;
    }
}
