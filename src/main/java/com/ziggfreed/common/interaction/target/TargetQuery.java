package com.ziggfreed.common.interaction.target;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * An immutable server-side target sweep description, resolved by {@link TargetSweep}.
 *
 * <p><b>Orthogonal knobs, no mode.</b> There is no shape/mode field. Geometry is chosen by
 * WHICH {@link TargetSweep} METHOD is called ({@code volume} vs {@code ray} - two
 * genuinely different narrow phases), and every knob below composes on top of either: a
 * full sphere is {@link #coneAngleDegrees()} left {@code null}; a cone is that knob set; a
 * cylinder is {@link #verticalExtent()} set; an arc slice is {@link #minDistance()} +
 * {@link #maxDistance()} plus the cone angle.
 *
 * <p>Defaults: minDistance 0, maxDistance 0 (no hits - always set it), coneAngleDegrees
 * {@code null} (no angular gate), verticalExtent {@code null} (spherical), inflateRadius
 * 0, requireLineOfSight {@code false}, lineOfSightStep 0.3, maxTargets 0 (unlimited),
 * nearestFirst {@code true}, owner {@code null}, filter {@code null}.
 *
 * <p>Every value is normalized at BUILD time (never at read time): negative
 * minDistance/maxDistance/inflateRadius clamp to 0; a {@code lineOfSightStep <= 0} falls
 * back to 0.3; a zero-length direction normalizes to {@code null}; a non-unit direction is
 * normalized; {@code coneAngleDegrees} clamps to {@code [0, 180]}; duplicate excluded
 * indices de-duplicate. {@link #origin()}, {@link #direction()}, and
 * {@link #excludedIndices()} all return DEFENSIVE COPIES so mutating a returned value can
 * never corrupt the query.
 */
public final class TargetQuery {

    private static final double DEFAULT_LOS_STEP = 0.3;

    @Nonnull private final Vector3d origin;
    @Nullable private final Vector3d direction;
    @Nullable private final Ref<EntityStore> owner;
    private final double minDistance;
    private final double maxDistance;
    @Nullable private final Double coneAngleDegrees;
    @Nullable private final Double verticalExtent;
    private final double inflateRadius;
    private final boolean requireLineOfSight;
    private final double lineOfSightStep;
    private final int maxTargets;
    @Nonnull private final long[] excludedIndices;
    @Nullable private final Predicate<Ref<EntityStore>> filter;
    private final boolean nearestFirst;

    private TargetQuery(@Nonnull Builder b) {
        this.origin = new Vector3d(b.origin);
        this.direction = normalizeDirection(b.direction);
        this.owner = b.owner;
        this.minDistance = Math.max(0.0, b.minDistance);
        this.maxDistance = Math.max(0.0, b.maxDistance);
        this.coneAngleDegrees = b.coneAngleDegrees == null ? null : clamp(b.coneAngleDegrees, 0.0, 180.0);
        this.verticalExtent = b.verticalExtent;
        this.inflateRadius = Math.max(0.0, b.inflateRadius);
        this.requireLineOfSight = b.requireLineOfSight;
        this.lineOfSightStep = b.lineOfSightStep <= 0.0 ? DEFAULT_LOS_STEP : b.lineOfSightStep;
        this.maxTargets = b.maxTargets;
        this.excludedIndices = dedupe(b.excludedIndices);
        this.filter = b.filter;
        this.nearestFirst = b.nearestFirst;
    }

    @Nonnull
    public static Builder builder(@Nonnull Vector3d origin) {
        return new Builder(origin);
    }

    /** Sweep origin (defensive copy). */
    @Nonnull
    public Vector3d origin() {
        return new Vector3d(origin);
    }

    /**
     * Facing for the angular gate and for {@link TargetSweep#ray}; {@code null} =
     * omnidirectional (volume sweep only). Normalized at build (defensive copy on read).
     */
    @Nullable
    public Vector3d direction() {
        return direction == null ? null : new Vector3d(direction);
    }

    /** The caster: auto-excluded from results, and REQUIRED by {@link TargetSweep#ray}. */
    @Nullable
    public Ref<EntityStore> owner() {
        return owner;
    }

    public double minDistance() {
        return minDistance;
    }

    public double maxDistance() {
        return maxDistance;
    }

    /** Half-angle gate off {@link #direction()} in DEGREES; {@code null} = no angular gate. Clamped to [0,180]. */
    @Nullable
    public Double coneAngleDegrees() {
        return coneAngleDegrees;
    }

    /** Cylinder half-height on Y; {@code null} = spherical distance only. */
    @Nullable
    public Double verticalExtent() {
        return verticalExtent;
    }

    /** Widens the BROAD PHASE only; the narrow phase is unchanged. */
    public double inflateRadius() {
        return inflateRadius;
    }

    public boolean requireLineOfSight() {
        return requireLineOfSight;
    }

    public double lineOfSightStep() {
        return lineOfSightStep;
    }

    /** {@code <= 0} = unlimited. */
    public int maxTargets() {
        return maxTargets;
    }

    /** Extra excluded entity indices beyond {@link #owner()} (defensive copy, may be empty, never null). */
    @Nonnull
    public long[] excludedIndices() {
        return excludedIndices.clone();
    }

    /** Consumer policy (hostility, faction, alive); {@code null} = accept all. Applied AFTER the geometry gates. */
    @Nullable
    public Predicate<Ref<EntityStore>> filter() {
        return filter;
    }

    /** {@code true} = nearest first (default); {@code false} = farthest first. Applied before {@link #maxTargets()}. */
    public boolean nearestFirst() {
        return nearestFirst;
    }

    @Override
    public String toString() {
        return "TargetQuery{origin=" + origin + ", direction=" + direction
                + ", minDistance=" + minDistance + ", maxDistance=" + maxDistance
                + ", coneAngleDegrees=" + coneAngleDegrees + ", verticalExtent=" + verticalExtent
                + ", maxTargets=" + maxTargets + "}";
    }

    @Nullable
    private static Vector3d normalizeDirection(@Nullable Vector3d dir) {
        if (dir == null) return null;
        double lenSq = dir.lengthSquared();
        if (lenSq <= 1e-12) return null;
        Vector3d copy = new Vector3d(dir);
        copy.normalize();
        return copy;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Nonnull
    private static long[] dedupe(@Nonnull List<Long> values) {
        LinkedHashSet<Long> set = new LinkedHashSet<>(values);
        long[] out = new long[set.size()];
        int i = 0;
        for (Long v : set) {
            out[i++] = v;
        }
        return out;
    }

    /** Builder for {@link TargetQuery}. */
    public static final class Builder {
        @Nonnull private final Vector3d origin;
        @Nullable private Vector3d direction;
        @Nullable private Ref<EntityStore> owner;
        private double minDistance;
        private double maxDistance;
        @Nullable private Double coneAngleDegrees;
        @Nullable private Double verticalExtent;
        private double inflateRadius;
        private boolean requireLineOfSight;
        private double lineOfSightStep = DEFAULT_LOS_STEP;
        private int maxTargets;
        @Nonnull private final List<Long> excludedIndices = new ArrayList<>();
        @Nullable private Predicate<Ref<EntityStore>> filter;
        private boolean nearestFirst = true;

        private Builder(@Nonnull Vector3d origin) {
            this.origin = Objects.requireNonNull(origin, "origin");
        }

        @Nonnull
        public Builder direction(@Nullable Vector3d direction) {
            this.direction = direction;
            return this;
        }

        @Nonnull
        public Builder owner(@Nullable Ref<EntityStore> owner) {
            this.owner = owner;
            return this;
        }

        @Nonnull
        public Builder minDistance(double minDistance) {
            this.minDistance = minDistance;
            return this;
        }

        @Nonnull
        public Builder maxDistance(double maxDistance) {
            this.maxDistance = maxDistance;
            return this;
        }

        @Nonnull
        public Builder coneAngleDegrees(@Nullable Double coneAngleDegrees) {
            this.coneAngleDegrees = coneAngleDegrees;
            return this;
        }

        @Nonnull
        public Builder verticalExtent(@Nullable Double verticalExtent) {
            this.verticalExtent = verticalExtent;
            return this;
        }

        @Nonnull
        public Builder inflateRadius(double inflateRadius) {
            this.inflateRadius = inflateRadius;
            return this;
        }

        @Nonnull
        public Builder requireLineOfSight(boolean requireLineOfSight) {
            this.requireLineOfSight = requireLineOfSight;
            return this;
        }

        @Nonnull
        public Builder lineOfSightStep(double lineOfSightStep) {
            this.lineOfSightStep = lineOfSightStep;
            return this;
        }

        @Nonnull
        public Builder maxTargets(int maxTargets) {
            this.maxTargets = maxTargets;
            return this;
        }

        /** Adds {@code ref}'s entity index to the exclusion set; a null ref is a no-op. */
        @Nonnull
        public Builder exclude(@Nullable Ref<EntityStore> ref) {
            if (ref != null) {
                this.excludedIndices.add((long) ref.getIndex());
            }
            return this;
        }

        @Nonnull
        public Builder exclude(long entityIndex) {
            this.excludedIndices.add(entityIndex);
            return this;
        }

        @Nonnull
        public Builder filter(@Nullable Predicate<Ref<EntityStore>> filter) {
            this.filter = filter;
            return this;
        }

        @Nonnull
        public Builder nearestFirst(boolean nearestFirst) {
            this.nearestFirst = nearestFirst;
            return this;
        }

        @Nonnull
        public TargetQuery build() {
            return new TargetQuery(this);
        }
    }
}
