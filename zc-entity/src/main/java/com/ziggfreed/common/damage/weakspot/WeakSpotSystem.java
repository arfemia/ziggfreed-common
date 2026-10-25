package com.ziggfreed.common.damage.weakspot;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;
import org.joml.Vector4d;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.asset.type.model.config.DetailBox;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSourceType;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.damage.onhit.HitWords;
import com.ziggfreed.common.stats.damage.TypedDamageBonusSystem;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.usegate.UseGateDamageSystem;
import com.ziggfreed.common.util.SafeLog;

/**
 * The weak-spot step ({@link WeakSpots}): in the Filter damage group, after the engine's outgoing step and the
 * library's route step ({@link TypedDamageBonusSystem}, itself after it) and after the use gate (a refused hit is
 * already cancelled), so the bonus multiplies the whole hit, flats included, on top as a crit does. A consumer
 * whose fold must see the weak-spot amount (a lifesteal read off the hit) orders itself {@code AFTER} this class.
 *
 * <p><b>Cost.</b> A hit by an entity whose weak-spot stats are all 0 (every one until something raises them) pays
 * one component read and a stat read each and returns. A damage-over-time tick never asks. Only a nonzero attacker
 * asks the ways in, cheapest first: vanilla's own headshot or backstab branch (a meta read, decided once a hit and
 * applied to each of its causes), the target's window effects (one pass over its active effects against a table), the
 * rear arc (two transform reads), the head box (the target's detail boxes), and last the target's health against the
 * attacker's {@code WeakSpot_FoeLow}; once the hit is a weak spot, only a way whose own stat pays more is asked. A weak
 * spot puts {@link WeakSpots#hitKey} on the hit and shows the attacker the combat text.
 */
public final class WeakSpotSystem extends DamageEventSystem {

    /** Where the word floats, beside the crit's (the crit step's, at -20). */
    private static final float TEXT_ANGLE = 20f;

