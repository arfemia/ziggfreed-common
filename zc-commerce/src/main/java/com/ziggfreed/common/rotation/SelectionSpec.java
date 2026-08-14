package com.ziggfreed.common.rotation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * WHICH candidates a rotating pool shows, once the cadence has said when.
 *
 * <p>The RUNTIME selection, not the authored shape; the authoring layer folds its own group into
 * one of these.
 *
 * <p>{@link #type()} names a REGISTERED strategy ({@link SelectionStrategies}) rather than being a
 * mode: it picks which algorithm draws the active set and toggles nothing else. Two are seeded,
 * {@link #TYPE_WEIGHTED_RANDOM} and {@link #TYPE_ALL}; a consumer registers its own the same way it
 * registers any other open vocabulary in this library, and an unknown type resolves to nothing
 * rather than quietly falling back to the default, so a typo is something a validator can report
 * instead of a pool that silently draws the wrong way.
 *
 * <p>{@link #seed()} names what the draw is keyed on. {@link #SEED_PERIOD} is the only one shipped
 * and the only one that makes a rotation reproducible; it is a field rather than an assumption so a
 * consumer that wants a per-player draw has somewhere to put it.
 */
public final class SelectionSpec {

    /** The draw every pool uses unless it says otherwise. */
    public static final String TYPE_WEIGHTED_RANDOM = "Weighted_Random";

    /** Show everything eligible, in id order. */
    public static final String TYPE_ALL = "All";

    /** Keyed on the rotation period, which is what makes a draw reproducible. */
    public static final String SEED_PERIOD = "Period";

    /** The selection a pool that says nothing is read as: a weighted draw keyed on the period. */
    public static final SelectionSpec DEFAULT = new SelectionSpec(TYPE_WEIGHTED_RANDOM, SEED_PERIOD);

    private final String type;
    private final String seed;

    private SelectionSpec(@Nonnull String type, @Nonnull String seed) {
        this.type = type;
        this.seed = seed;
    }

    /** A selection naming a registered strategy; blanks fall back to the shipped defaults. */
    @Nonnull
    public static SelectionSpec of(@Nullable String type, @Nullable String seed) {
        return new SelectionSpec(
                (type == null || type.isBlank()) ? TYPE_WEIGHTED_RANDOM : type.trim(),
                (seed == null || seed.isBlank()) ? SEED_PERIOD : seed.trim());
    }

    /** The registered strategy id. */
    @Nonnull
    public String type() {
        return type;
    }

    /** What the draw is keyed on. */
    @Nonnull
    public String seed() {
        return seed;
    }

    @Override
    public String toString() {
        return "SelectionSpec[" + type + " by " + seed + "]";
    }
}
