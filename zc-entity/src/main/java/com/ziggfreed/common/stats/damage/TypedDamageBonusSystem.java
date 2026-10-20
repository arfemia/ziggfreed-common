package com.ziggfreed.common.stats.damage;

import java.util.List;
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
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSourceType;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The per-hit step of {@link TypedDamageBonuses}, for a player's hits. Two things join the engine's own
 * outgoing sum here ({@link NativeOutgoing#addToSum}), so they add to Enrage and every native percent line
 * rather than multiplying on top of them: the route sources a registered {@link TypedDamageBonuses.Route}
 * names (a tagged bomb's burst, a thrown spear) with every registered {@link TypedDamageBonuses.RouteTerm}'s percent
 * (the conditional stats, read against this hit's target), and on a DIRECT hit every source's flat stat
 * ({@link TypedDamageBonuses#flatFor}), as {@code (a + flat) * (1 + P)}. A damage-over-time tick
 * ({@code DamageSourceType.DoT}) takes no flat. The every-hit percent lines are not here: the engine
 * applies those itself, from the native effects zc-effects' {@code DamageBonusReconciler} keeps in step.
 *
 * <p>In the Filter damage group, after the engine's outgoing step; a consumer whose own fold must see
 * these in the hit's sum orders itself {@code AFTER} this class. A projectile hit counts its shooter's
 * stats. A hit by a non-player returns at once; a player's hit costs one stat read per flat and route
 * source, a gate or route asked only of a nonzero one.
 */
public final class TypedDamageBonusSystem extends DamageEventSystem {

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            // DEPRECATION-KEPT: the engine's outgoing step is the only applier of effect OutgoingDamage in both lines (no successor yet); this step must run after it to add into its sum.
            new SystemDependency<>(Order.AFTER, DamageSystems.ScaleOutgoingDamageFromEntityEffects.class));

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
            if (damage.isCancelled() || damage.getAmount() <= 0f) {
                return;
            }
            if (!(damage.getSource() instanceof Damage.EntitySource source)) {
                return;
            }
            Ref<EntityStore> attacker = source.getRef();
            if (attacker == null || !attacker.isValid()
                    || store.getComponent(attacker, PlayerRef.getComponentType()) == null) {
                return; // the lines are kept for players, as the native ones are
            }
            DamageCause cause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
            if (cause == null) {
                return;
            }
            // A flat line lands on a direct hit, never on a damage-over-time tick (a burn's one-point
            // tick would otherwise take a weapon's whole flat each tick).
            boolean directHit = source.getSourceType() != DamageSourceType.DoT;
            TypedDamageBonuses.Sums route = TypedDamageBonuses.routeSumsFor(store, attacker,
                    chunk.getReferenceTo(index), damage, cause.getId());
            double flat = directHit ? TypedDamageBonuses.flatFor(store, attacker, cause.getId()) + route.flat() : 0.0;
            double percentPoints = route.percentPoints();
            if (percentPoints == 0.0 && flat == 0.0) {
                return;
            }
            EntityStatMap stats = store.getComponent(attacker, EntityStatMap.getComponentType());
            if (stats != null && percentPoints != 0.0) {
                double nativePoints = TypedDamageBonuses.sumsFor(store, attacker, cause.getId(), List.of()).percentPoints();
                percentPoints = TypedDamageBonuses.heldToCeiling(nativePoints, percentPoints,
                        TypedDamageBonuses.ceilingPercent(stats));
            }
            NativeOutgoing.addToSum(damage, store, attacker, percentPoints / 100.0, flat);
        } catch (Throwable t) {
            SafeLog.warn("[damage-bonus] route step failed: " + t.getMessage());
        }
    }
}
