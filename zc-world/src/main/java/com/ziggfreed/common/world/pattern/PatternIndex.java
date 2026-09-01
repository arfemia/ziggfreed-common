package com.ziggfreed.common.world.pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nonnull;

/**
 * A lookup from a block ITEM id to the pattern cells that could stand at a block of that id: the
 * caller registers whichever (pattern, variant, cell) combinations it considers indexable (cells it
 * can name by exact id), and a probe answers every candidate for one id so the caller can derive
 * each implied anchor via {@link PatternVariant#anchorFromCell} and walk from there. The index
 * stores and answers; which cells are worth indexing, and what happens with a candidate, is
 * entirely the caller's.
 *
 * <p>Keys are exact, case-sensitive block item ids (asset ids are canonical). A duplicate
 * registration of the same candidate under the same id is ignored.
 *
 * <p>Not thread-safe: the caller builds and reads it on one thread (or synchronizes itself).
 *
 * @param <P> the caller's own cell payload type
 */
public final class PatternIndex<P> {

    /** One indexable cell: this cell of this variant of this pattern. */
    public record Candidate<P>(@Nonnull BlockPattern<P> pattern, int variantIndex, int cellIndex) {

        public Candidate {
            Objects.requireNonNull(pattern, "pattern");
            if (variantIndex < 0 || variantIndex >= pattern.variants().size()) {
                throw new IllegalArgumentException("variantIndex " + variantIndex
                        + " is outside 0.." + (pattern.variants().size() - 1));
            }
            if (cellIndex < 0 || cellIndex >= pattern.cellCount()) {
                throw new IllegalArgumentException("cellIndex " + cellIndex
                        + " is outside 0.." + (pattern.cellCount() - 1));
            }
        }

        /** The variant this candidate names. */
        @Nonnull
        public PatternVariant<P> variant() {
            return pattern.variants().get(variantIndex);
        }
    }

    private final Map<String, List<Candidate<P>>> byBlockItemId = new HashMap<>();
    private int maxBoundingRadius;

    /** Register one candidate under a block item id (a duplicate registration is ignored). */
    public void add(@Nonnull String blockItemId, @Nonnull BlockPattern<P> pattern,
            int variantIndex, int cellIndex) {
        Objects.requireNonNull(blockItemId, "blockItemId");
        Candidate<P> candidate = new Candidate<>(pattern, variantIndex, cellIndex);
        List<Candidate<P>> candidates = byBlockItemId.computeIfAbsent(blockItemId, k -> new ArrayList<>());
        if (!candidates.contains(candidate)) {
            candidates.add(candidate);
        }
        maxBoundingRadius = Math.max(maxBoundingRadius, pattern.boundingRadius());
    }

    /**
     * Every candidate registered under this block item id, in registration order; empty when the
     * id is not indexed. An unmodifiable view.
     */
    @Nonnull
    public List<Candidate<P>> candidatesFor(@Nonnull String blockItemId) {
        List<Candidate<P>> candidates = byBlockItemId.get(blockItemId);
        return candidates != null ? Collections.unmodifiableList(candidates) : List.of();
    }

    /**
     * The largest {@link BlockPattern#boundingRadius()} over every registered pattern (0 while
     * empty): the widest distance at which any indexed pattern could still relate two block
     * positions, for a caller pruning proximity checks before walking candidates.
     */
    public int maxBoundingRadius() {
        return maxBoundingRadius;
    }

    public boolean isEmpty() {
        return byBlockItemId.isEmpty();
    }

    /** Drop every registration (and reset the radius answer). */
    public void clear() {
        byBlockItemId.clear();
        maxBoundingRadius = 0;
    }
}
