package com.ziggfreed.common.stats.condition;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCalculatorSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSourceType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageCalculator;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.combat.DamageClass;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The reads a conditional stat's {@code Hit} group asks of one hit ({@link ConditionalStatAsset.Hit}): the first hit,
 * the target's lock, distance and height, the hit's class, projectile and damage-over-time. All but the class and the
 * first hit read the entities as they stand; those two are decided ONCE per hit, on its first damage (the one the
 * engine puts the interaction's calculator on), and every later damage of the same strike (another cause, the same
 * source object and target) takes the same answer, as the weak-spot step decides vanilla's branch.
 */
public final class HitReads {

    /** The last hit decided on this thread: its source object and target, and what was read on it. */
    private static final class LastHit {
        @Nullable Damage.Source source;
        @Nullable Ref<EntityStore> target;
        boolean first;
        @Nullable String damageClass;
    }

    private static final ThreadLocal<LastHit> LAST = ThreadLocal.withInitial(LastHit::new);

    private HitReads() {
    }

    /**
     * Does the hit meet every leaf of {@code hit} (null or empty: always)? {@code target} may be null (a hit whose
     * target is not known), which no target read meets.
     */
    public static boolean meets(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nullable Ref<EntityStore> target, @Nonnull Damage damage, @Nullable ConditionalStatAsset.Hit hit) {
        if (hit == null || hit.isEmpty()) {
            return true;
        }
        if (hit.overTime() != null && hit.overTime() != isOverTime(damage)) {
            return false;
        }
        if (hit.projectile() != null && hit.projectile() != (damage.getSource() instanceof Damage.ProjectileSource)) {
            return false;
        }
        if (hit.onSelf() != null && hit.onSelf() != (target != null && Nearby.Targets.same(holder, target))) {
            return false;
        }
        Set<String> classes = hit.classes();
        if (!classes.isEmpty()) {
            String damageClass = decide(accessor, damage, target).damageClass;
            if (damageClass == null || !classes.contains(damageClass)) {
                return false;
            }
        }
        if (target == null) {
            return hit.firstHit() == null && hit.notTargeted() == null && hit.minDistance() == null
                    && hit.maxDistance() == null && hit.minHeightAbove() == null;
        }
        if (hit.firstHit() != null && hit.firstHit() != decide(accessor, damage, target).first) {
            return false;
        }
        if (hit.notTargeted() != null) {
            boolean notTargeted = Nearby.Targets.isCreature(accessor, target)
                    && !Nearby.Targets.isTargeting(accessor, target, holder);
            if (hit.notTargeted() != notTargeted) {
                return false;
            }
        }
        if (hit.minDistance() != null || hit.maxDistance() != null || hit.minHeightAbove() != null) {
            Vector3d from = Nearby.positionOf(accessor, holder);
            Vector3d to = Nearby.positionOf(accessor, target);
            if (from == null || to == null) {
                return false;
            }
            double distance = from.distance(to);
            if (hit.minDistance() != null && distance < hit.minDistance()) {
                return false;
            }
            if (hit.maxDistance() != null && distance > hit.maxDistance()) {
                return false;
            }
            if (hit.minHeightAbove() != null && from.y - to.y < hit.minHeightAbove()) {
                return false;
            }
        }
        return true;
    }

    /** Is the hit a damage-over-time tick? */
    public static boolean isOverTime(@Nonnull Damage damage) {
        return damage.getSource() instanceof Damage.EntitySource source && source.getSourceType() == DamageSourceType.DoT;
    }

    /** The hit's first-hit answer and class, decided on its first damage and shared by the rest of the strike. */
    @Nonnull
    private static LastHit decide(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Damage damage,
            @Nullable Ref<EntityStore> target) {
        LastHit last = LAST.get();
        DamageCalculatorSystems.DamageSequence sequence = null;
        try {
            sequence = damage.getIfPresentMetaObject(DamageCalculatorSystems.DAMAGE_SEQUENCE);
        } catch (Throwable ignored) {
            // A meta store that cannot be read: decided as a hit carrying no calculator.
        }
        boolean sameStrike = last.source == damage.getSource() && last.target == target;
        if (sequence == null && sameStrike) {
            return last;
        }
        last.source = damage.getSource();
        last.target = target;
        last.damageClass = classOf(sequence);
        // An attacker with no id is never noted, so it never reads as a first hit (it would on every hit).
        UUID attacker = target != null && damage.getSource() instanceof Damage.EntitySource entity
                && entity.getRef() != null ? FirstHits.idOf(accessor, entity.getRef()) : null;
        last.first = attacker != null && !FirstHits.hitBefore(attacker, target);
        return last;
    }

    @Nullable
    private static String classOf(@Nullable DamageCalculatorSystems.DamageSequence sequence) {
        if (sequence == null) {
            return null;
        }
        DamageCalculator calculator = sequence.getDamageCalculator();
        DamageClass damageClass = calculator == null ? null : calculator.getDamageClass();
        return damageClass == null ? null : damageClass.name().toLowerCase(Locale.ROOT);
    }
}