    /** The word's lang key, in this module's {@code ziggfreedcommon.weakspot.lang}. */
    static final String TEXT_KEY = "ziggfreedcommon.weakspot.combattext";

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, UseGateDamageSystem.class),
            new SystemDependency<>(Order.AFTER, TypedDamageBonusSystem.class));

    @Nonnull
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
        try {
            if (!(damage.getSource() instanceof Damage.EntitySource source)
                    || source.getSourceType() == DamageSourceType.DoT) {
                return;
            }
            Ref<EntityStore> attacker = source.getRef();
            if (attacker == null || !attacker.isValid()) {
                return;
            }
            EntityStatMap stats = store.getComponent(attacker, EntityStatMap.getComponentType());
            if (stats == null) {
                return;
            }
            Ref<EntityStore> target = chunk.getReferenceTo(index);
            // Read before the cancel check: only a hit's first damage names vanilla's branch, and every later damage
            // of the same hit (another cause) takes the same answer, even when the first was refused.
            VanillaWeakSpots.Paid vanilla = VanillaWeakSpots.paidForHit(damage, target);
            Ways ways = Ways.of(stats);
            if (ways.base() <= 0.0 && !ways.anyWay() && !ways.sourcesMayPay()) {
                return;
            }
            if (damage.isCancelled() || damage.getAmount() <= 0f
                    || target.equals(attacker) || !TypedDamageBonuses.attackerAllowed(store, attacker)) {
                return;
            }
            double base = ways.base() + WeakSpots.sourcePoints(store, attacker, damage, stats);
            WeakSpotSettingsAsset settings = WeakSpotSettingsConfig.getInstance().settings();
            // Vanilla's own headshot or backstab branch is a weak spot (when that way counts here) and, either way,
            // the bonus it already paid counts against ours: one weak-spot bonus a hit, the larger of the two.
            boolean vanillaHead = vanilla != null && vanilla.head() && settings.headEnabled();
            boolean vanillaBack = vanilla != null && vanilla.backstab() && settings.backstabEnabled();
            int found = waysOf(store, damage, target, attacker, settings, ways, vanillaHead || vanillaBack);
            if (vanillaHead) {
                found |= HEAD;
            }
            if (vanillaBack) {
                found |= BACK;
            }
            if (found == 0) {
                return;
            }
            double points = base + ((found & HEAD) != 0 ? ways.head() : 0.0) + ((found & BACK) != 0 ? ways.back() : 0.0)
                    + ((found & WINDOW) != 0 ? ways.staggered() : 0.0);
            if (points <= 0.0) {
                return;
            }
            double ours = WeakSpots.bonusFraction(points, WeakSpots.ceilingPercent(stats));
            double paid = vanilla == null ? 0.0 : vanilla.share();
            double factor = WeakSpots.factorOver(ours, paid);
            if (factor > 1.0) {
                damage.setAmount((float) (damage.getAmount() * factor));
            }
            damage.putMetaObject(WeakSpots.hitKey(), (float) Math.max(0.0, ours - paid));
            if (settings.textEnabled()) {
                HitWords.show(store, attacker, target, TEXT_KEY, settings.textColor(), TEXT_ANGLE);
            }
        } catch (Throwable t) {
            SafeLog.warn("[weakspot] the weak-spot step failed: " + t.getMessage());
        }
    }

    /** The hit is a weak spot through a window effect on the target. */
    static final int WINDOW = 1;

    /** The hit is a weak spot from behind. */
    static final int BACK = 2;

    /** The hit is a weak spot on the head. */
    static final int HEAD = 4;

    /** The hit is a weak spot on a target low enough for the attacker's {@code WeakSpot_FoeLow}. */
    static final int LOW = 8;

    /** The attacker's weak-spot stats for one hit, read once. */
    record Ways(double base, double head, double back, double staggered, double foeLow, boolean sourcesMayPay) {

        static Ways of(@Nonnull EntityStatMap stats) {
            return new Ways(WeakSpots.damagePoints(stats), WeakSpots.pointsOf(stats, WeakSpots.HEAD_STAT),
                    WeakSpots.pointsOf(stats, WeakSpots.BACK_STAT), WeakSpots.pointsOf(stats, WeakSpots.STAGGERED_STAT),
                    WeakSpots.pointsOf(stats, WeakSpots.FOE_LOW_STAT), WeakSpots.anySource(stats));
        }

        /** Does a way of its own pay more on top (a head, back or window stat raised)? */
        boolean anyWay() {
            return head > 0.0 || back > 0.0 || staggered > 0.0;
        }
    }

    /**
     * The ways the hit is a weak spot, as a mask of {@link #WINDOW}, {@link #BACK}, {@link #HEAD} and {@link #LOW},
     * cheapest first: once the hit is a weak spot (or {@code alreadyWeak}, vanilla's own branch), only a way that pays
     * more of its own is asked.
     */
    private static int waysOf(@Nonnull Store<EntityStore> store, @Nonnull Damage damage,
            @Nonnull Ref<EntityStore> target, @Nonnull Ref<EntityStore> attacker,
            @Nonnull WeakSpotSettingsAsset settings, @Nonnull Ways ways, boolean alreadyWeak) {
        int found = 0;
        boolean weak = alreadyWeak;
        if ((!weak || ways.staggered() > 0.0) && WeakSpotWindows.openFor(store, target, attacker)) {
            found |= WINDOW;
            weak = true;
        }
        TransformComponent targetAt = store.getComponent(target, TransformComponent.getComponentType());
        if ((!weak || ways.back() > 0.0) && targetAt != null && settings.backstabEnabled()
                && settings.backstabArc() > 0.0) {
            TransformComponent attackerAt = store.getComponent(attacker, TransformComponent.getComponentType());
            if (attackerAt != null && behind(attackerAt.getPosition().x, attackerAt.getPosition().z,
                    targetAt.getPosition().x, targetAt.getPosition().z, targetAt.getRotation().yaw(),
                    settings.backstabArc())) {
                found |= BACK;
                weak = true;
            }
        }
        if ((!weak || ways.head() > 0.0) && targetAt != null && settings.headEnabled()
                && inHead(store, damage, target, targetAt, settings.headMargin())) {
            found |= HEAD;
            weak = true;
        }
        if (!weak && ways.foeLow() > 0.0 && lowEnough(store, target, ways.foeLow())) {
            found |= LOW;
        }
        return found;
    }

    /** Is the target at or under {@code percent} of its health? */
    private static boolean lowEnough(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> target,
            double percent) {
        EntityStatMap targetStats = store.getComponent(target, EntityStatMap.getComponentType());
        EntityStatValue health = targetStats == null ? null : targetStats.get(DefaultEntityStatTypes.getHealth());
        return health != null && health.getMax() > 0f
                && health.get() * 100.0 / health.getMax() <= Math.min(100.0, percent);
    }

    /** The hit's location inside one of the target's {@code Head} detail boxes, grown by {@code margin}. */
    private static boolean inHead(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Damage damage,
            @Nonnull Ref<EntityStore> target, @Nonnull TransformComponent targetAt, double margin) {
        Vector4d hit = damage.getIfPresentMetaObject(Damage.HIT_LOCATION);
        if (hit == null) {
            return false;
        }
        BoundingBox bounds = accessor.getComponent(target, BoundingBox.getComponentType());
        Map<String, DetailBox[]> details = bounds == null ? null : bounds.getDetailBoxes();
        if (details == null || details.isEmpty()) {
            return false;
        }
        Vector3d at = targetAt.getPosition();
        float yaw = targetAt.getRotation().yaw();
        for (Map.Entry<String, DetailBox[]> entry : details.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase(VanillaWeakSpots.HEAD) || entry.getValue() == null) {
                continue;
            }
            for (DetailBox box : entry.getValue()) {
                if (box == null || box.getBox() == null) {
                    continue;
                }
                // Placed as the engine's own projectile test places it: the offset turned with the body, the box
                // itself axis-aligned (EntityRefCollisionProvider.isColliding).
                Vector3d center = new Vector3d(box.getOffset()).rotateY(yaw).add(at);
                Box shape = box.getBox();
                if (inside(hit.x, hit.y, hit.z, center.x + shape.min.x, center.y + shape.min.y, center.z + shape.min.z,
                        center.x + shape.max.x, center.y + shape.max.y, center.z + shape.max.z, margin)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Is an attacker at ({@code ax}, {@code az}) behind a target at ({@code tx}, {@code tz}) facing {@code yaw}
     * (radians), within an arc of {@code arcDegrees} centred on straight behind? The game's own dagger backstab
     * test (DamageEntityInteraction's {@code AngledDamage}: the angle from the target to the attacker, against the
     * target's body yaw, 180 degrees being behind). Pure.
     */
    static boolean behind(double ax, double az, double tx, double tz, double yaw, double arcDegrees) {
        if (!(arcDegrees > 0.0)) {
            return false;
        }
        double between = Math.atan2(ax - tx, az - tz);
        double relative = between + Math.PI - yaw;
        double turn = Math.PI * 2.0;
        double diff = Math.PI - relative + Math.PI;
        diff = diff - turn * Math.floor(diff / turn) - Math.PI;
        return Math.abs(diff) < Math.toRadians(Math.min(360.0, arcDegrees) / 2.0);
    }

    /** Is a point inside a box grown by {@code margin} on every side? Pure. */
    static boolean inside(double x, double y, double z, double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, double margin) {
        double m = Math.max(0.0, margin);
        return x >= minX - m && x <= maxX + m && y >= minY - m && y <= maxY + m && z >= minZ - m && z <= maxZ + m;
    }
}
