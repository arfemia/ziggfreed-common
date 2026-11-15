package com.ziggfreed.common.stats.condition;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * An entity condition that can also answer for one hit its holder dealt (R945): a {@link Condition} subclass registered
 * into the engine's {@code Condition.CODEC} that implements this says whether it holds for the attacker ON THIS HIT,
 * not only as the attacker stands. A consumer's "while this weapon counts" condition (what trains a skill) answers a
 * hit that earns that skill's credit whatever is in hand when it lands: a damage-over-time tick after a swap, a thrown
 * weapon or a bomb landing after one, a technique cast with another item.
 *
 * <p>Two readers ask it, both through {@link #allHold}: a bound grant's hit term ({@link BoundGrants#hitPoints}: a
 * bound modifier that is off pays on a hit where every one of its conditions holds on that hit), and zc-track's procs,
 * whose {@code Holder} conditions are read on a hit the holder dealt. Every other reading of the condition (the holder
 * pass, an effect's {@code ApplyConditions}) stays {@link Condition#eval}; a condition that does not implement this
 * answers a hit exactly as the holder pass reads it.
 *
 * <p>An implementation applies its own {@code Inverse} as {@link Condition#eval} does ({@code inverse != answer}), so
 * an authored {@code "Inverse": true} turns the hit answer around too. It runs on the world thread inside the damage
 * pipeline, once per hit and bound list it is asked for: keep it a few reads, never a scan.
 */
public interface HitAwareCondition {

    /**
     * Does the condition hold for {@code attacker} on {@code damage}, a hit the attacker dealt (a projectile's shooter,
     * a damage-over-time tick's owner)? The whole answer, its {@code Inverse} applied.
     */
    boolean evalOnHit(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Damage damage, @Nonnull Instant now);

    /** Is any of {@code conditions} hit-aware (so the list can answer a hit differently from the holder pass)? */
    static boolean anyIn(@Nullable Condition[] conditions) {
        if (conditions == null) {
            return false;
        }
        for (Condition condition : conditions) {
            if (condition instanceof HitAwareCondition) {
                return true;
            }
        }
        return false;
    }

    /**
     * Do all of {@code conditions} hold on {@code ref}: each hit-aware one asked on {@code damage}, a hit {@code ref}
     * dealt, every other as the holder pass reads it ({@link Condition#eval})? With no hit ({@code damage} null) this is
     * exactly {@link Nearby#allHold}. True for none; a null entry is passed over; one that throws does not hold.
     */
    static boolean allHold(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nullable Condition[] conditions, @Nullable Damage damage, @Nonnull Instant now) {
        if (damage == null) {
            return Nearby.allHold(accessor, ref, conditions, now);
        }
        if (conditions == null || conditions.length == 0) {
            return true;
        }
        for (Condition condition : conditions) {
            if (condition == null) {
                continue;
            }
            try {
                boolean holds = condition instanceof HitAwareCondition onHit
                        ? onHit.evalOnHit(accessor, ref, damage, now) : condition.eval(accessor, ref, now);
                if (!holds) {
                    return false;
                }
            } catch (Throwable t) {
                SafeLog.fine("[condition] a condition asked on a hit threw; it does not hold: " + t);
                return false;
            }
        }
        return true;
    }
}
