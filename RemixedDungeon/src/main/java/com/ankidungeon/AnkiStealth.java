package com.ankidungeon;

import com.nyrds.pixeldungeon.ai.Sleeping;
import com.nyrds.pixeldungeon.game.GameLoop;
import com.watabou.pixeldungeon.Dungeon;
import com.watabou.pixeldungeon.actors.Char;
import com.watabou.pixeldungeon.actors.hero.Hero;
import com.watabou.pixeldungeon.actors.mobs.Mob;
import com.watabou.pixeldungeon.sprites.CharSprite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sneaking past sleeping mobs. Each turn the hero is in view of a sleeping mob
 * adds the vanilla wake-up chance to that mob's tension; when it fills up the
 * hero gets a card. A pass keeps the mob asleep, a miss wakes it.
 */
public final class AnkiStealth {

    private static final Map<Char, Float> tension = new WeakHashMap<>();
    private static final List<Char> participants = new ArrayList<>();
    private static boolean checkPending;

    private AnkiStealth() {
    }

    /** Whether the hero should be left out of this sleeping mob's vanilla notice roll. */
    public static boolean active(Char me) {
        Hero hero = Dungeon.hero;
        return hero != null && me instanceof Mob && hero.invisible <= 0 && !me.friendly(hero)
                && AnkiCheck.active(CheckKind.SNEAK);
    }

    public static void sneakTick(Char me, float attentionFactor, Sleeping state) {
        Hero hero = Dungeon.hero;
        if (!me.level().fieldOfView[hero.getPos()]) {
            return;
        }

        float attention = attentionFactor * me.getAttentionFactor() * GameLoop.getDifficultyFactor();
        int distance = me.level().distance(me.getPos(), hero.getPos());
        int odds = (int) ((distance + hero.stealth()) / attention + (hero.isFlying() ? 2 : 0));
        float chance = odds <= 1 ? 1f : 1f / odds;

        float value = tension.getOrDefault(me, 0f) + chance;
        tension.put(me, value);

        if (value < 1f) {
            return;
        }
        if (!participants.contains(me)) {
            participants.add(me);
        }
        if (checkPending) {
            return;
        }
        checkPending = true;
        AnkiCheck.request(CheckKind.SNEAK, result -> resolve(result, state));
    }

    private static void resolve(CheckResult result, Sleeping state) {
        Hero hero = Dungeon.hero;
        for (Char mob : participants) {
            if (!mob.isAlive() || !(mob instanceof Mob) || !(((Mob) mob).getState() instanceof Sleeping)) {
                tension.remove(mob);
                continue;
            }
            if (result.skipped) {
                tension.put(mob, 0f);
            } else if (result.correct) {
                // a little grace before the next card from the same mob
                tension.put(mob, -1f);
            } else {
                tension.remove(mob);
                state.wake(mob, hero);
            }
        }
        if (!result.skipped) {
            hero.showStatus(result.correct ? CharSprite.POSITIVE : CharSprite.NEGATIVE,
                    result.correct ? "Unnoticed" : "Spotted!");
        }
        participants.clear();
        checkPending = false;
    }
}
