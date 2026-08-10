package com.ziggfreed.common.interaction.target;

import javax.annotation.Nonnull;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * One resolved target from a {@link TargetSweep}. Immutable.
 */
public final class TargetHit {

    @Nonnull private final Ref<EntityStore> ref;
    @Nonnull private final Vector3d position;
    private final double distance;
    private final double angleDegrees;

    private TargetHit(@Nonnull Ref<EntityStore> ref, @Nonnull Vector3d position,
                      double distance, double angleDegrees) {
        this.ref = ref;
        this.position = position;
        this.distance = distance;
        this.angleDegrees = angleDegrees;
    }

    @Nonnull
    public static TargetHit of(@Nonnull Ref<EntityStore> ref, @Nonnull Vector3d position,
                               double distance, double angleDegrees) {
        return new TargetHit(ref, new Vector3d(position), distance, angleDegrees);
    }

    @Nonnull
    public Ref<EntityStore> ref() {
        return ref;
    }

    /**
     * The candidate's transform position at pick time (defensive copy) - the impact
     * SFX/VFX anchor a consumer's fork/dispatch step reads.
     */
    @Nonnull
    public Vector3d position() {
        return new Vector3d(position);
    }

    /**
     * The ordering scalar this hit was ranked by: centre distance from
     * {@link TargetQuery#origin()} for a {@link TargetSweep#volume} sweep, ray parameter
     * t for a {@link TargetSweep#ray} sweep.
     */
    public double distance() {
        return distance;
    }

    /** Angle off {@link TargetQuery#direction()} in degrees; {@code 0} when the query had no direction. */
    public double angleDegrees() {
        return angleDegrees;
    }

    @Override
    public String toString() {
        return "TargetHit{ref=" + ref + ", distance=" + distance + ", angleDegrees=" + angleDegrees + "}";
    }
}
