package com.ziggfreed.common.encounter.system;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.seam.EncounterSeams;
import com.ziggfreed.common.util.SafeLog;

/**
 * The precise instant a fight is decided: the bound subject's death component lands, well before
 * the corpse is removed and the script's own target-gone transition fires. Latched once per run
 * through the lifecycle. A member's death is recorded in the ledger on the same hook.
 *
 * <p>Both reads are one lookup on an index that is empty whenever no fight is on.
 */
public final class EncounterDeathSystem extends DeathSystems.OnDeathSystem {

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> victimRef, @Nonnull DeathComponent death,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            UUID subjectRun = EncounterRuns.runOfSubject(victimRef);
            if (subjectRun != null) {
                onSubjectDied(store, commandBuffer, victimRef, death, subjectRun);
                return;
            }
            UUID memberRun = EncounterRuns.runOfMember(victimRef);
            if (memberRun != null) {
                onMemberDied(store, victimRef, memberRun);
            }
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " death handling failed", t);
        }
    }

    private static void onSubjectDied(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> victimRef, @Nonnull DeathComponent death, @Nonnull UUID runId) {
        EncounterRuns.Live live = EncounterRuns.live(runId);
        if (live == null || live.run().isConcluded()) {
            return;
        }
        Damage info = death.getDeathInfo();
        Ref<EntityStore> attacker = info == null ? null : attackerOf(info);
        Ref<EntityStore> credited = attacker == null ? null : creditedPlayer(store, attacker);
        PlayerRef killer = credited == null ? null : store.getComponent(credited, PlayerRef.getComponentType());
        if (killer != null && killer.getUuid() != null) {
            live.run().noteHitter(killer.getUuid());
            live.run().noteMember(killer.getUuid(), killer.getUsername());
        }
        EncounterLifecycle.defeat(store, commandBuffer, live.encounterRef(), live.run(), live.encounterId(), victimRef,
                "death");
    }

    private static void onMemberDied(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victimRef,
            @Nonnull UUID runId) {
        PlayerRef player = store.getComponent(victimRef, PlayerRef.getComponentType());
        if (player == null || player.getUuid() == null) {
            return;
        }
        EncounterRuns.LEDGER.recordDeath(runId, player.getUuid(), player.getUsername());
        EncounterRuns.Live live = EncounterRuns.live(runId);
        if (live != null) {
            live.run().noteDeath(player.getUuid());
        }
    }

    /** Who caused the damage: the source entity (a projectile's source IS the shooter). */
    @Nullable
    static Ref<EntityStore> attackerOf(@Nonnull Damage damage) {
        Damage.Source source = damage.getSource();
        return source instanceof Damage.EntitySource entitySource ? entitySource.getRef() : null;
    }

    /** The attacker when it is a player, else whoever the attribution seam says it acts for, else null. */
    @Nullable
    static Ref<EntityStore> creditedPlayer(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attacker) {
        if (!attacker.isValid()) {
            return null;
        }
        if (store.getComponent(attacker, PlayerRef.getComponentType()) != null) {
            return attacker;
        }
        Ref<EntityStore> actsFor = EncounterSeams.actsFor(store, attacker);
        if (actsFor == null || !actsFor.isValid()) {
            return null;
        }
        return store.getComponent(actsFor, PlayerRef.getComponentType()) != null ? actsFor : null;
    }
}
