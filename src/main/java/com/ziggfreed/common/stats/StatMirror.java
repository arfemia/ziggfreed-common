package com.ziggfreed.common.stats;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Idempotent keyed put/remove of a SINGLE native additive {@code MAX} {@link StaticModifier} -
 * the generic "mirror a derived scalar (a skill level, a purchased multiplier) onto a native
 * {@link EntityStatMap} channel" primitive. A consumer writes its derived value under its own
 * key on every path that can change it (level-up, respec, admin set, PlayerReady hydrate) so the
 * native map stays the single aggregation authority a `resolve*`-style reader can fold with zero
 * at-use lookups.
 *
 * <p>{@link #set} is idempotent by construction: it re-derives the {@link StaticModifier} it
 * would write and skips the actual {@code putModifier} call when an equal one is already present
 * (native {@link StaticModifier#equals}), so it is safe to call unconditionally on a hot path
 * without spamming redundant writes. World-thread, try-guarded; a missing stat map / unresolved
 * channel is a silent no-op.
 */
public final class StatMirror {

    private StatMirror() {
    }

    /**
     * Write-or-replace an additive {@code MAX} modifier of {@code value} under {@code key} on
     * {@code statId}. No-op when the stat is unregistered, the entity has no stat map, or an
     * equal modifier is already present under {@code key}.
     */
    public static void set(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId, @Nonnull String key, double value) {
        int idx = StatIndexCache.resolve(statId);
        if (idx == StatIndexCache.UNRESOLVED) {
            return;
        }
        EntityStatMap stats = store.getComponent(ref, EntityStatsModule.get().getEntityStatMapComponentType());
        if (stats == null) {
            return;
        }
        try {
            Float toWrite = decideOrSkip(stats.getModifier(idx, key), (float) value);
            if (toWrite == null) {
                return;
            }
            stats.putModifier(idx, key, new StaticModifier(Modifier.ModifierTarget.MAX,
                    StaticModifier.CalculationType.ADDITIVE, toWrite));
        } catch (Throwable t) {
            warn("set", t);
        }
    }

    /** Remove the EXACT-key modifier from {@code statId}. Safe if absent / stat unregistered. */
    public static void remove(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String statId, @Nonnull String key) {
        int idx = StatIndexCache.resolve(statId);
        if (idx == StatIndexCache.UNRESOLVED) {
            return;
        }
        EntityStatMap stats = store.getComponent(ref, EntityStatsModule.get().getEntityStatMapComponentType());
        if (stats == null) {
            return;
        }
        try {
            stats.removeModifier(idx, key);
        } catch (Throwable ignored) {
        }
    }

    /**
     * PURE decision core (package-private, unit-testable without a live {@link EntityStatMap}):
     * given whatever modifier is currently present under the target key (or {@code null}) and
     * the amount {@link #set} would write, returns {@code null} when nothing needs to change
     * (an equal additive-MAX {@link StaticModifier} is already present) or the amount to write.
     */
    @Nullable
    static Float decideOrSkip(@Nullable Modifier existing, float newAmount) {
        if (existing instanceof StaticModifier sm
                && sm.getCalculationType() == StaticModifier.CalculationType.ADDITIVE
                && sm.getTarget() == Modifier.ModifierTarget.MAX
                && sm.getAmount() == newAmount) {
            return null;
        }
        return newAmount;
    }

    private static void warn(@Nonnull String label, @Nonnull Throwable t) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("StatMirror." + label + " failed: " + t.getMessage());
        } catch (Throwable ignored) {
        }
    }
}
