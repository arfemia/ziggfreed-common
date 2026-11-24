package com.ziggfreed.common.pvp;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.damage.pvp.PvpHits;
import com.ziggfreed.common.util.SafeLog;

/**
 * The PvP ledger's two engine hooks ({@link PvpLedger}): every hit one player lands on another, as applied, and every
 * player's death and return.
 */
public final class PvpSystems {

    private PvpSystems() {
    }

    /**
     * Who struck whom: in the Inspect damage group, so the hit is the one the engine applied (its PvP switch, spawn
     * protection, a safe zone and every cut already behind it; a cancelled hit never arrives), each hit a player
     * landed on another player goes into the victim's tally, kept as long as the world's rules can still credit it.
     */
    public static final class Struck extends DamageEventSystem {

        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getInspectDamageGroup();
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            try {
                if (damage.isCancelled() || !(damage.getAmount() > 0f)) {
                    return;
                }
                Ref<EntityStore> victim = chunk.getReferenceTo(index);
                Ref<EntityStore> attacker = PvpHits.playerAttacker(store, damage, victim);
                if (attacker == null) {
                    return;
                }
                UUID victimId = PvpLedger.idOf(store, victim);
                UUID attackerId = PvpLedger.idOf(store, attacker);
                if (victimId == null || attackerId == null) {
                    return;
                }
                PvpRules.Credit credit = PvpWorldRules.forWorld(store.getExternalData().getWorld()).credit();
                long keep = Math.max(credit.assistMillis(), credit.environmentMillis());
                PvpLedger.struck(victimId, attackerId, damage.getAmount(), damage, System.currentTimeMillis(), keep);
            } catch (Throwable t) {
                SafeLog.fine("[pvp] a player's hit could not be noted: " + t.getMessage());
            }
        }
    }

    /**
     * A player's death and return: the death credits whoever the rules say ({@link PvpLedger#died}), once per death
     * (the engine adds its death component once), and the return starts the fresh-spawn window.
     */
    public static final class Died extends DeathSystems.OnDeathSystem {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                PvpLedger.died(store, ref, death.getDeathInfo(), System.currentTimeMillis());
            } catch (Throwable t) {
                SafeLog.warn("[pvp] a player's death could not be credited", t);
            }
        }

        @Override
        public void onComponentRemoved(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                UUID id = PvpLedger.idOf(store, ref);
                if (id != null) {
                    PvpLedger.respawned(id, System.currentTimeMillis());
                }
            } catch (Throwable t) {
                SafeLog.fine("[pvp] a player's return could not be noted: " + t.getMessage());
            }
        }
    }
}
