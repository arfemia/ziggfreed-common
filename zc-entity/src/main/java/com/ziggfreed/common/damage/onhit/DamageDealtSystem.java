package com.ziggfreed.common.damage.onhit;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.condition.Nearby;
import com.ziggfreed.common.stats.damage.NativeOutgoing;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.util.SafeLog;

/**
 * The true share step ({@link DamageDealt}): in the Filter damage group, after the crit step ({@link CritSystem}, itself
 * after the weak-spot step and the route step), so it multiplies the whole hit as every bonus left it. Any attacker,
 * a creature as much as a player (R860), any damage type, a damage-over-time tick credited to its owner and a
 * projectile's hit counted on its shooter; never a hit on oneself. The factor is recorded with
 * {@link NativeOutgoing#scaleHit}, so a flat a later step adds lands at it too (R992).
 *
 * <p><b>Cost.</b> A hit by an attacker whose stat is 0 (every one until something lowers it) pays one stat read and
 * returns.
 */
public final class DamageDealtSystem extends DamageEventSystem {

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, CritSystem.class));

    @Nonnull
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
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
            if (damage.isCancelled() || !(damage.getAmount() > 0f)
                    || !(damage.getSource() instanceof Damage.EntitySource source)) {
                return;
            }
            Ref<EntityStore> attacker = source.getRef();
            if (attacker == null || !attacker.isValid()) {
                return;
            }
            EntityStatMap stats = store.getComponent(attacker, EntityStatMap.getComponentType());
            if (stats == null) {
                return;
            }
            double points = DamageDealt.points(store, attacker, damage, stats);
            if (points == 0.0) {
                return;
            }
            Ref<EntityStore> target = chunk.getReferenceTo(index);
            if (Nearby.same(attacker, target) || !TypedDamageBonuses.attackerAllowed(store, attacker)) {
                return;
            }
            double factor = DamageDealt.factor(points, DamageDealt.ceiling());
            if (factor == 1.0) {
                return;
            }
            damage.setAmount((float) (damage.getAmount() * factor));
            NativeOutgoing.scaleHit(damage, factor);
        } catch (Throwable t) {
            SafeLog.warn("[damage dealt] the true share step failed: " + t.getMessage());
        }
    }
}
