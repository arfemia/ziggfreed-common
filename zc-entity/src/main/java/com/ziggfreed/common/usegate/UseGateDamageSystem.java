package com.ziggfreed.common.usegate;

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
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSourceType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.stats.damage.TypedDamageBonusSystem;
import com.ziggfreed.common.util.EntityIdentifierUtil;
import com.ziggfreed.common.util.SafeLog;

/**
 * Refuses a hit a use gate refuses: the creature's own gates (by NPC role) and the attacker's held item's
 * ({@link UseGates}), for a hit a player deals, a projectile they shot included. A refused hit is CANCELLED, so it
 * takes no health, shows no number and counts as a miss to the swing; every later damage step and every consumer
 * fold that checks {@code isCancelled} (as each one should) skips it. In the Filter group, ahead of the library's
 * per-type bonus step; a consumer fold that must not see a refused hit orders itself AFTER this class.
 *
 * <p>An effect's own damage-over-time tick ({@link DamageSourceType#DoT}) is never asked: its hit was asked when
 * the effect landed. The player is told why through {@link UseGates#announce}.
 */
public final class UseGateDamageSystem extends DamageEventSystem {

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, TypedDamageBonusSystem.class));

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
            if (damage.isCancelled() || UseGates.nothingToAsk()) {
                return;
            }
            if (!(damage.getSource() instanceof Damage.EntitySource source)
                    || source.getSourceType() == DamageSourceType.DoT) {
                return;
            }
            Ref<EntityStore> attacker = source.getRef();
            if (attacker == null || !attacker.isValid()
                    || store.getComponent(attacker, PlayerRef.getComponentType()) == null) {
                return;
            }
            Ref<EntityStore> target = chunk.getReferenceTo(index);
            // Only a creature has a Mob axis: the role name, null for a player or anything no role drives.
            String mobId = target.isValid() ? EntityIdentifierUtil.roleName(store, target) : null;
            UseGateRefusal refusal = UseGates.hitRefusal(store, attacker,
                    PlayerAccess.activeHotbarItem(store, attacker), target, mobId);
            if (refusal == null) {
                return;
            }
            damage.setCancelled(true);
            UseGates.announce(store, attacker, refusal);
        } catch (Throwable t) {
            SafeLog.warn("[usegate] a hit could not be asked: " + t.getMessage());
        }
    }
}
