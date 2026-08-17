package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.EntityIdentifierUtil;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires {@code KILL_ENTITY} for the fallback runtime, targeted at the dead entity's id and credited
 * to the player who dealt the killing blow.
 *
 * <p><b>This producer always runs.</b> There is no claim and no stand-down: nothing may register a
 * competing producer for the same native event, so a kill is dispatched here exactly once.
 *
 * <p>Death is the moment, not a hit, so this hangs off the death component rather than the damage
 * pipeline. Player deaths are skipped (this is the "something was killed" moment, and a player is
 * not one of them), and a kill nothing player-shaped caused credits nobody.
 */
public final class ZigMobKillProducer extends DeathSystems.OnDeathSystem {

    /** The objective kind this producer feeds. */
    public static final String KIND = "KILL_ENTITY";

    /** What a victim whose id will not resolve is called, so a match-all objective still counts. */
    private static final String UNKNOWN_VICTIM = "unknown";

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        // Anything that can die carries the death component; players are excluded below.
        return Query.any();
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> victimRef, @Nonnull DeathComponent death,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            if (store.getComponent(victimRef, Player.getComponentType()) != null) {
                return;
            }
            Damage deathInfo = death.getDeathInfo();
            if (deathInfo == null) {
                return;
            }
            Ref<EntityStore> attackerRef = resolveAttackerRef(deathInfo);
            if (attackerRef == null) {
                return;
            }
            PlayerRef playerRef = store.getComponent(attackerRef, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }
            // An unreadable id still credits a kill: content authored as "kill anything" matches
            // on a blank target, so returning here would quietly cost the player that objective
            // rather than merely naming the victim vaguely.
            String victimId = EntityIdentifierUtil.getMobId(store, victimRef);
            if (victimId == null || victimId.isBlank()) {
                victimId = UNKNOWN_VICTIM;
            }
            ProgressDispatch.fire(store, attackerRef, playerRef, KIND, victimId, null, 1L);
        } catch (Throwable t) {
            SafeLog.warn("[progression] kill dispatch failed", t);
        }
    }

    /**
     * Who caused the death: the source entity for a melee hit, the shooter for a projectile. One
     * test covers both, because the engine's projectile source IS an entity source - it carries the
     * shooter's ref, not the arrow's. An environmental or command death has no attacker and credits
     * nobody.
     */
    @Nullable
    private static Ref<EntityStore> resolveAttackerRef(@Nonnull Damage damage) {
        Damage.Source source = damage.getSource();
        return source instanceof Damage.EntitySource entitySource ? entitySource.getRef() : null;
    }
}
