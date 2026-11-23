package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * A pay mark, as a reader builds it ({@link PayMarkPainter}): an optional head over it ("Next clear"), its word
 * ("Full pay"), and how many of its {@link #PIPS} diamonds are lit ({@code lit}, held in 0 to {@link #PIPS}): all four
 * for a full share, two for half, one for a quarter, none for nothing. The words are the caller's; the mark's ink
 * follows {@code lit} ({@link PayMarkPainter#ink}).
 */
public record PayMark(@Nullable Message head, @Nonnull Message word, int lit) {

    /** How many diamonds a mark has. */
    public static final int PIPS = 4;

    public PayMark {
        Objects.requireNonNull(word, "word");
        lit = Math.max(0, Math.min(PIPS, lit));
    }

    /**
     * How many of the four diamonds light for a share of the full pay ({@code 1} full, {@code 0.5} half,
     * {@code 0.25} a quarter, {@code 0} none): the nearest quarter, held in 0 to {@link #PIPS}, so a share above
     * nothing never reads as none and a share under full never reads as full.
     */
    public static int litFor(double share) {
        if (!Double.isFinite(share) || share <= 0.0) {
            return 0;
        }
        if (share >= 1.0) {
            return PIPS;
        }
        int lit = (int) Math.round(share * PIPS);
        return Math.max(1, Math.min(PIPS - 1, lit));
    }
}
