package com.ziggfreed.common.encounter.system;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
import com.hypixel.hytale.server.spawning.SpawnLineage;
import com.ziggfreed.common.damage.HitTags;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.ledger.EncounterFeat;
import com.ziggfreed.common.encounter.ledger.FeatTally;
import com.ziggfreed.common.encounter.run.EncounterRuns;
import com.ziggfreed.common.encounter.run.EncounterSubjects;
import com.ziggfreed.common.util.SafeLog;

/**
 * The ledger's eyes: an observe-only system on the engine's INSPECT damage group, the phase where a
 * damage value is final (every filter that mutates or cancels has already run). It never changes
 * the damage. A hit on a bound subject credits the attacker's player (a non-player attacker through
 * the attribution seam); a hit on a member credits damage taken; a hit on one of a live run's adds
 * (an NPC carrying the encounter's native spawn lineage, which the engine copies onto whatever the
 * script's spawners and Spawn actions raise) credits the attacker's player damage to the adds. Either
 * player-credited hit also reaches the run's feat tally ({@code FeatTally}) with its native Hit tags,
 * read only when the run's binding row names feats.
 *
 * <p>The miss path, which is every hit on a server with no fight on, is three looks at three empty
 * maps and allocates nothing; the victim's lineage is read only while some run's lineage is indexed.
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
                return;
            }
            if (EncounterRuns.anyLineageIndexed()) {
                SpawnLineage lineage = archetypeChunk.getComponent(index, SpawnLineage.getComponentType());
                UUID addRun = lineage == null ? null : EncounterRuns.runOfLineage(lineage.getLineageId());
                if (addRun != null) {
                    creditAdd(store, addRun, victim, damage, amount);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " damage bookkeeping failed", t);
        }
    }

    private static void creditDealt(@Nonnull Store<EntityStore> store, @Nonnull UUID runId, @Nonnull Damage damage,
            float amount) {
        PlayerRef player = creditedPlayer(store, damage);
        if (player == null) {
            return;
        }
        UUID playerId = player.getUuid();
        String name = player.getUsername();
        EncounterRuns.LEDGER.creditDamageDealt(runId, playerId, name, amount);
        EncounterRuns.Live live = EncounterRuns.live(runId);
        if (live != null) {
            live.run().noteHitter(playerId);
            live.run().noteMember(playerId, name);
            noteFeats(live, FeatTally.Struck.SUBJECT, damage);
        }
    }

    /**
     * Hand a player-credited hit to the run's feat tally, with the hit's own Hit tags, only when the
     * run's binding row names feats: a fight with none reads no tag.
     */
    private static void noteFeats(@Nonnull EncounterRuns.Live live, @Nonnull FeatTally.Struck struck,
            @Nonnull Damage damage) {
        List<EncounterFeat> feats = EncounterBindingConfig.getInstance().featsFor(live.encounterId());
        if (!feats.isEmpty()) {
            live.run().feats().noteHit(feats, struck, HitTags.of(damage));
        }
    }

    /**
     * A hit on an add, credited only inside the run's own world and never on the subject, which carries the
     * lineage too and reaches here only in the tick after an in-place role change reissued its reference.
     */
    private static void creditAdd(@Nonnull Store<EntityStore> store, @Nonnull UUID runId,
            @Nonnull Ref<EntityStore> victim, @Nonnull Damage damage, float amount) {
        EncounterRuns.Live live = EncounterRuns.live(runId);
        if (live == null || live.encounterRef().getStore() != store) {
            return;
        }
        UUID subjectUuid = live.run().subjectUuid();
        if (subjectUuid != null && subjectUuid.equals(EncounterSubjects.uuidOf(store, victim))) {
            return;
        }
        PlayerRef player = creditedPlayer(store, damage);
        if (player == null) {
            return;
        }
        EncounterRuns.LEDGER.creditDamageToAdds(runId, player.getUuid(), player.getUsername(), amount);
        live.run().noteMember(player.getUuid(), player.getUsername());
        noteFeats(live, FeatTally.Struck.ADD, damage);
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

    /** The player a hit is credited to: the attacker, else whoever the attribution seam says it acts for. */
    @Nullable
    private static PlayerRef creditedPlayer(@Nonnull Store<EntityStore> store, @Nonnull Damage damage) {
        Ref<EntityStore> attacker = EncounterDeathSystem.attackerOf(damage);
        Ref<EntityStore> credited = attacker == null ? null : EncounterDeathSystem.creditedPlayer(store, attacker);
        PlayerRef player = credited == null ? null : store.getComponent(credited, PlayerRef.getComponentType());
        return player == null || player.getUuid() == null ? null : player;
    }
}
