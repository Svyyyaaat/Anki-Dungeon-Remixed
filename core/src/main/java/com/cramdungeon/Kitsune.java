package com.cramdungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;

/**
 * The Kitsune: for now a Mage run with her own sprite and tails. She is picked on
 * the hero select screen as her own entry and plays exactly like the Mage, so all
 * of the Mage's talents, items, quests and badges apply unchanged.
 */
public final class Kitsune {

    public static final String TITLE = "kitsune";
    public static final String SHORT_DESC = "The _Kitsune_ is a fox spirit who studies her way through the dungeon. "
            + "She grows more tails as her power grows.\n\nFor now she plays exactly like the Mage.";

    public static final String SPRITES = "sprites/kitsune.png";
    public static final String TAILS = "sprites/kitsune_tails.png";
    public static final int MAX_TAILS = 3;

    /** Each tail layer is a TAIL_W x TAIL_H cell with her 12x15 frame at (HERO_X, HERO_Y). */
    public static final int TAIL_W = 40;
    public static final int TAIL_H = 26;
    public static final int HERO_X = 20;
    public static final int HERO_Y = 7;

    // how far her body moves in each of the 21 hero frames; null hides the tails (death skulls)
    private static final int[][] FRAME_OFFSETS = {
            {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 1}, {0, 0}, {0, 0}, {0, 1},
            null, null, null, null, null,
            {-1, 0}, {0, 0}, {1, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0},
    };

    /** Whether the Kitsune is the pick on the hero select screen (her class is MAGE). */
    public static boolean selected;

    private Kitsune() {
    }

    public static boolean is(Hero hero) {
        return hero != null && hero.kitsune;
    }

    /** Called when a new run starts, after the hero class has set the hero up. */
    public static void startRun(Hero hero, HeroClass cls) {
        hero.kitsune = selected && cls == HeroClass.MAGE;
        hero.tails = 1;
        if (hero.kitsune) {
            new FoxCharm().collect();
        }
    }

    /** Body offset for a frame of the hero sheet, or null when the tails should not show. */
    public static int[] frameOffset(int frame) {
        return frame >= 0 && frame < FRAME_OFFSETS.length ? FRAME_OFFSETS[frame] : null;
    }
}
