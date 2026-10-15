package com.ziggfreed.common.encounter.event;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.encounter.seam.RunTag;

/**
 * The subject is down: its death component landed (the precise instant), or the script signalled
 * {@code zc:defeated} for a fight with no bound subject, or for one whose subject this library found
 * dead. A beat while the subject still lived is a leash and fires none of this
 * ({@link ResetReason#LEASHED}). Latched once per run, fired on the world thread with everybody's
 * credit already settled, so loot, leaderboards and progression all read the same numbers.
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
    @Nullable private final RunTag runTag;
    private final List<String> feats;
    @Nullable private final Vector3d deathPosition;

    /** A defeat filed under no zc run. */
    public EncounterDefeatedEvent(@Nonnull UUID runId, @Nonnull String encounterId, @Nullable UUID worldUuid,
                                  @Nullable UUID subjectUuid, @Nullable String subjectMobId,
                                  @Nonnull List<ParticipantShare> participantShares, @Nonnull List<UUID> participants,
                                  @Nonnull Map<UUID, Double> shares, @Nonnull Map<UUID, Double> damageDealt,
                                  double elapsedSeconds, int memberDeaths, @Nullable String difficulty,
                                  @Nullable UUID lastHitter) {
        this(runId, encounterId, worldUuid, subjectUuid, subjectMobId, participantShares, participants, shares,
                damageDealt, elapsedSeconds, memberDeaths, difficulty, lastHitter, null);
    }

    /** @param runTag the zc run the fight belongs to (a dungeon area's), or null for none */
    public EncounterDefeatedEvent(@Nonnull UUID runId, @Nonnull String encounterId, @Nullable UUID worldUuid,
                                  @Nullable UUID subjectUuid, @Nullable String subjectMobId,
                                  @Nonnull List<ParticipantShare> participantShares, @Nonnull List<UUID> participants,
                                  @Nonnull Map<UUID, Double> shares, @Nonnull Map<UUID, Double> damageDealt,
                                  double elapsedSeconds, int memberDeaths, @Nullable String difficulty,
                                  @Nullable UUID lastHitter, @Nullable RunTag runTag) {
        this(runId, encounterId, worldUuid, subjectUuid, subjectMobId, participantShares, participants, shares,
                damageDealt, elapsedSeconds, memberDeaths, difficulty, lastHitter, runTag, List.of());
    }

    /**
     * A defeat that says nowhere the boss fell.
     *
     * @param runTag the zc run the fight belongs to (a dungeon area's), or null for none
     * @param feats  the names of the binding row's feats the fight held at the defeat, as authored
     */
    public EncounterDefeatedEvent(@Nonnull UUID runId, @Nonnull String encounterId, @Nullable UUID worldUuid,
                                  @Nullable UUID subjectUuid, @Nullable String subjectMobId,
                                  @Nonnull List<ParticipantShare> participantShares, @Nonnull List<UUID> participants,
                                  @Nonnull Map<UUID, Double> shares, @Nonnull Map<UUID, Double> damageDealt,
                                  double elapsedSeconds, int memberDeaths, @Nullable String difficulty,
                                  @Nullable UUID lastHitter, @Nullable RunTag runTag, @Nonnull List<String> feats) {
        this(runId, encounterId, worldUuid, subjectUuid, subjectMobId, participantShares, participants, shares,
                damageDealt, elapsedSeconds, memberDeaths, difficulty, lastHitter, runTag, feats, null);
    }

    /**
     * @param runTag        the zc run the fight belongs to (a dungeon area's), or null for none
     * @param feats         the names of the binding row's feats the fight held at the defeat, as authored
     * @param deathPosition where the boss fell in the fight's world (copied), or null when it could not be read
     */
    public EncounterDefeatedEvent(@Nonnull UUID runId, @Nonnull String encounterId, @Nullable UUID worldUuid,
                                  @Nullable UUID subjectUuid, @Nullable String subjectMobId,
                                  @Nonnull List<ParticipantShare> participantShares, @Nonnull List<UUID> participants,
                                  @Nonnull Map<UUID, Double> shares, @Nonnull Map<UUID, Double> damageDealt,
                                  double elapsedSeconds, int memberDeaths, @Nullable String difficulty,
                                  @Nullable UUID lastHitter, @Nullable RunTag runTag, @Nonnull List<String> feats,
                                  @Nullable Vector3d deathPosition) {
        this.runTag = runTag;
        this.feats = List.copyOf(feats);
        this.deathPosition = deathPosition == null ? null : new Vector3d(deathPosition);
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

    /**
     * The zc run the fight belongs to (a fight inside a dungeon area belongs to the area's run), as the run-tag
     * seam answered it at the defeat, or null for a fight in no run.
     */
    @Nullable
    public RunTag runTag() {
        return runTag;
    }

    /**
     * The feats the fight held when the boss fell ({@code Progression.Feats} on its binding row), by their
     * authored names: each one every credited participant earns. Empty when the row names none, or the
     * fight broke or never met them.
     */
    @Nonnull
    public List<String> feats() {
        return feats;
    }

    /**
     * Where the boss fell, in the fight's world ({@link #worldUuid()}): the subject's position at the defeat,
     * else the encounter entity's for a fight with no subject bound. A copy each call, so a reader may move it.
     * Null when neither could be read, or the event was built by a caller that names no position.
     */
    @Nullable
    public Vector3d deathPosition() {
        return deathPosition == null ? null : new Vector3d(deathPosition);
    }
}
