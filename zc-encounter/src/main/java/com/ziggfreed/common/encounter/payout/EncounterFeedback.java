package com.ziggfreed.common.encounter.payout;

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
 * {@code seconds}, and at a settlement {@code share} (the reader's own, as a typed number) and
 * {@code rank} (their place, 1 for the top contributor). A moment nobody authored costs one cheap
 * probe and nothing else.
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
        for (UUID playerId : playerIds) {
            fireTo(store, momentId, playerId, args);
        }
    }

    /** Fire {@code momentId} to every participant, each with their own share and rank added. */
    public static void fireWithShares(@Nonnull Store<EntityStore> store, @Nonnull String momentId,
            @Nonnull List<ParticipantShare> shares, @Nonnull Map<String, Object> args) {
        if (shares.isEmpty() || !FeedbackEngine.answers(momentId)) {
            return;
        }
        int rank = 0;
        for (ParticipantShare share : shares) {
            rank++;
            Map<String, Object> own = new LinkedHashMap<>(args);
            own.put(SHARE_ARG, share.share());
            own.put(RANK_ARG, rank);
            fireTo(store, momentId, share.playerId(), own);
        }
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
