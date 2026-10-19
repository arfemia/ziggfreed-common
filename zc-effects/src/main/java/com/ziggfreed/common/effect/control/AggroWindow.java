package com.ziggfreed.common.effect.control;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * One entity's open aggro window: a taunt (creatures in reach turn on it), a suppress (creatures in reach ignore it
 * and forget it) or a one-pass drop (creatures in reach forget it once). The creatures the last sweep reached are
 * kept, so the attitude provider answers between sweeps without measuring anything. World thread only.
 */
final class AggroWindow {

    /** What the window does. Internal state, one per source: a newer call replaces it. */
    enum Kind {
        TAUNT, SUPPRESS, DROP
    }

    private final Ref<EntityStore> source;
    private final Kind kind;
    private final double radius;
    private final long endsAtNanos;
    private final Set<Integer> reached = new HashSet<>();
    @Nullable
    private Vector3d origin;

    AggroWindow(@Nonnull Ref<EntityStore> source, @Nonnull Kind kind, double radius, long endsAtNanos) {
        this.source = source;
        this.kind = kind;
        this.radius = radius;
        this.endsAtNanos = endsAtNanos;
    }

    @Nonnull
    Ref<EntityStore> source() {
        return source;
    }

    @Nonnull
    Kind kind() {
        return kind;
    }

    /** Whether this window belongs to {@code ref}. */
    boolean isFor(@Nullable Ref<EntityStore> ref) {
        return AggroWindows.sameEntity(source, ref);
    }

    /** Whether the window still runs at {@code nowNanos}: its source lives and, for a timed window, time is left. */
    boolean isLive(long nowNanos) {
        return source.isValid() && (kind == Kind.DROP || nowNanos < endsAtNanos);
    }

    /** The seconds left at {@code nowNanos}; a drop has none. */
    float remainingSeconds(long nowNanos) {
        if (kind == Kind.DROP) {
            return 0f;
        }
        return Math.max(0f, (endsAtNanos - nowNanos) / 1_000_000_000f);
    }

    /** Start a sweep from where the source stands now ({@code null} when it cannot be placed). */
    void beginSweep(@Nullable Vector3d sourcePosition) {
        origin = sourcePosition;
        reached.clear();
    }

    /** Whether a creature at {@code position} is within reach this sweep; a radius of 0 or less is the whole world. */
    boolean reaches(@Nonnull Ref<EntityStore> creature, @Nullable Vector3d position) {
        if (isFor(creature)) {
            return false;
        }
        if (radius <= 0.0) {
            return true;
        }
        if (origin == null || position == null) {
            return false;
        }
        double dx = position.x - origin.x;
        double dy = position.y - origin.y;
        double dz = position.z - origin.z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** Record that this sweep reached {@code creature}, so the provider answers for it. */
    void reached(@Nonnull Ref<EntityStore> creature) {
        reached.add(creature.getIndex());
    }

    /**
     * The attitude {@code creature} takes toward the source while this window runs: hostile under a taunt, ignore
     * under a suppress, and no answer under a drop or for a creature the last sweep did not reach.
     */
    @Nullable
    Attitude attitudeOf(@Nonnull Ref<EntityStore> creature) {
        if (kind == Kind.DROP || !reached.contains(creature.getIndex())) {
            return null;
        }
        return kind == Kind.TAUNT ? Attitude.HOSTILE : Attitude.IGNORE;
    }
}
