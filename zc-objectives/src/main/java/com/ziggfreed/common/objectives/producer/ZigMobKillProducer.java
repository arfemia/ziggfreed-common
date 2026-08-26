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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.progress.runtime.KillAttribution;
import com.ziggfreed.common.progress.runtime.KillQualifier;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
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
 * not one of them).
 *
 * <p><b>A kill nothing player-shaped landed is asked about before it credits nobody.</b> A turret,
 * a totem, a summoned creature carries ITSELF as the damage source, so the attacker has no
 * {@code PlayerRef}. The composed {@link KillAttribution} - every registered one, first real answer
 * wins - names the player it acts for, and the moment fires for THAT player, credited exactly as if
 * they had landed the blow. Nothing registered, or no answer, and the kill credits nobody, which is
 * what a bare server has always done. The moment carries a {@link MobKillPayload} either way, so a
 * reaction still reaches the victim and the raw attacker.
 *
 * <p><b>The victim is asked about ONCE for a qualifier, at fire time.</b> The composed
 * {@link KillQualifier} - every registered one, first real answer wins, a throwing one skipped
 * with a warn - names what the killed entity carries (e.g. a difficulty tier a companion mod
 * attributes), and the answer is stamped into the ONE primary {@code KILL_ENTITY} dispatch, so a
 * criterion authoring that qualifier matches while an unqualified criterion keeps matching every
 * kill (an empty AUTHORED qualifier reads as "any"). No answer means the kill fires unqualified,
 * byte-identical to a server with nothing registered; there is deliberately no second qualified
 * re-fire, which would count one kill twice for every unqualified criterion.
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
            Ref<EntityStore> credited = creditedPlayer(store, attackerRef);
            if (credited == null) {
                return;
            }
            // An unreadable id still credits a kill: content authored as "kill anything" matches
            // on a blank target, so returning here would quietly cost the player that objective
            // rather than merely naming the victim vaguely.
            String victimId = EntityIdentifierUtil.getMobId(store, victimRef);
            if (victimId == null || victimId.isBlank()) {
                victimId = UNKNOWN_VICTIM;
            }
            // The composed ask is guarded per contribution, so a throwing qualifier costs its own
            // answer and never the kill: the moment still fires, unqualified.
            String qualifier = ProgressionRuntime.killQualifier().qualifierFor(store, victimRef);
            ProgressDispatch.fire(store, credited, commandBuffer, KIND, victimId, qualifier, 1L,
                    new MobKillPayload(victimRef, death));
        } catch (Throwable t) {
            SafeLog.warn("[progression] kill dispatch failed", t);
        }
    }

    /**
     * Who this kill is credited to: the attacker itself when it is a player; otherwise whoever the
     * composed attribution says the attacker acts for, provided THAT is a player; otherwise nobody.
     * The answered entity is checked rather than trusted, because a contribution's "acts for" is
     * an opinion about ownership and the producer's promise is a PLAYER's ref.
     */
    @Nullable
    private static Ref<EntityStore> creditedPlayer(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> attackerRef) {
        if (PlayerAccess.playerRef(store, attackerRef) != null) {
            return attackerRef;
        }
        Ref<EntityStore> actsFor = ProgressionRuntime.killAttribution().actsFor(store, attackerRef);
        if (actsFor == null || !actsFor.isValid()) {
            return null;
        }
        return PlayerAccess.playerRef(store, actsFor) != null ? actsFor : null;
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
