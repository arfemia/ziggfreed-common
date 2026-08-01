package com.ziggfreed.common.interaction.target;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.selector.Selector;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.cast.RaycastTargeting;
import com.ziggfreed.common.util.SafeLog;

/**
 * Server-authoritative entity selection - the trustworthy replacement for the native,
 * client-trusting {@code SelectInteraction} sweep.
 *
 * <p><b>Why this exists.</b> {@code SelectInteraction.getWaitForDataFrom()} returns
 * {@code WaitForDataFrom.Client} (verified, {@code SelectInteraction.java:220-221}) - the
 * native selector trusts client-reported hit sets. It is disqualified as an ability's
 * targeting authority. This class resolves the target LIST; the CONSUMER's own Type does
 * the per-target fork using the exact engine idiom ({@code SelectInteraction.java:329-364}):
 * <pre>{@code
 * subCtx = context.duplicate();
 * subCtx.getMetaStore().putMetaObject(Interaction.TARGET_ENTITY, ref);
 * context.fork(new InteractionChainData(), context.getChain().getType(), subCtx, hitRoot, false);
 * }</pre>
 * This class ships NO forking, NO damage, NO hostility policy - a consumer's Type owns
 * every one of those.
 *
 * <p><b>Deliberate divergence from the engine, recorded here.</b> Where
 * {@code SelectInteraction} caps its target set with {@code reservoirSample} (random),
 * this engine sorts by the ordering scalar ({@link TargetHit#distance()}) and takes the
 * first N. Deterministic capping is what an ability wants and what a test can assert.
 *
 * <p>Two narrow phases over one {@link TargetQuery}: {@link #volume} (broad phase
 * {@code Selector.selectNearbyEntities} + distance / cone / cylinder gates) and
 * {@link #ray} (delegates the ray-AABB narrow phase to
 * {@code cast.RaycastTargeting.pickPiercing}). Both then run the SAME post-filter
 * pipeline in this FIXED order: owner + excluded indices, consumer filter, line of sight
 * (via {@code cast.BlockRaystep}), ordering ({@link TargetQuery#nearestFirst()}),
 * {@link TargetQuery#maxTargets()} cap.
 *
 * <p>Returns an immutable, possibly empty list; NEVER {@code null}, never throws. A null
 * store, a null query, or {@code query.maxDistance() <= 0} short-circuits to empty BEFORE
 * any engine touch - the guard the unit tests rely on.
 *
 * <p>World-thread (reads {@code TransformComponent} and walks blocks).
 */
public final class TargetSweep {

    private TargetSweep() {}

    /**
     * Broad-phase-plus-geometry sweep: {@code Selector.selectNearbyEntities} around
     * {@code query.origin()} out to {@code query.maxDistance() + query.inflateRadius()},
     * narrowed per candidate by distance / cone / cylinder gates, then the shared
     * post-filter pipeline.
     */
    @Nonnull
    public static List<TargetHit> volume(@Nullable Store<EntityStore> store, @Nullable TargetQuery query) {
        if (store == null || query == null || query.maxDistance() <= 0.0) return List.of();
        List<TargetHit> raw;
        try {
            raw = collectVolume(store, query);
        } catch (Throwable t) {
            SafeLog.warn("[interaction.target] volume sweep failed", t);
            return List.of();
        }
        return applyPostFilter(store, query, raw);
    }

    /**
     * Ray sweep: delegates the narrow phase to
     * {@code RaycastTargeting.pickPiercing(store, query.owner(), query.origin(),
     * query.direction(), query.maxDistance(), query.inflateRadius(), maxHits)} (maxHits =
     * {@code query.maxTargets() > 0 ? query.maxTargets() : Integer.MAX_VALUE}), then the
     * shared post-filter pipeline. Requires {@link TargetQuery#owner()} (the engine ray
     * pick excludes by caster index) and {@link TargetQuery#direction()}; either missing
     * returns empty plus a guarded FINE log.
     */
    @Nonnull
    public static List<TargetHit> ray(@Nullable Store<EntityStore> store, @Nullable TargetQuery query) {
        if (store == null || query == null || query.maxDistance() <= 0.0) return List.of();
        Ref<EntityStore> owner = query.owner();
        if (owner == null) {
            SafeLog.fine("[interaction.target] ray sweep skipped: TargetQuery.owner() is null");
            return List.of();
        }
        Vector3d direction = query.direction();
        if (direction == null) {
            SafeLog.fine("[interaction.target] ray sweep skipped: TargetQuery.direction() is null");
            return List.of();
        }
        List<TargetHit> raw;
        try {
            int maxHits = query.maxTargets() > 0 ? query.maxTargets() : Integer.MAX_VALUE;
            Vector3d origin = query.origin();
            List<RaycastTargeting.Hit> engineHits = RaycastTargeting.pickPiercing(
                    store, owner, origin, direction, query.maxDistance(), query.inflateRadius(), maxHits);
            raw = new ArrayList<>(engineHits.size());
            for (RaycastTargeting.Hit hit : engineHits) {
                Vector3d pos = new Vector3d(hit.entityX, hit.entityY, hit.entityZ);
                raw.add(TargetHit.of(hit.ref, pos, hit.rayT, angleBetween(origin, direction, pos)));
            }
        } catch (Throwable t) {
            SafeLog.warn("[interaction.target] ray sweep failed", t);
            return List.of();
        }
        return applyPostFilter(store, query, raw);
    }

