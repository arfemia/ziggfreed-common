package com.ziggfreed.common.encounter.ledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * Everybody's standing at the end of a run, most credited first. The one answer loot, feedback,
 * leaderboards and events all read, so nothing downstream re-derives credit.
 */
public final class ParticipationShares {

    /** Nobody took part. */
    public static final ParticipationShares EMPTY = new ParticipationShares(List.of());

    private final List<ParticipantShare> participants;
    private final Map<UUID, Double> shares;
    private final Map<UUID, Double> damageDealt;

    ParticipationShares(@Nonnull List<ParticipantShare> sorted) {
        this.participants = Collections.unmodifiableList(new ArrayList<>(sorted));
        Map<UUID, Double> s = new LinkedHashMap<>();
        Map<UUID, Double> d = new LinkedHashMap<>();
        for (ParticipantShare p : sorted) {
            s.put(p.playerId(), p.share());
            d.put(p.playerId(), p.damageDealt());
        }
        this.shares = Collections.unmodifiableMap(s);
        this.damageDealt = Collections.unmodifiableMap(d);
    }

    /** Every participant, share descending; attempt-only participants are at the tail with share 0. */
    @Nonnull
    public List<ParticipantShare> participants() {
        return participants;
    }

    /** Only those whose share clears the rule's minimum, share descending. */
    @Nonnull
    public List<ParticipantShare> credited() {
        List<ParticipantShare> out = new ArrayList<>();
        for (ParticipantShare p : participants) {
            if (p.credited()) {
                out.add(p);
            }
        }
        return out;
    }

    /** Every participant's id, share descending. */
    @Nonnull
    public List<UUID> participantIds() {
        List<UUID> out = new ArrayList<>(participants.size());
        for (ParticipantShare p : participants) {
            out.add(p.playerId());
        }
        return Collections.unmodifiableList(out);
    }

    /** Share by participant, in the same order. */
    @Nonnull
    public Map<UUID, Double> shares() {
        return shares;
    }

    /** Raw damage dealt by participant, in the same order. */
    @Nonnull
    public Map<UUID, Double> damageDealt() {
        return damageDealt;
    }

    public boolean isEmpty() {
        return participants.isEmpty();
    }

    public int size() {
        return participants.size();
    }
}
