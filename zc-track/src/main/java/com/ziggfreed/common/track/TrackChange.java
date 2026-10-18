package com.ziggfreed.common.track;

import javax.annotation.Nonnull;

/**
 * One committed move of a climb: the record before and after, read against the track's curve.
 *
 * @param key     whose climb on which track
 * @param def     the track and its curve, as they stood when it moved
 * @param before  the record before
 * @param after   the record after (unlock rows it paid included)
 * @param source  what moved it: a reward's source id, {@code admin}, {@code prestige}
 * @param claims  how many unlock Claims it parked
 */
public record TrackChange(@Nonnull TrackKey key, @Nonnull TrackDef def, @Nonnull TrackRecord before,
                          @Nonnull TrackRecord after, @Nonnull String source, int claims) {

    public int levelBefore() {
        return def.level(before);
    }

    public int levelAfter() {
        return def.level(after);
    }

    public int bestBefore() {
        return def.best(before);
    }

    public int bestAfter() {
        return def.best(after);
    }

    /** How much the current climb's value moved: negative for a loss or a prestige. */
    public long delta() {
        return after.value() - before.value();
    }

    /**
     * Did a reading move: the current level, the best level or the prestige count? Every track reading reads one
     * of the three, so a move inside one level (the value or its fraction alone, or only unlock marks) moves none
     * and needs no gate verdict read again.
     */
    public boolean readingsMoved() {
        return levelBefore() != levelAfter() || bestBefore() != bestAfter() || before.prestige() != after.prestige();
    }
}