    /** {@link #volume} reduced to its first element, or {@code null}. */
    @Nullable
    public static TargetHit nearestInVolume(@Nullable Store<EntityStore> store, @Nullable TargetQuery query) {
        List<TargetHit> hits = volume(store, query);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** {@link #ray} reduced to its first element, or {@code null}. */
    @Nullable
    public static TargetHit nearestOnRay(@Nullable Store<EntityStore> store, @Nullable TargetQuery query) {
        List<TargetHit> hits = ray(store, query);
        return hits.isEmpty() ? null : hits.get(0);
    }

    // ==================== volume broad + narrow phase ====================

    @Nonnull
    private static List<TargetHit> collectVolume(@Nonnull Store<EntityStore> store, @Nonnull TargetQuery query) {
        List<TargetHit> hits = new ArrayList<>();
        Vector3d origin = query.origin();
        Vector3d direction = query.direction();
        Double coneAngle = query.coneAngleDegrees();
        Double verticalExtent = query.verticalExtent();
        double minDistance = query.minDistance();
        double maxDistance = query.maxDistance();
        long ownerIndex = query.owner() != null ? query.owner().getIndex() : Long.MIN_VALUE;
        double searchRadius = maxDistance + query.inflateRadius();

        Selector.selectNearbyEntities(store, origin, searchRadius, candidate -> {
            TransformComponent transform = store.getComponent(candidate, TransformComponent.getComponentType());
            if (transform == null) return;
            Vector3d pos = transform.getPosition();
            double dx = pos.x - origin.x;
            double dy = pos.y - origin.y;
            double dz = pos.z - origin.z;

            if (verticalExtent != null && Math.abs(dy) > verticalExtent) return;

            // Horizontal-or-full: a cylinder (verticalExtent set) gates on the horizontal
            // radius; a sphere gates on the full 3D distance. Reused as the ordering
            // scalar for this hit so the two stay consistent with each other.
            double distance = verticalExtent != null
                    ? Math.sqrt(dx * dx + dz * dz)
                    : Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < minDistance || distance > maxDistance) return;

            double angleDegrees = angleBetween(dx, dy, dz, direction);
            if (direction != null && coneAngle != null && angleDegrees > coneAngle) return;

            hits.add(TargetHit.of(candidate, pos, distance, angleDegrees));
        }, candidate -> candidate.getIndex() != ownerIndex);

        return hits;
    }

    // ==================== shared post-filter pipeline ====================

    /**
     * Runs the SAME post-filter pipeline for both narrow phases, in this fixed order:
     * owner + excluded indices, consumer filter, line of sight, ordering, maxTargets cap.
     */
    @Nonnull
    private static List<TargetHit> applyPostFilter(@Nonnull Store<EntityStore> store,
                                                    @Nonnull TargetQuery query,
                                                    @Nonnull List<TargetHit> raw) {
        if (raw.isEmpty()) return List.of();

        long ownerIndex = query.owner() != null ? query.owner().getIndex() : Long.MIN_VALUE;
        long[] excluded = query.excludedIndices();
        Predicate<Ref<EntityStore>> filter = query.filter();
        boolean requireLos = query.requireLineOfSight();
        World world = requireLos ? resolveWorld(store) : null;
        Vector3d origin = query.origin();
        double losStep = query.lineOfSightStep();

        List<TargetHit> filtered = new ArrayList<>(raw.size());
        for (TargetHit hit : raw) {
            long idx = hit.ref().getIndex();
            if (idx == ownerIndex) continue;
            if (isExcluded(idx, excluded)) continue;
            if (filter != null && !filter.test(hit.ref())) continue;
            if (requireLos && !LineOfSight.clear(world, origin, hit.position(), losStep)) continue;
            filtered.add(hit);
        }
        if (filtered.isEmpty()) return List.of();

        filtered.sort(query.nearestFirst()
                ? Comparator.comparingDouble(TargetHit::distance)
                : Comparator.comparingDouble(TargetHit::distance).reversed());

        if (query.maxTargets() > 0 && filtered.size() > query.maxTargets()) {
            filtered = filtered.subList(0, query.maxTargets());
        }

        return List.copyOf(filtered);
    }

    @Nullable
    private static World resolveWorld(@Nonnull Store<EntityStore> store) {
        try {
            return store.getExternalData().getWorld();
        } catch (Throwable t) {
            SafeLog.fine("[interaction.target] failed to resolve world for line-of-sight check", t);
            return null;
        }
    }

    private static boolean isExcluded(long index, @Nonnull long[] excluded) {
        for (long e : excluded) {
            if (e == index) return true;
        }
        return false;
    }

    /** Angle in degrees between {@code direction} and the delta vector {@code (dx,dy,dz)}; 0 when direction is null. */
    private static double angleBetween(double dx, double dy, double dz, @Nullable Vector3d direction) {
        if (direction == null) return 0.0;
        double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq <= 1e-12) return 0.0;
        double invLen = 1.0 / Math.sqrt(lenSq);
        double dot = (dx * invLen) * direction.x + (dy * invLen) * direction.y + (dz * invLen) * direction.z;
        double clampedDot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(clampedDot));
    }

    private static double angleBetween(@Nonnull Vector3d origin, @Nullable Vector3d direction, @Nonnull Vector3d pos) {
        return angleBetween(pos.x - origin.x, pos.y - origin.y, pos.z - origin.z, direction);
    }
}
