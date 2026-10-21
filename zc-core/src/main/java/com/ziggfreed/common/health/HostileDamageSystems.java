package com.ziggfreed.common.health;

import java.util.UUID;

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
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The two systems behind {@link RecentHostileDamage}: a creature's hit on a player fills that player's pool once it
 * has landed, and a player's death empties it. Registered once, by {@link HealCreditBootstrap} (the system registry
 * is class-keyed).
 */
public final class HostileDamageSystems {

    private HostileDamageSystems() {
    }

    /**
     * A hit on a player, in the Inspect group: the engine has applied it (its amount the health it took, whole), so a
     * cancelled hit, the Forgotten Temple's included, an empty one or one on an invulnerable player changes nothing.
     * Every other hit first holds the player's pool to the health they were missing before it; only a creature's
     * ({@link RecentHostileDamage#fromCreature}) then adds to it.
     */
    public static final class Taken extends DamageEventSystem {

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
            if (damage.isCancelled() || !(damage.getAmount() > 0f)) {
                return;
            }
            try {
                Ref<EntityStore> victim = chunk.getReferenceTo(index);
                PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
                if (player == null || store.getComponent(victim, Invulnerable.getComponentType()) != null) {
                    return;
                }
                UUID id = player.getUuid();
                boolean creature = RecentHostileDamage.fromCreature(store, damage, victim);
                boolean holds = RecentHostileDamage.holds(id);
                if (!creature && !holds) {
                    return;
                }
                // Any hit first holds the pool to what the player was missing before it (one health read, and only
                // while there is a pool to hold), so a hit at full health leaves no healed damage creditable.
                double missing = holds ? RecentHostileDamage.missingBefore(HealthUtil.current(store, victim),
                        HealthUtil.max(store, victim), damage.getAmount()) : Double.NaN;
                RecentHostileDamage.landed(id, missing, creature ? damage.getAmount() : 0.0);
            } catch (Throwable t) {
                SafeLog.fine("[health] a creature's hit could not be noted: " + t.getMessage());
            }
        }
    }

    /** A player died: whatever creatures took from them is no longer health a heal could give back. */
    public static final class Died extends DeathSystems.OnDeathSystem {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
                if (player != null) {
                    RecentHostileDamage.forget(player.getUuid());
                }
            } catch (Throwable t) {
                SafeLog.fine("[health] a dead player's recent damage could not be forgotten: " + t.getMessage());
            }
        }
    }
}
