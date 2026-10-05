package com.ziggfreed.common.npc.placement.runtime;

import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.systems.BalancingInitialisationSystem;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * Applies a placement's {@code Fortify} bonus as a placed NPC enters the store, on a fresh spawn and on a
 * load alike, the way the engine's own {@code BalancingInitialisationSystem} applies a role's health.
 *
 * <p><b>Why at the add.</b> On Update 7 the engine parks an NPC added into a chunk section that is not
 * ticking, and a parked add never runs its post-spawn, where {@code NpcPlacementService.place} used to
 * apply the bonus; a copy that comes back from a park comes back as a load with no bonus. The spawn's
 * {@code preAddToWorld} hook cannot carry it either: the holder has no stat map yet. A holder system
 * ordered after the engine's stat setup and NPC balancing runs on every add, before anything could park
 * the entity, and the modifier is saved with the entity, so every copy carries it.
 * {@code NpcPlacementService.applyFortify} fills the enlarged pool on a fresh spawn or when the bonus was
 * missing, and leaves a copy that already had it at its current health.
 *
 * <p>Runs inside the store's add, so it edits only the holder it is handed: never a store call, a wake,
 * a pin or a ledger write. Never throws.
 */
public final class PlacementFortifySystem extends HolderSystem<EntityStore> {

    @Nonnull
    private final ComponentType<EntityStore, PlacedNpcComponent> placedType;
    @Nonnull
    private final ComponentType<EntityStore, EntityStatMap> statsType;
    @Nonnull
    private final Query<EntityStore> query;
    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, EntityStatsSystems.Setup.class),
            new SystemDependency<>(Order.AFTER, BalancingInitialisationSystem.class));

    /** @param placedType the registered {@link PlacedNpcComponent} type */
    public PlacementFortifySystem(@Nonnull ComponentType<EntityStore, PlacedNpcComponent> placedType) {
        this.placedType = placedType;
        this.statsType = EntityStatsModule.get().getEntityStatMapComponentType();
        this.query = Query.and(placedType, statsType);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void onEntityAdd(@Nonnull Holder<EntityStore> holder, @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store) {
        try {
            PlacedNpcComponent placed = holder.getComponent(placedType);
            EntityStatMap stats = holder.getComponent(statsType);
            if (placed == null || stats == null) {
                return;
            }
            PlacedNpcIdentity identity = placed.toIdentity();
            if (identity.isUnknown()) {
                return;
            }
            NpcPlacementAsset placement = NpcPlacementConfig.getInstance().resolve(identity.placementId());
            NpcPlacementAsset.Lifecycle lifecycle = placement == null ? null : placement.getLifecycle();
            if (lifecycle == null || !lifecycle.effectiveFortify()) {
                return;
            }
            NpcPlacementService.applyFortify(stats, lifecycle.effectiveFortifyHealth(), reason == AddReason.SPAWN);
        } catch (Throwable t) {
            SafeLog.fine("[placement] could not fortify a placed NPC as it was added: " + t.getMessage());
        }
    }

    @Override
    public void onEntityRemoved(@Nonnull Holder<EntityStore> holder, @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store) {
    }
}
