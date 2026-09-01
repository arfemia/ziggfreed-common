package com.ziggfreed.common.world.pattern;

import java.util.Objects;

import javax.annotation.Nonnull;

/**
 * One cell of a block pattern: an integer offset in whole blocks plus an opaque caller payload.
 * The offset is relative to whatever frame the pattern is authored in; {@link BlockPattern#compile}
 * re-bases every cell so the anchor cell sits at the origin, and from then on every offset is
 * anchor-relative. What a cell ACCEPTS is entirely the payload's meaning to the caller: the library
 * never inspects it, it only hands it back through the {@link CellPredicate} seam at match time.
 *
 * @param <P> the caller's own cell payload type
 */
public record PatternCell<P>(int dx, int dy, int dz, @Nonnull P payload) {

    public PatternCell {
        Objects.requireNonNull(payload, "payload");
    }
}
