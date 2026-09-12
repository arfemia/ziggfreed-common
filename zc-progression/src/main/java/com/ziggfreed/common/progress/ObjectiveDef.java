package com.ziggfreed.common.progress;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One authored objective on whatever owns it: what kind of moment counts, which one specifically,
 * how many, where, and when in its owner it unlocks.
 *
 * <p>The shared objective shape every lifecycle engine in this library authors against, so an
 * objective written for one engine reads and behaves identically under the next.
 *
 * <p>Immutable and built through {@link #builder}. It carries no progress - that lives per subject
 * in an {@link ObjectiveProgressState} keyed by {@link #id()}.
 *
 * <p><b>{@code order} is the sequencing knob and 0 means unconstrained.</b> An objective with
 * {@code order > 0} unlocks only once every objective with a strictly LOWER non-zero order is done;
 * objectives sharing an order run in parallel. What happens when an owner authors no orders at all
 * is the owning engine's call, not this record's.
 *
 * <p><b>Two pairs, one objective.</b> {@link #kind()} and {@link #target()} are what the engine
 * dispatches, indexes and settles on. {@link #authoredKind()} and {@link #authoredTarget()} are
 * what the file said, and they differ only when a registered
 * {@linkplain ObjectiveKindRegistry.Alias kind alias} fired at the fold: an authored kind that
 * RUNS as another kind with its target rewritten. Every text and icon surface reads the authored
 * pair, so "reach rank 30 in Mining" never renders as the channel id it is measured on; every
 * engine site reads the run pair and never learns an alias exists.
 */
public final class ObjectiveDef {

    private final String id;
    private final String kind;
    private final String target;
    private final String authoredKind;
    private final String authoredTarget;
    private final MatchMode matchMode;
    @Nullable private final String qualifier;
    private final long amount;
    @Nullable private final String zone;
    private final int order;
    @Nullable private final String turnInLockId;

    private ObjectiveDef(@Nonnull Builder b) {
        this.id = b.id;
        this.kind = b.kind;
        this.target = b.target != null ? b.target : "";
        this.authoredKind = b.authoredKind != null ? b.authoredKind : this.kind;
        this.authoredTarget = b.authoredTarget != null ? b.authoredTarget : this.target;
        this.matchMode = b.matchMode;
        this.qualifier = b.qualifier;
        this.amount = b.amount;
        this.zone = (b.zone == null || b.zone.isBlank()) ? null : b.zone;
        this.order = Math.max(0, b.order);
        this.turnInLockId = (b.turnInLockId == null || b.turnInLockId.isBlank()) ? null : b.turnInLockId;
    }

    /** Unique within its owner; also the key its progress is stored under. */
    @Nonnull
    public String id() {
        return id;
    }

    /** The registered {@link ObjectiveKind} id this objective listens for. */
    @Nonnull
    public String kind() {
        return kind;
    }

    /** What specifically counts. Never null; EMPTY means match-all - see {@link ObjectiveMatch}. */
    @Nonnull
    public String target() {
        return target;
    }

    /**
     * The kind the FILE named, for every surface that puts words or a picture beside the step. The
     * same as {@link #kind()} unless a kind alias fired at the fold.
     */
    @Nonnull
    public String authoredKind() {
        return authoredKind;
    }

    /**
     * The target the FILE named, for the same surfaces. The same as {@link #target()} unless a kind
     * alias rewrote it; never null, empty when nothing was named.
     */
    @Nonnull
    public String authoredTarget() {
        return authoredTarget;
    }

    @Nonnull
    public MatchMode matchMode() {
        return matchMode;
    }

    /** The secondary filter, or null for "any". An EMPTY string is a real value - see {@link ObjectiveMatch}. */
    @Nullable
    public String qualifier() {
        return qualifier;
    }

    /** How many are needed. */
    public long amount() {
        return amount;
    }

    /** {@link #amount()} clamped into int range, the width progress is counted in. */
    public int amountAsInt() {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, amount));
    }

    /** The zone or region this objective only progresses inside, or null for anywhere. */
    @Nullable
    public String zone() {
        return zone;
    }

    /** Sequencing group; 0 = unconstrained. See the class javadoc. */
    public int order() {
        return order;
    }

    /**
     * For a hand-in objective: the id of the one place it can be handed in at, or null when any
     * hand-in surface will do. The engine only compares it (case-insensitively) against the id a
     * caller passes; resolving aliases is the consumer's business.
     */
    @Nullable
    public String turnInLockId() {
        return turnInLockId;
    }

    /** Does the identifier + qualifier an event carries satisfy this objective? */
    public boolean matches(@Nonnull String eventTarget,
                           @Nullable String eventQualifier) {
        return ObjectiveMatch.matches(target, matchMode, qualifier, eventTarget, eventQualifier);
    }

    /** Does this objective's zone scope admit an event that happened at {@code eventZone}? */
    public boolean matchesZone(@Nullable ZoneRef eventZone) {
        return ObjectiveMatch.zoneMatches(zone, eventZone);
    }

    @Override
    public String toString() {
        return "ObjectiveDef[" + id + " " + kind + " '" + target + "' x" + amount + "]";
    }

    @Nonnull
    public static Builder builder(@Nonnull String id, @Nonnull String kind) {
        return new Builder(id, kind);
    }

    /** Assembles a {@link ObjectiveDef}; only the id and kind are required. */
    public static final class Builder {

        private final String id;
        private final String kind;
        @Nullable private String target;
        @Nullable private String authoredKind;
        @Nullable private String authoredTarget;
        private MatchMode matchMode = MatchMode.CONTAINS;
        @Nullable private String qualifier;
        private long amount = 1L;
        @Nullable private String zone;
        private int order;
        @Nullable private String turnInLockId;

        private Builder(@Nonnull String id, @Nonnull String kind) {
            this.id = id;
            this.kind = kind;
        }

        @Nonnull
        public Builder target(@Nullable String target) {
            this.target = target;
            return this;
        }

        /**
         * What the file named, when the run pair differs from it because a kind alias fired. Left
         * unset, the authored pair IS the run pair, which is every ordinary objective.
         */
        @Nonnull
        public Builder authored(@Nullable String authoredKind, @Nullable String authoredTarget) {
            this.authoredKind = authoredKind == null || authoredKind.isBlank() ? null : authoredKind.trim();
            this.authoredTarget = authoredTarget == null ? "" : authoredTarget;
            return this;
        }

        @Nonnull
        public Builder matchMode(@Nonnull MatchMode matchMode) {
            this.matchMode = matchMode;
            return this;
        }

        @Nonnull
        public Builder qualifier(@Nullable String qualifier) {
            this.qualifier = qualifier;
            return this;
        }

        @Nonnull
        public Builder amount(long amount) {
            this.amount = amount;
            return this;
        }

        @Nonnull
        public Builder zone(@Nullable String zone) {
            this.zone = zone;
            return this;
        }

        @Nonnull
        public Builder order(int order) {
            this.order = order;
            return this;
        }

        @Nonnull
        public Builder turnInLockId(@Nullable String turnInLockId) {
            this.turnInLockId = turnInLockId;
            return this;
        }

        @Nonnull
        public ObjectiveDef build() {
            return new ObjectiveDef(this);
        }
    }
}
