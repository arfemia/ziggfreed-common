package com.ziggfreed.common.encounter.system;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.util.SafeLog;

/**
 * The ledger's eyes: an observe-only system on the engine's INSPECT damage group, the phase where a
 * damage value is final (every filter that mutates or cancels has already run). It never changes
 * the damage. A hit on a bound subject credits the attacker's player (a non-player attacker through
 * the attribution seam); a hit on a member credits damage taken.
 *
 * <p>The miss path, which is every hit on a server with no fight on, is two lookups on two empty
 * maps and allocates nothing.
 */
public final class EncounterDamageSystem extends DamageEventSystem {

    @Nonnull
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
        try {
            if (damage.isCancelled()) {
                return;
            }
            float amount = damage.getAmount();
            if (!(amount > 0.0F)) {
                return;
            }
            Ref<EntityStore> victim = archetypeChunk.getReferenceTo(index);
            UUID subjectRun = EncounterRuns.runOfSubject(victim);
            if (subjectRun != null) {
                creditDealt(store, subjectRun, damage, amount);
                return;
            }
            UUID memberRun = EncounterRuns.runOfMember(victim);
            if (memberRun != null) {
                creditTaken(store, memberRun, victim, amount);
            }
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " damage bookkeeping failed", t);
        }
    }

    private static void creditDealt(@Nonnull Store<EntityStore> store, @Nonnull UUID runId, @Nonnull Damage damage,
            float amount) {
        Ref<EntityStore> attacker = EncounterDeathSystem.attackerOf(damage);
        Ref<EntityStore> credited = attacker == null ? null : EncounterDeathSystem.creditedPlayer(store, attacker);
        PlayerRef player = credited == null ? null : store.getComponent(credited, PlayerRef.getComponentType());
        UUID playerId = player == null ? null : player.getUuid();
        if (playerId == null) {
            return;
        }
        String name = player.getUsername();
        EncounterRuns.LEDGER.creditDamageDealt(runId, playerId, name, amount);
        EncounterRuns.Live live = EncounterRuns.live(runId);
        if (live != null) {
            live.run().noteHitter(playerId);
            live.run().noteMember(playerId, name);
        }
    }

    private static void creditTaken(@Nonnull Store<EntityStore> store, @Nonnull UUID runId,
            @Nonnull Ref<EntityStore> victim, float amount) {
        PlayerRef player = store.getComponent(victim, PlayerRef.getComponentType());
        UUID playerId = player == null ? null : player.getUuid();
        if (playerId == null) {
            return;
        }
        EncounterRuns.LEDGER.creditDamageTaken(runId, playerId, player.getUsername(), amount);
    }
}
