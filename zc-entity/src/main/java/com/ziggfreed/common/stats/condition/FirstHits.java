package com.ziggfreed.common.stats.condition;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Which players have hit which targets, for a conditional stat's {@code Hit.FirstHit}: the engine keeps no such flag,
 * so this is a transient, single-process table of each target's attackers (players only), filled after every
 * player's direct hit lands and dropped for a target the moment it leaves its store (its death, an unload), so a
 * creature met again later is a first hit again. Nothing is saved.
 *
 * <p>An attacker is known by its entity's UUID ({@link UUIDComponent}), never its {@code Ref}: the store re-binds a
 * ref object to a new entity once the old one is gone, so a ref kept after its player left could name someone else.
 * A target is keyed by its ref, which is safe because the table drops it in the same removal that frees the ref
 * ({@code ConditionalStatSystems.Left}).
 */
public final class FirstHits {

    /** Each target's attackers so far, by their entity UUID. */
    private static final Map<Ref<EntityStore>, Set<UUID>> HIT_BY = new ConcurrentHashMap<>();

    private FirstHits() {
    }

    /** The entity's stable id, or null when it has none (not a stored entity, or a test's bare ref). Never throws. */
    @Nullable
    public static UUID idOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        try {
            UUIDComponent id = accessor.getComponent(ref, UUIDComponent.getComponentType());
            return id == null ? null : id.getUuid();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Has the attacker {@code attacker} (its entity UUID) hit {@code target} before (since the target appeared)? */
    public static boolean hitBefore(@Nullable UUID attacker, @Nullable Ref<EntityStore> target) {
        if (attacker == null || target == null) {
            return false;
        }
        Set<UUID> attackers = HIT_BY.get(target);
        return attackers != null && attackers.contains(attacker);
    }

    /** Note that the attacker {@code attacker} (its entity UUID) has hit {@code target}. */
    public static void record(@Nonnull UUID attacker, @Nonnull Ref<EntityStore> target) {
        HIT_BY.computeIfAbsent(target, t -> ConcurrentHashMap.newKeySet()).add(attacker);
    }

    /** Forget {@code ref} as a target (it left its store). */
    public static void forget(@Nonnull Ref<EntityStore> ref) {
        HIT_BY.remove(ref);
    }

    /** How many targets are tracked now (a test, a leak check). */
    public static int size() {
        return HIT_BY.size();
    }

    /** Forget everything (a test). */
    public static void clear() {
        HIT_BY.clear();
    }
}
