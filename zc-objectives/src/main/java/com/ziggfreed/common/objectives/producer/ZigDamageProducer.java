package com.ziggfreed.common.objectives.producer;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.HeldItemUtil;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.stats.condition.ConditionalStatSystems;
import com.ziggfreed.common.track.source.TrackSources;
import com.ziggfreed.common.track.world.TrackWorldRules;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires {@code DEAL_DAMAGE} for every hit a player lands on a creature: the one producer of the kind, so a hit
 * counts once for every reader of it.
 *
 * <p><b>Everyone hears it, once.</b> The moment goes through {@link DamageMoments#fire} into
 * {@link ProgressDispatch#fire}: first to every moment
 * listener (a track source paying on hits, a stat file counting damage dealt), then to the quest and achievement
 * engines, so an objective or a criterion on damage advances by the same amount the listeners saw. No consumer
 * dispatches the kind into the engines itself, or every hit would count twice there.
 *
 * <p><b>Capped at what the creature had left.</b> The engine rounds and applies a hit between the Filter and the
 * Inspect groups and clamps health at its minimum, so the Inspect amount of a killing blow can be far more than the
 * creature had. {@link HealthBefore}, in the Filter group (which never moves health), notes the creature's health
 * on the damage itself, and {@link Dealt}, in the Inspect group, fires the applied amount capped at it, exactly
 * ({@link DamagePayload#dealt}), the whole number rounded into the moment's amount.
 *
 * <p>Target the creature's id (as a kill names it), Qualifier the attacker's held item (a sword, the bow that shot
 * the arrow; none for a turret), credited the way a kill is (the attacker when it is a player, else whoever the
 * composed {@code KillAttribution} says it acts for). A hit on another player fires only in a world whose track
 * rules write {@code Gain.PlayerHits} true ({@link TrackWorldRules#paysPlayerHits}, R864, R895), Target
 * {@value #PLAYER_TARGET}, and then for every reader alike (a track source, a quest, a statistic); anywhere else it
 * fires nothing, the default. An invulnerable victim, a cancelled or empty hit and a self-hit fire nothing, nor does
 * a hit a consumer's {@link DamageHitFilters} filter keeps out (a training dummy, a passive animal its own combat does
 * not count).
 */
public final class ZigDamageProducer {

    /** The objective kind this producer feeds. */
    public static final String KIND = TrackSources.DEAL_DAMAGE;

    /** The Target of a hit on another player, where a world's rules let one count. */
    public static final String PLAYER_TARGET = "Player";

    /** The creature's health before the hit, noted on the damage in the Filter group. */
    @Nonnull
    static final MetaKey<Float> HEALTH_BEFORE = Damage.META_REGISTRY.registerMetaObject();

    private ZigDamageProducer() {
    }

    /** Register both systems. Called once from the producers' setup. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new HealthBefore());
        plugin.getEntityStoreRegistry().registerSystem(new Dealt());
    }

    /** The Filter-group half: the creature's health before the engine applies the hit. */
    public static final class HealthBefore extends DamageEventSystem {

        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getFilterDamageGroup();
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return Query.any();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            // Only a hit the Inspect half could credit is worth noting: one struck by an entity (no fall, no
            // fire), on a creature, or on a player where the world's rules count one. Every other hit stops here.
            if (damage.isCancelled() || ZigMobKillProducer.resolveAttackerRef(damage) == null) {
                return;
            }
            try {
                Ref<EntityStore> victim = chunk.getReferenceTo(index);
                if (!counts(store, victim)) {
                    return;
                }
                float health = HealthUtil.current(store, victim);
                if (Float.isFinite(health)) {
                    damage.putMetaObject(HEALTH_BEFORE, health);
                }
            } catch (Throwable t) {
                SafeLog.fine("[progression] a hit's health before could not be noted: " + t.getMessage());
            }
        }
    }

    /**
     * The Inspect-group half: the hit as applied, capped and fired. Ordered before zc-entity's first-hit record, so a
     * reader of the moment (a proc's {@code Hit.FirstHit}) sees the hit as it was before the record noted it.
     */
    public static final class Dealt extends DamageEventSystem {

        @Nonnull
        private final Set<Dependency<EntityStore>> dependencies = Set.of(
                new SystemDependency<>(Order.BEFORE, ConditionalStatSystems.FirstHitRecord.class));

        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getInspectDamageGroup();
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
            if (damage.isCancelled()) {
                return;
            }
            try {
                fire(chunk, index, store, commandBuffer, damage);
            } catch (Throwable t) {
                SafeLog.warn("[progression] damage dispatch failed", t);
            }
        }

        private static void fire(@Nonnull ArchetypeChunk<EntityStore> chunk, int index,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull Damage damage) {
            Ref<EntityStore> victim = chunk.getReferenceTo(index);
            float amount = damage.getAmount();
            if (!(amount > 0f) || store.getComponent(victim, Invulnerable.getComponentType()) != null
                    || !counts(store, victim)) {
                return;
            }
            Ref<EntityStore> attacker = ZigMobKillProducer.resolveAttackerRef(damage);
            if (attacker == null || attacker.equals(victim)) {
                return;
            }
            Ref<EntityStore> credited = ZigMobKillProducer.creditedPlayer(store, attacker);
            if (credited == null) {
                return;
            }
            Float before = damage.getIfPresentMetaObject(HEALTH_BEFORE);
            double dealt = before == null || !(before > 0f) ? amount : Math.min(amount, before);
            String target = isPlayer(store, victim) ? PLAYER_TARGET : ZigMobKillProducer.victimId(store, victim);
            DamageMoments.fire(new DamageHit(chunk, index, store, victim, attacker, credited, damage), commandBuffer,
                    target, HeldItemUtil.heldItemId(store, attacker), dealt, new DamagePayload(victim, damage, dealt));
        }
    }

    /**
     * Does a hit on {@code victim} count at all: always on a creature, and on a player only where the world's track
     * rules turn {@code Gain.PlayerHits} on (R864, R895; off by default).
     */
    static boolean counts(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victim) {
        return !isPlayer(store, victim) || TrackWorldRules.paysPlayerHits(store.getExternalData().getWorld());
    }

    private static boolean isPlayer(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> victim) {
        return store.getComponent(victim, Player.getComponentType()) != null;
    }
}
