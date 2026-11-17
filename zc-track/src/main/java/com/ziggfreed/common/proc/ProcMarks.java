package com.ziggfreed.common.proc;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;

/**
 * What marks a hit as a proc's own, so it never counts as a Hit, a HitTaken or any other moment about a hit for a
 * proc, and no content can build a proc that sets itself off: damage an effect a proc put on deals (the engine's
 * {@code EntityEffectSource} names the one applied instance, held here weakly, so a removed effect's mark goes with
 * it), and damage a chain a proc ran deals (every such chain runs as {@link ProcActions#CHAIN_TYPE}, and the engine's
 * damage interaction stamps its chain's type on each hit, {@code Damage.INTERACTION_TYPE}). An effect a proc
 * re-applies keeps its one instance, so it stays marked; one a weapon re-applies over a proc's is the same instance
 * too, and its ticks stay marked until it ends. A {@code Strike}'s own hit carries one more mark ({@link #struck}), so
 * the kill it lands is no Kill moment either; a kill any other of these lands still is one. Any thread.
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

    /** The mark a proc's {@code Strike} puts on the hit it deals. */
    private static final class Meta {
        static final MetaKey<Boolean> STRIKE = Damage.META_REGISTRY.registerMetaObject();
    }

    /** Mark {@code damage} as a {@code Strike}'s own hit, so a kill it lands is no Kill moment. Never throws. */
    static void markStrike(@Nullable Damage damage) {
        if (damage == null) {
            return;
        }
        try {
            damage.putMetaObject(Meta.STRIKE, Boolean.TRUE);
        } catch (Throwable t) {
            // An unmarked strike only means its kill can feed a Kill proc.
        }
    }

    /**
     * Is {@code damage} a hit a proc's {@code Strike} dealt? A kill such a hit lands is no Kill moment
     * ({@code ProcMoments}), so a strike carries nothing further; a kill any other proc-dealt hit lands (a proc's own
     * chain, an effect a proc put on) still is one, as it always was. Never throws.
     */
    static boolean struck(@Nullable Damage damage) {
        if (damage == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(damage.getIfPresentMetaObject(Meta.STRIKE));
        } catch (Throwable t) {
            return false;
        }
    }
}
