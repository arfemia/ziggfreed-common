package com.ziggfreed.common.stats.damage;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.CommonLog;

/**
 * Applies {@link TypedDamageBonuses} to every hit an entity deals: the attacker's summed per-type bonus
 * stats for the hit's cause, as {@code (amount + flat) * (1 + percent / 100)}. In the Filter damage
 * group, so it sees every hit before it lands; a consumer whose own damage fold must come first orders
 * itself {@code BEFORE} this class. A projectile hit counts its shooter's stats.
 */
public final class TypedDamageBonusSystem extends DamageEventSystem {

    @Nonnull
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
        try {
            if (damage.isCancelled() || damage.getAmount() <= 0f) {
                return;
            }
            if (!(damage.getSource() instanceof Damage.EntitySource source)) {
                return;
            }
            Ref<EntityStore> attacker = source.getRef();
            if (attacker == null || !attacker.isValid()) {
                return;
            }
            DamageCause cause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
            if (cause == null) {
                return;
            }
            TypedDamageBonuses.Sums sums = TypedDamageBonuses.sumsFor(store, attacker, cause.getId(), List.of());
            if (sums.isZero()) {
                return;
            }
            damage.setAmount((float) TypedDamageBonuses.apply(damage.getAmount(), sums.percentPoints(), sums.flat()));
        } catch (Throwable t) {
            try {
                CommonLog.LOGGER.atWarning().log("[ziggfreed-common][damage-bonus] hit failed: " + t.getMessage());
            } catch (Throwable ignored) {
                // log-manager-less unit JVM
            }
        }
    }
}
