package com.ziggfreed.common.proc;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;

/**
 * What marks a hit as a proc's own, so it never counts as a Hit, a HitTaken or any other moment about a hit for a
 * proc, and no content can build a proc that sets itself off: damage an effect a proc put on deals (the engine's
 * {@code EntityEffectSource} names the one applied instance, held here weakly, so a removed effect's mark goes with
 * it), and damage a chain a proc ran deals (every such chain runs as {@link ProcActions#CHAIN_TYPE}, and the engine's
 * damage interaction stamps its chain's type on each hit, {@code Damage.INTERACTION_TYPE}). An effect a proc
 * re-applies keeps its one instance, so it stays marked; one a weapon re-applies over a proc's is the same instance
 * too, and its ticks stay marked until it ends. Any thread.
 */
final class ProcMarks {

    private static final Set<ActiveEntityEffect> APPLIED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private ProcMarks() {
    }

    /** Note that a proc put {@code effect} on. */
    static void markApplied(@Nullable ActiveEntityEffect effect) {
        if (effect != null) {
            APPLIED.add(effect);
        }
    }

    /** Is {@code damage} a proc's own: an applied effect's tick, or a hit of a chain a proc ran? Never throws. */
    static boolean fromProc(@Nullable Damage damage) {
        if (damage == null) {
            return false;
        }
        try {
            if (damage.getSource() instanceof Damage.EntityEffectSource source && source.getEffect() != null
                    && APPLIED.contains(source.getEffect())) {
                return true;
            }
            InteractionType type = damage.getIfPresentMetaObject(Damage.INTERACTION_TYPE);
            return type == ProcActions.CHAIN_TYPE;
        } catch (Throwable t) {
            return false;
        }
    }
}
