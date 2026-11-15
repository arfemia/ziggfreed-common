package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires {@code TAKE_FALL_DAMAGE} for a fall that hurts a player, blank Target, Amount the health it took: the one
 * producer of the kind, so a fall counts once for every reader of it.
 *
 * <p>It reads the hit in the Inspect group, after the engine has applied it (every resistance, a consumer's own
 * included, already off it, and the amount rounded), so the amount is what the player's health lost. A cancelled
 * fall, one on an invulnerable player and one that lands for nothing fire nothing.
 */
public final class ZigFallDamageProducer extends DamageEventSystem {

    /** The objective kind this producer feeds. */
    public static final String KIND = "TAKE_FALL_DAMAGE";

    /** The engine's fall damage cause. */
    static final String FALL_CAUSE = "Fall";

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
        if (damage.isCancelled()) {
            return;
        }
        try {
            long landed = landedPoints(damage.getAmount());
            if (landed <= 0L || !isFall(damage)) {
                return;
            }
            Ref<EntityStore> victim = chunk.getReferenceTo(index);
            if (store.getComponent(victim, Invulnerable.getComponentType()) != null) {
                return;
            }
            ProgressDispatch.fire(store, victim, commandBuffer, KIND, "", null, landed, null);
        } catch (Throwable t) {
            SafeLog.warn("[progression] fall-damage dispatch failed", t);
        }
    }

    /** The whole health points an applied amount took, never below 0. Pure. */
    static long landedPoints(float amount) {
        return Float.isFinite(amount) ? Math.max(0L, Math.round(amount)) : 0L;
    }

    private static boolean isFall(@Nonnull Damage damage) {
        DamageCause cause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
        return cause != null && FALL_CAUSE.equalsIgnoreCase(cause.getId());
    }
}
