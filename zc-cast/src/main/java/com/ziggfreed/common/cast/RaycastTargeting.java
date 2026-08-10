package com.ziggfreed.common.cast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector2d;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.collision.CollisionMath;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.selector.Selector;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Shared entity-raycast picking for any archetype that wants ray-targeted entity
 * selection (a projectile, a raycast DOT, a mark, a beam, a chain-lightning, ...).
 *
 * <p>Two reductions: {@link #pickClosest} returns the single nearest hit by
 * ray-parameter t, {@link #pickPiercing} returns all hits sorted closest-first
 * (capped by {@code maxHits}). Both honor caster exclusion via index match.
 *
 * <p>{@code inflateRadius} only widens the broad-phase {@link Selector} search - the
 * narrow-phase {@link CollisionMath#intersectRayAABB} test is always against the
 * candidate's actual bounding box. Pass {@code 0} when you want a strict ray
 * (single-target picks); pass a damage radius when you want grazing shots to register
 * against entities slightly off-axis.
 *
 * <p>Stateless static utility; world-thread (reads {@code BoundingBox} +
 * {@code TransformComponent} off the store via the {@link Selector}).
 */
public final class RaycastTargeting {

    private RaycastTargeting() {}

    /**
     * Single closest entity along the ray, caster excluded. Returns null when
     * nothing intersects.
     */
    @Nullable
    public static Ref<EntityStore> pickClosest(@Nonnull Store<EntityStore> store,
                                               @Nonnull Ref<EntityStore> casterRef,
                                               @Nonnull Vector3d eyePos,
                                               @Nonnull Vector3d direction,
                                               double maxDistance) {
        AtomicReference<Ref<EntityStore>> bestMatch = new AtomicReference<>(null);
        double[] bestRayT = { Double.MAX_VALUE };
        Vector2d minMax = new Vector2d();
        Vector3d searchCenter = new Vector3d(
                eyePos.x + direction.x * maxDistance * 0.5,
                eyePos.y + direction.y * maxDistance * 0.5,
                eyePos.z + direction.z * maxDistance * 0.5);
        Selector.selectNearbyEntities(store, searchCenter, maxDistance * 0.6, candidate -> {
            BoundingBox bb = store.getComponent(candidate, BoundingBox.getComponentType());
            if (bb == null) return;
            TransformComponent t = store.getComponent(candidate, TransformComponent.getComponentType());
            if (t == null) return;
            Vector3d ePos = t.getPosition();
            if (CollisionMath.intersectRayAABB(eyePos, direction,
                    ePos.x, ePos.y, ePos.z,
                    bb.getBoundingBox(), minMax)) {
                double rayT = minMax.x;
                if (rayT >= 0 && rayT <= maxDistance && rayT < bestRayT[0]) {
                    bestRayT[0] = rayT;
                    bestMatch.set(candidate);
                }
            }
        }, candidate -> candidate.getIndex() != casterRef.getIndex());
        return bestMatch.get();
    }

    /**
     * All entity hits along the ray sorted by ray-parameter t ascending, capped
     * at {@code maxHits}. {@code inflateRadius} widens the broad-phase
     * {@link Selector} radius so wide-AABB targets slightly off-axis still
     * become candidates.
     */
    @Nonnull
    public static List<Hit> pickPiercing(@Nonnull Store<EntityStore> store,
                                         @Nonnull Ref<EntityStore> casterRef,
                                         @Nonnull Vector3d eyePos,
                                         @Nonnull Vector3d direction,
                                         double maxDistance,
                                         double inflateRadius,
                                         int maxHits) {
        List<Hit> hits = new ArrayList<>();
        if (maxHits <= 0) return hits;
        Vector2d minMax = new Vector2d();
        Vector3d searchCenter = new Vector3d(
                eyePos.x + direction.x * maxDistance * 0.5,
                eyePos.y + direction.y * maxDistance * 0.5,
                eyePos.z + direction.z * maxDistance * 0.5);
        double searchRadius = maxDistance * 0.6 + Math.max(0.0, inflateRadius);
        Selector.selectNearbyEntities(store, searchCenter, searchRadius, candidate -> {
            BoundingBox bb = store.getComponent(candidate, BoundingBox.getComponentType());
            if (bb == null) return;
            TransformComponent t = store.getComponent(candidate, TransformComponent.getComponentType());
            if (t == null) return;
            Vector3d ePos = t.getPosition();
            if (CollisionMath.intersectRayAABB(eyePos, direction,
                    ePos.x, ePos.y, ePos.z,
                    bb.getBoundingBox(), minMax)) {
                double rayT = minMax.x;
                if (rayT >= 0 && rayT <= maxDistance) {
                    hits.add(new Hit(candidate, rayT, ePos.x, ePos.y, ePos.z));
                }
            }
        }, candidate -> candidate.getIndex() != casterRef.getIndex());

        if (hits.size() > 1) {
            hits.sort(Comparator.comparingDouble(h -> h.rayT));
        }
        if (hits.size() > maxHits) {
            return new ArrayList<>(hits.subList(0, maxHits));
        }
        return hits;
    }

    /**
     * One entity hit along the ray. {@code entityX/Y/Z} is the candidate's
     * transform position at pick time - useful for impact SFX placement when
     * the caller doesn't want to recompute from {@code eyePos + direction*rayT}.
     */
    public static final class Hit {
        @Nonnull public final Ref<EntityStore> ref;
        public final double rayT;
        public final double entityX;
        public final double entityY;
        public final double entityZ;

        Hit(@Nonnull Ref<EntityStore> ref, double rayT,
            double entityX, double entityY, double entityZ) {
            this.ref = ref;
            this.rayT = rayT;
            this.entityX = entityX;
            this.entityY = entityY;
            this.entityZ = entityZ;
        }
    }
}
