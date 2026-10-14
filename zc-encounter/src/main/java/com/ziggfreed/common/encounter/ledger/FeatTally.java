package com.ziggfreed.common.encounter.ledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * How one run stands on its feats ({@link EncounterFeat}): for each, how many hits have met its
 * condition and whether any hit has broken it. Fed every player-credited hit the run's damage system
 * sees, on the boss or on one of its adds, and read once at the defeat.
 *
 * <p><b>The rule.</b> A hit on the SUBJECT that carries none of a feat's subject tags breaks that feat
 * for the whole run, whoever landed it; one that carries one counts toward it. A hit on an ADD (anything
 * the fight's spawners raise, whatever the boss summons or splits off) is never judged by a subject
 * condition, so the adds may be hit with anything. A feat is HELD at the defeat when it sets a condition,
 * nothing broke it and at least one hit met it, so a boss nobody struck the feat's way never pays it.
 *
 * <p>Pure: no engine type. Written on the run's own world thread, like the rest of the run.
 */
public final class FeatTally {

    /** What a hit landed on. */
    public enum Struck {
        /** The boss the binding row binds, in any of its forms. */
        SUBJECT,
        /** Anything the fight's own spawners raised: never the subject. */
        ADD
    }

    /** Where one feat stands mid-fight, for an admin read. */
    public enum Standing {
        /** Nothing has met or broken it yet. */
        WAITING,
        /** At least one hit met it and none broke it: a win now would pay it. */
        HELD,
        /** A hit broke it; this run cannot pay it. */
        BROKEN
    }

    /** One feat's counters, keyed by its folded name. */
    private static final class State {
        int counted;
        boolean broken;
    }

    private final Map<String, State> byFeat = new HashMap<>();

    public FeatTally() {
    }

    /**
     * Note one player-credited hit on {@code struck}, carrying {@code hitTags}, against every feat in
     * {@code feats}.
     */
    public void noteHit(@Nonnull List<EncounterFeat> feats, @Nonnull Struck struck,
            @Nonnull Collection<String> hitTags) {
        if (struck != Struck.SUBJECT) {
            return;
        }
        for (EncounterFeat feat : feats) {
            if (!feat.judgesSubjectHits()) {
                continue;
            }
            State state = byFeat.computeIfAbsent(EncounterFeat.fold(feat.name()), k -> new State());
            if (feat.acceptsSubjectHit(hitTags)) {
                state.counted++;
            } else {
                state.broken = true;
            }
        }
    }

    /** Where {@code feat} stands in this run; a feat that sets no condition only ever waits. */
    @Nonnull
    public Standing standing(@Nonnull EncounterFeat feat) {
        if (!feat.hasCondition()) {
            return Standing.WAITING;
        }
        State state = byFeat.get(EncounterFeat.fold(feat.name()));
        if (state != null && state.broken) {
            return Standing.BROKEN;
        }
        return state != null && state.counted > 0 ? Standing.HELD : Standing.WAITING;
    }

    /** How many hits have met {@code feat}'s condition this run. */
    public int counted(@Nonnull EncounterFeat feat) {
        State state = byFeat.get(EncounterFeat.fold(feat.name()));
        return state == null ? 0 : state.counted;
    }

    /** The names of the feats in {@code feats} this run holds, in their given order: what a win now pays. */
    @Nonnull
    public List<String> held(@Nonnull List<EncounterFeat> feats) {
        List<String> out = new ArrayList<>();
        for (EncounterFeat feat : feats) {
            if (standing(feat) == Standing.HELD) {
                out.add(feat.name());
            }
        }
        return out;
    }

    /** Forget everything; the run rolled over. */
    public void clear() {
        byFeat.clear();
    }
}
