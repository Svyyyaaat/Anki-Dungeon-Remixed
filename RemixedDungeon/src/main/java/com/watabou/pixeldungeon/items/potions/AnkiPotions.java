package com.watabou.pixeldungeon.items.potions;

import com.ankidungeon.AnkiCheck;
import com.ankidungeon.CheckKind;
import com.nyrds.platform.audio.Sample;
import com.watabou.pixeldungeon.Assets;
import com.watabou.pixeldungeon.Dungeon;
import com.watabou.pixeldungeon.actors.Char;
import com.watabou.pixeldungeon.levels.Level;
import com.watabou.pixeldungeon.utils.GLog;
import com.watabou.utils.Random;

import java.util.HashSet;

/**
 * Unknown potions ask a card. Drinking: a pass makes the potion helpful, a miss
 * makes it harmful. Throwing is the other way round. If the potion already is
 * what the answer asks for it works as usual, otherwise a random potion of the
 * right kind takes its effect while the potion itself stays unidentified.
 * Strength never comes out of a stand-in; a real one is lost on a miss.
 */
public final class AnkiPotions {

    @SuppressWarnings("unchecked")
    private static final Class<? extends Potion>[] HARMFUL = new Class[]{
            PotionOfLiquidFlame.class,
            PotionOfToxicGas.class,
            PotionOfParalyticGas.class,
            PotionOfFrost.class,
    };

    @SuppressWarnings("unchecked")
    private static final Class<? extends Potion>[] HELPFUL = new Class[]{
            PotionOfHealing.class,
            PotionOfExperience.class,
            PotionOfLevitation.class,
            PotionOfMindVision.class,
            PotionOfInvisibility.class,
            PotionOfMana.class,
    };

    private static final float TIME_TO_DRINK = 1f;

    private AnkiPotions() {
    }

    static boolean harmful(Potion potion) {
        for (Class<? extends Potion> c : HARMFUL) {
            if (c.isInstance(potion)) {
                return true;
            }
        }
        return false;
    }

    // potion of might is a potion of strength too
    private static boolean strengthLike(Potion potion) {
        return potion instanceof PotionOfStrength;
    }

    static boolean wantsDrinkCheck(Potion potion, Char chr) {
        return chr == Dungeon.hero && !potion.isKnown() && AnkiCheck.active(CheckKind.POTION);
    }

    static boolean wantsThrowCheck(Potion potion, int cell, Char thrower) {
        if (thrower != Dungeon.hero || potion.isKnown()) {
            return false;
        }
        Level level = thrower.level();
        // pits and magic wells keep their vanilla handling
        return !level.pit[cell] && !potion.isMagicWell(level, cell) && AnkiCheck.active(CheckKind.POTION);
    }

    static void drink(Potion potion, Char chr) {
        AnkiCheck.request(CheckKind.POTION, result -> {
            if (result.skipped) {
                potion.drink(chr);
                return;
            }

            if (strengthLike(potion)) {
                if (result.correct) {
                    potion.drink(chr);
                } else {
                    potion.detach(chr.getBelongings().backpack);
                    slipped(potion);
                    chr.doOperate(TIME_TO_DRINK);
                }
                return;
            }

            if (result.correct) {
                GLog.p("Your knowledge tames the brew.");
            } else {
                GLog.n("The brew turns against you!");
            }

            boolean wantHarmful = !result.correct;
            if (harmful(potion) == wantHarmful) {
                potion.drink(chr);
                return;
            }

            potion.detach(chr.getBelongings().backpack);
            Potion standIn = standIn(wantHarmful, potion);
            HashSet<Class<? extends Potion>> known = Potion.knownSnapshot();
            standIn.apply(chr);
            Potion.restoreKnown(known);

            Sample.INSTANCE.play(Assets.SND_DRINK);
            chr.doOperate(TIME_TO_DRINK);
        });
    }

    /** The potion is already off the belt and in flight when this runs. */
    static void thrown(Potion potion, int cell, Char thrower, Char enemy) {
        AnkiCheck.request(CheckKind.POTION, result -> {
            if (result.skipped) {
                potion.onThrowVanilla(cell, thrower, enemy);
                return;
            }

            if (strengthLike(potion)) {
                if (result.correct) {
                    potion.onThrowVanilla(cell, thrower, enemy);
                } else {
                    slipped(potion);
                }
                return;
            }

            if (result.correct) {
                GLog.p("Your wits turn the flask into a weapon.");
            } else {
                GLog.n("The flask goes soft as it flies...");
            }

            boolean wantHarmful = result.correct;
            if (harmful(potion) == wantHarmful) {
                potion.onThrowVanilla(cell, thrower, enemy);
                return;
            }

            Potion standIn = standIn(wantHarmful, potion);
            HashSet<Class<? extends Potion>> known = Potion.knownSnapshot();
            standIn.onThrowVanilla(cell, thrower, enemy);
            Potion.restoreKnown(known);
        });
    }

    private static void slipped(Potion potion) {
        potion.setKnown();
        GLog.n("The %s slipped from your hands and is lost!", potion.name());
        Sample.INSTANCE.play(Assets.SND_SHATTER);
    }

    private static Potion standIn(boolean harmful, Potion original) {
        Class<? extends Potion>[] pool = harmful ? HARMFUL : HELPFUL;
        Potion standIn;
        try {
            standIn = pool[Random.Int(pool.length)].newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        standIn.disguiseAs(original);
        return standIn;
    }
}
