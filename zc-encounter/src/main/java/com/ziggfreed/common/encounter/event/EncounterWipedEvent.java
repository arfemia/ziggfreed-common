package com.ziggfreed.common.encounter.event;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;

/**
 * The fight was lost: it engaged, and then every member was dead or gone for the binding's
 * {@code WipeGraceSeconds}, or its owner tore the encounter down mid-fight. Fired once per run, on
 * the world thread, with everybody's credit settled the same way a defeat settles it.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; every collection is a
 * copy and unmodifiable. See {@link Encounters} for the fire contract.
 */
public final class EncounterWipedEvent implements IEvent<Void> {

    private final UUID runId;
    private final String encounterId;
    @Nullable private final UUID worldUuid;
    private final List<ParticipantShare> participantShares;
    private final List<UUID> participants;
    private final Map<UUID, Double> shares;
    private final double elapsedSeconds;
    private final int memberDeaths;
    private final boolean allMembersDead;
    @Nullable private final String subjectPhase;
    private final double subjectHealthFraction;

    public EncounterWipedEvent(@Nonnull UUID runId, @Nonnull String encounterId, @Nullable UUID worldUuid,
                               @Nonnull List<ParticipantShare> participantShares, @Nonnull List<UUID> participants,
                               @Nonnull Map<UUID, Double> shares, double elapsedSeconds, int memberDeaths,
                               boolean allMembersDead, @Nullable String subjectPhase, double subjectHealthFraction) {
        this.runId = runId;
        this.encounterId = encounterId;
        this.worldUuid = worldUuid;
        this.participantShares = List.copyOf(participantShares);
        this.participants = List.copyOf(participants);
        this.shares = Map.copyOf(shares);
        this.elapsedSeconds = elapsedSeconds;
        this.memberDeaths = memberDeaths;
        this.allMembersDead = allMembersDead;
        this.subjectPhase = subjectPhase;
        this.subjectHealthFraction = subjectHealthFraction;
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

    @Nonnull
    public List<ParticipantShare> participantShares() {
        return participantShares;
    }

    @Nonnull
    public List<UUID> participants() {
        return participants;
    }

    @Nonnull
    public Map<UUID, Double> shares() {
        return shares;
    }

    public double elapsedSeconds() {
        return elapsedSeconds;
    }

    public int memberDeaths() {
        return memberDeaths;
    }

    /** True when every member known to the run had died; false when they simply left. */
    public boolean allMembersDead() {
        return allMembersDead;
    }

    /** The phase the subject was in, or null when no phase was ever signalled. */
    @Nullable
    public String subjectPhase() {
        return subjectPhase;
    }

    /** The subject's health as a fraction of its maximum at the wipe, or -1 when unreadable. */
    public double subjectHealthFraction() {
        return subjectHealthFraction;
    }
}
