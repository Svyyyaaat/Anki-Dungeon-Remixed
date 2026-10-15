package com.nyrds.pixeldungeon.ai;

import com.ankidungeon.AnkiStealth;
import com.watabou.pixeldungeon.Dungeon;
import com.nyrds.pixeldungeon.mechanics.NamedEntityKind;
import com.nyrds.pixeldungeon.mechanics.buffs.BuffFactory;
import com.nyrds.pixeldungeon.ml.R;
import com.watabou.pixeldungeon.actors.Char;
import com.watabou.pixeldungeon.actors.mobs.Mob;
import com.watabou.pixeldungeon.utils.Utils;
import org.jetbrains.annotations.NotNull;

public class Sleeping extends MobAi implements AiState {

    public Sleeping(){}

    @Override
    public void act(@NotNull Char me) {

        if (me.hasBuff(BuffFactory.ANESTHESIA)) {
            me.spend(Mob.TIME_TO_WAKE_UP);
            return;
        }

        if(returnToOwnerIfTooFar(me, 3)) {
            return;
        }

        // with flashcards on, the hero is noticed through sneak checks instead of the dice
        boolean sneaking = AnkiStealth.active(me);
        Char enemy = sneaking ? chooseEnemy(me, 0.5f, Dungeon.hero) : chooseEnemy(me, 0.5f);
        me.setEnemy(enemy);

        if (me.isEnemyInFov() ){
            huntEnemy(me);
            me.spend(Mob.TIME_TO_WAKE_UP);
        } else {
            me.enemySeen = false;
            if (sneaking) {
                AnkiStealth.sneakTick(me, 0.5f, this);
            }
        }
    }

    /** Wakes the mob up to hunt {@code enemy} (a failed sneak check). */
    public void wake(@NotNull Char me, @NotNull Char enemy) {
        me.setEnemy(enemy);
        huntEnemy(me);
    }

    @Override
    public void gotDamage(Char me, NamedEntityKind src, int dmg) {
        if (!me.hasBuff(BuffFactory.ANESTHESIA)) {
            seekRevenge(me, src);
        }
    }

    @Override
    public String status(Char me) {
        return Utils.format(R.string.Mob_StaSleepingStatus,
                me.getName());
    }
}
