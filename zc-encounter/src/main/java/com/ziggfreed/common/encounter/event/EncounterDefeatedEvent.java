package com.ziggfreed.common.encounter.event;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;

/**
 * The subject is down: its death component landed (the precise instant), or the script signalled
 * {@code zc:defeated} for a fight with no bound subject. Latched once per run, fired on the world
 * thread with everybody's credit already settled, so loot, leaderboards and progression all read the
 * same numbers.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; every collection is a
 * copy and unmodifiable. See {@link Encounters} for the fire contract.
 */
public final class EncounterDefeatedEvent implements IEvent<Void> {

    private final UUID runId;
    private final String encounterId;
    @Nullable private final UUID worldUuid;
    @Nullable private final UUID subjectUuid;
    @Nullable private final String subjectMobId;
    private final List<ParticipantShare> participantShares;
    private final List<UUID> participants;
    private final Map<UUID, Double> shares;
    private final Map<UUID, Double> damageDealt;
    private final double elapsedSeconds;
    private final int memberDeaths;
    @Nullable private final String difficulty;
    @Nullable private final UUID lastHitter;

    public EncounterDefeatedEvent(@Nonnull UUID runId, @Nonnull String encounterId, @Nullable UUID worldUuid,
                                  @Nullable UUID subjectUuid, @Nullable String subjectMobId,
                                  @Nonnull List<ParticipantShare> participantShares, @Nonnull List<UUID> participants,
                                  @Nonnull Map<UUID, Double> shares, @Nonnull Map<UUID, Double> damageDealt,
                                  double elapsedSeconds, int memberDeaths, @Nullable String difficulty,
                                  @Nullable UUID lastHitter) {
        this.runId = runId;
        this.encounterId = encounterId;
        this.worldUuid = worldUuid;
        this.subjectUuid = subjectUuid;
        this.subjectMobId = subjectMobId;
        this.participantShares = List.copyOf(participantShares);
        this.participants = List.copyOf(participants);
        this.shares = Map.copyOf(shares);
        this.damageDealt = Map.copyOf(damageDealt);
        this.elapsedSeconds = elapsedSeconds;
        this.memberDeaths = memberDeaths;
        this.difficulty = difficulty;
        this.lastHitter = lastHitter;
    }

    @Nonnull
    public UUID runId() {
        return runId;
    }

    @Nonnull
    public String encounterId() {
        return encounterId;
    }

    @Nullable
    public UUID worldUuid() {
        return worldUuid;
    }

    @Nullable
    public UUID subjectUuid() {
        return subjectUuid;
    }

    /** The subject's mob id as captured at the bind, before any in-place role change. */
    @Nullable
    public String subjectMobId() {
        return subjectMobId;
    }

    /** Everybody's standing, share descending, attempt-only participants at the tail. */
    @Nonnull
    public List<ParticipantShare> participantShares() {
        return participantShares;
    }

    /** Every participant's id, share descending. */
    @Nonnull
    public List<UUID> participants() {
        return participants;
    }

    /** Share by participant, 0 to 1 of the top contributor's. */
    @Nonnull
    public Map<UUID, Double> shares() {
        return shares;
    }

    /** Raw damage dealt to the subject by participant. */
    @Nonnull
    public Map<UUID, Double> damageDealt() {
        return damageDealt;
    }

    public double elapsedSeconds() {
        return elapsedSeconds;
    }

    public int memberDeaths() {
        return memberDeaths;
    }

    @Nullable
    public String difficulty() {
        return difficulty;
    }

    /** The player credited with the killing blow, or null when nobody player-shaped landed it. */
    @Nullable
    public UUID lastHitter() {
        return lastHitter;
    }
}
