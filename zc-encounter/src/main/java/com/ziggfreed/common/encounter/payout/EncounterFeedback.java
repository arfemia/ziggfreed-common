package com.ziggfreed.common.encounter.payout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.encounter.seam.EncounterSeams;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Draws an authored FeedbackMoment for each player a beat concerns, through the one moment engine
 * every other library beat uses, behind each player's own notification preference (the consumer's
 * subject handle answers that; the engine asks).
 *
 * <p>The arguments a moment can name: {@code encounter} (the script id), {@code title} (the fight's
 * name, a nested message the reader's client resolves), {@code phase}, {@code members},
 * {@code seconds}, and at a settlement {@code share} (the reader's own, as whole percent points of
 * the top contributor's, a typed number) and {@code rank} (their place, 1 for the top contributor).
 * Two more ride for an authored BANNER rather than for a line: {@code participants} (the players
 * the beat is about, so a banner's {@code ToParticipants} reaches them wherever they stand) and
 * {@code source} (the script id again, so a banner's {@code MinSecondsBetween} is kept per fight).
 * A moment nobody authored costs one cheap probe and nothing else.
 */
public final class EncounterFeedback {

    public static final String ENCOUNTER_ARG = "encounter";
    public static final String TITLE_ARG = "title";
    public static final String PHASE_ARG = "phase";
    public static final String MEMBERS_ARG = "members";
    public static final String SECONDS_ARG = "seconds";
    public static final String SHARE_ARG = "share";
    public static final String RANK_ARG = "rank";

    private EncounterFeedback() {
    }

    /** Fire {@code momentId} to every online player in {@code playerIds} standing in {@code store}. */
    public static void fire(@Nonnull Store<EntityStore> store, @Nonnull String momentId, @Nonnull List<UUID> playerIds,
            @Nonnull Map<String, Object> args) {
        if (playerIds.isEmpty() || !FeedbackEngine.answers(momentId)) {
            return;
        }
        Map<String, Object> shared = forBanner(args, List.copyOf(playerIds));
        for (UUID playerId : playerIds) {
            fireTo(store, momentId, playerId, shared);
        }
    }

    /** Fire {@code momentId} to every participant, each with their own share and rank added. */
    public static void fireWithShares(@Nonnull Store<EntityStore> store, @Nonnull String momentId,
            @Nonnull List<ParticipantShare> shares, @Nonnull Map<String, Object> args) {
        if (shares.isEmpty() || !FeedbackEngine.answers(momentId)) {
            return;
        }
        List<UUID> participants = new ArrayList<>(shares.size());
        for (ParticipantShare share : shares) {
            participants.add(share.playerId());
        }
        Map<String, Object> shared = forBanner(args, List.copyOf(participants));
        int rank = 0;
        for (ParticipantShare share : shares) {
            rank++;
            Map<String, Object> own = new LinkedHashMap<>(shared);
            own.put(SHARE_ARG, sharePercent(share.share()));
            own.put(RANK_ARG, rank);
            fireTo(store, momentId, share.playerId(), own);
        }
    }

    /** A share, 0 to 1 of the top contributor's, as the whole percent points a line shows. */
    static long sharePercent(double share) {
        return Math.round(Math.max(0.0, Math.min(1.0, share)) * 100.0);
    }

    /** The beat's args plus what an authored banner scopes on: who the beat is about, and what it is about. */
    @Nonnull
    private static Map<String, Object> forBanner(@Nonnull Map<String, Object> args, @Nonnull List<UUID> participants) {
        Map<String, Object> shared = new LinkedHashMap<>(args);
        shared.put(FeedbackEngine.PARTICIPANTS_ARG, participants);
        Object encounterId = args.get(ENCOUNTER_ARG);
        if (encounterId != null) {
            shared.putIfAbsent(FeedbackEngine.SOURCE_ARG, encounterId);
        }
        return shared;
    }

    private static void fireTo(@Nonnull Store<EntityStore> store, @Nonnull String momentId, @Nonnull UUID playerId,
            @Nonnull Map<String, Object> args) {
        try {
            PlayerRef player = Universe.get().getPlayer(playerId);
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != store) {
                return;
            }
            Subject subject = EncounterSeams.subjectFor(store, ref);
            if (subject == null) {
                return;
            }
            FeedbackEngine.fire(momentId, subject, args);
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " the '" + momentId + "' moment failed for one player: " + t.getMessage());
        }
    }
}
