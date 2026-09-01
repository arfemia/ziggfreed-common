package com.ziggfreed.common.world.pattern;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

/**
 * A completed pattern match: which pattern, in which orientation, anchored where. The anchor is
 * the world position of the pattern's anchor cell (the position the walk was rooted at), and
 * {@link #yawQuarterTurns()} is the matched orientation a consumer carries into whatever it does
 * next (typically a block write at the anchor).
 *
 * @param <P> the caller's own cell payload type
 */
public record PatternMatch<P>(@Nonnull BlockPattern<P> pattern, int variantIndex,
        int anchorX, int anchorY, int anchorZ) {

    /** The matched variant. */
    @Nonnull
    public PatternVariant<P> variant() {
        return pattern.variants().get(variantIndex);
    }

    /** The matched variant's yaw quarter-turns (see {@link PatternVariant#yawQuarterTurns()}). */
    public int yawQuarterTurns() {
        return variant().yawQuarterTurns();
    }

    /** Whether the matched variant is X-mirrored. */
    public boolean mirrored() {
        return variant().mirrored();
    }

    /** The anchor position as a fresh vector the caller owns. */
    @Nonnull
    public Vector3i anchor() {
        return new Vector3i(anchorX, anchorY, anchorZ);
    }
}
