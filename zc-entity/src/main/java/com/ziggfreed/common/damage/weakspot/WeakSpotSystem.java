package com.ziggfreed.common.damage.weakspot;

import java.util.Locale;
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
import com.hypixel.hytale.protocol.CombatTextUpdate;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.model.config.DetailBox;
import com.hypixel.hytale.server.core.asset.util.ColorParseUtil;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSourceType;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.PlainText;
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
 * <p><b>Cost.</b> A hit by an entity whose {@code WeakSpotDamage} is 0 (every one until something raises it) pays
 * one component read and one stat read and returns. A damage-over-time tick never asks. Only a nonzero attacker
 * asks the ways in, cheapest first: vanilla's own headshot or backstab branch (a meta read, decided once a hit and
 * applied to each of its causes), the target's window effects (one pass
 * over its active effects against a table), the rear arc (two transform reads), the head box (the target's
 * detail boxes), and last the holder's {@code WeakSpot} grant rules. A weak spot puts {@link WeakSpots#hitKey} on the
 * hit and shows the attacker the combat text.
 */
public final class WeakSpotSystem extends DamageEventSystem {

    /** Where the word floats, beside the crit's (which a consumer draws at -20). */
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
            double points = WeakSpots.damagePoints(stats);
            if (points <= 0.0) {
                return;
            }
            Ref<EntityStore> target = chunk.getReferenceTo(index);
            // Read before the cancel check: only a hit's first damage names vanilla's branch, and every later damage
            // of the same hit (another cause) takes the same answer, even when the first was refused.
            VanillaWeakSpots.Paid vanilla = VanillaWeakSpots.paidForHit(damage, target);
            if (damage.isCancelled() || damage.getAmount() <= 0f
                    || target.equals(attacker) || !TypedDamageBonuses.attackerAllowed(store, attacker)) {
                return;
            }
            WeakSpotSettingsAsset settings = WeakSpotSettingsConfig.getInstance().settings();
            // Vanilla's own headshot or backstab branch is a weak spot (when that way counts here) and, either way,
            // the bonus it already paid counts against ours: one weak-spot bonus a hit, the larger of the two.
            boolean vanillaWay = vanilla != null && ((vanilla.head() && settings.headEnabled())
                    || (vanilla.backstab() && settings.backstabEnabled()));
            if (!vanillaWay && !isWeakSpot(store, damage, target, attacker, settings)) {
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
                showText(store, attacker, target, settings.textColor());
            }
        } catch (Throwable t) {
            SafeLog.warn("[weakspot] the weak-spot step failed: " + t.getMessage());
        }
    }

    /** Is the hit a weak spot by any way the settings and the attacker's grants allow? */
    private static boolean isWeakSpot(@Nonnull Store<EntityStore> store, @Nonnull Damage damage,
            @Nonnull Ref<EntityStore> target, @Nonnull Ref<EntityStore> attacker,
            @Nonnull WeakSpotSettingsAsset settings) {
        if (WeakSpotWindows.openFor(store, target, attacker)) {
            return true;
        }
        TransformComponent targetAt = store.getComponent(target, TransformComponent.getComponentType());
        if (targetAt != null && settings.backstabEnabled() && settings.backstabArc() > 0.0) {
            TransformComponent attackerAt = store.getComponent(attacker, TransformComponent.getComponentType());
            if (attackerAt != null && behind(attackerAt.getPosition().x, attackerAt.getPosition().z,
                    targetAt.getPosition().x, targetAt.getPosition().z, targetAt.getRotation().yaw(),
                    settings.backstabArc())) {
                return true;
            }
        }
        if (targetAt != null && settings.headEnabled() && inHead(store, damage, target, targetAt, settings.headMargin())) {
            return true;
        }
        return byGrant(store, target, attacker);
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

    /** The attacker's {@code WeakSpot} grant rules: a foe low enough, or carrying a named effect. */
    private static boolean byGrant(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> target,
            @Nonnull Ref<EntityStore> attacker) {
        WeakSpotGrantWriter.Rules rules = WeakSpotGrantWriter.rulesFor(store, attacker);
        if (rules.isEmpty()) {
            return false;
        }
        if (rules.healthBelow() > 0.0) {
            EntityStatMap targetStats = store.getComponent(target, EntityStatMap.getComponentType());
            EntityStatValue health = targetStats == null ? null : targetStats.get(DefaultEntityStatTypes.getHealth());
            if (health != null && health.getMax() > 0f && health.get() * 100.0 / health.getMax() <= rules.healthBelow()) {
                return true;
            }
        }
        if (!rules.effects().isEmpty()) {
            EffectControllerComponent ctrl = store.getComponent(target, EffectControllerComponent.getComponentType());
            if (ctrl != null) {
                for (ActiveEntityEffect active : ctrl.getActiveEffects().values()) {
                    EntityEffect effect = EntityEffect.getAssetMap().getAsset(active.getEntityEffectIndex());
                    if (effect != null && effect.getId() != null
                            && rules.effects().contains(effect.getId().toLowerCase(Locale.ROOT))) {
                        return true;
                    }
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

    /** The word over the target, to the attacker alone, when the attacker is a player who can see it. */
    private static void showText(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Ref<EntityStore> target, @Nonnull String color) {
        try {
            EntityTrackerSystems.EntityViewer viewer =
                    store.getComponent(attacker, EntityTrackerSystems.EntityViewer.getComponentType());
            if (viewer == null || !viewer.visible.contains(target)) {
                return;
            }
            // The packet takes plain text, so the word is read in the server's language, as a crit's is.
            String text = PlainText.of(Msg.key(TEXT_KEY));
            if (text.isEmpty() || text.equals(TEXT_KEY)) {
                return;
            }
            CombatTextUpdate update = new CombatTextUpdate();
            update.hitAngleDeg = TEXT_ANGLE;
            update.text = text;
            update.color = ColorParseUtil.parseColor(color);
            viewer.queueUpdate(target, update);
        } catch (Throwable t) {
            SafeLog.fine("[weakspot] the combat text failed: " + t.getMessage());
        }
    }
}
