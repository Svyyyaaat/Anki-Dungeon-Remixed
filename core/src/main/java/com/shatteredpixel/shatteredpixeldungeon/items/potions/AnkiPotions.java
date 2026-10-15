package com.shatteredpixel.shatteredpixeldungeon.items.potions;

import com.ankidungeon.AnkiCheck;
import com.ankidungeon.CheckKind;
import com.ankidungeon.CheckResult;
import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfMastery;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Random;
import com.watabou.utils.Reflection;

/**
 * Unknown potions ask a card. Drinking: a pass makes the potion helpful, a miss
 * makes it harmful. Throwing is the other way round. If the potion already is
 * what the answer asks for it works as usual; otherwise an anonymous potion of
 * the right kind takes its effect and the potion itself stays unidentified.
 * Strength never comes out of a stand-in; a real one is lost on a miss.
 */
public final class AnkiPotions {

    @SuppressWarnings("unchecked")
    private static final Class<? extends Potion>[] HARMFUL = new Class[]{
            PotionOfToxicGas.class,
            PotionOfLiquidFlame.class,
            PotionOfFrost.class,
            PotionOfParalyticGas.class,
    };

    @SuppressWarnings("unchecked")
    private static final Class<? extends Potion>[] HELPFUL = new Class[]{
            PotionOfHealing.class,
            PotionOfMindVision.class,
            PotionOfHaste.class,
            PotionOfInvisibility.class,
            PotionOfLevitation.class,
            PotionOfPurity.class,
            PotionOfExperience.class,
    };

    private static final float TIME_TO_DRINK = 1f;

    // result of the card asked when a potion was thrown, consumed when it lands
    private static CheckResult pendingThrow;

    private AnkiPotions() {
    }

    static boolean harmful(Potion potion) {
        return Potion.mustThrowPots.contains(potion.getClass());
    }

    private static boolean strengthLike(Potion potion) {
        return potion instanceof PotionOfStrength || potion instanceof PotionOfMastery;
    }

    static boolean wantsDrinkCheck(Potion potion, Hero hero) {
        return hero == Dungeon.hero && !potion.isKnown() && AnkiCheck.active(CheckKind.POTION);
    }

    public static boolean wantsThrowCheck(Potion potion) {
        return !potion.isKnown() && AnkiCheck.active(CheckKind.POTION);
    }

    static void drink(Potion potion, Hero hero) {
        AnkiCheck.request(CheckKind.POTION, result -> {
            if (result.skipped) {
                potion.drink(hero);
                return;
            }

            if (strengthLike(potion)) {
                if (result.correct) {
                    potion.drink(hero);
                } else {
                    potion.detach(hero.belongings.backpack);
                    slipped(potion);
                    hero.spend(TIME_TO_DRINK);
                    hero.busy();
                    hero.sprite.operate(hero.pos);
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
                potion.drink(hero);
                return;
            }

            // same steps as Potion.drink, with a stand-in providing the effect
            potion.detach(hero.belongings.backpack);
            hero.spend(TIME_TO_DRINK);
            hero.busy();
            standIn(wantHarmful).apply(hero);
            Sample.INSTANCE.play(Assets.Sounds.DRINK);
            hero.sprite.operate(hero.pos);
        });
    }

    /** Asks the card while the flask flies; {@code impact} lands it (see {@link #interceptThrow}). */
    public static void thrown(Potion potion, Runnable impact) {
        AnkiCheck.request(CheckKind.POTION, result -> {
            pendingThrow = result;
            try {
                impact.run();
            } finally {
                pendingThrow = null;
            }
        });
    }

    /** Called from Potion.onThrow; true when the landing was handled here. */
    static boolean interceptThrow(Potion potion, int cell) {
        CheckResult result = pendingThrow;
        pendingThrow = null;
        if (result == null || result.skipped) {
            return false;
        }
        if (Dungeon.level.map[cell] == Terrain.WELL || Dungeon.level.pit[cell]) {
            return false;
        }

        if (strengthLike(potion)) {
            if (result.correct) {
                return false;
            }
            slipped(potion);
            return true;
        }

        if (result.correct) {
            GLog.p("Your wits turn the flask into a weapon.");
        } else {
            GLog.n("The flask goes soft as it flies...");
        }

        boolean wantHarmful = result.correct;
        if (harmful(potion) == wantHarmful) {
            return false;
        }
        Dungeon.level.pressCell(cell);
        standIn(wantHarmful).shatter(cell);
        return true;
    }

    private static void slipped(Potion potion) {
        potion.setKnown();
        GLog.n("The %s slipped from your hands and is lost!", potion.name());
        Sample.INSTANCE.play(Assets.Sounds.SHATTER);
    }

    private static Potion standIn(boolean harmful) {
        Class<? extends Potion>[] pool = harmful ? HARMFUL : HELPFUL;
        Potion standIn = Reflection.newInstance(pool[Random.Int(pool.length)]);
        standIn.anonymize();
        return standIn;
    }
}
