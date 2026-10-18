package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
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
import com.ziggfreed.common.track.source.TrackSources;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires {@code DEAL_DAMAGE} for every hit a player lands on a creature, but only while some track source listens
 * for it ({@link TrackSources#listens}): a hit is the most frequent moment in the game and nothing else reads it,
 * so with no source naming the kind both systems here stop at one lookup.
 *
 * <p><b>Reactions only.</b> The moment goes to the moment listeners ({@link ProgressDispatch#react}), never to the
 * quest and achievement engines: the MMO fires {@code DEAL_DAMAGE} into the shared engines from its own damage
 * system, so an engine half here would count every hit twice there.
 *
 * <p><b>Capped at what the creature had left.</b> The engine rounds and applies a hit between the Filter and the
 * Inspect groups and clamps health at its minimum, so the Inspect amount of a killing blow can be far more than the
 * creature had. {@link HealthBefore}, in the Filter group (which never moves health), notes the creature's health
 * on the damage itself, and {@link Dealt}, in the Inspect group, fires the applied amount capped at it, exactly
 * ({@link DamagePayload#dealt}), the whole number rounded into the moment's amount.
 *
 * <p>Target the creature's id (as a kill names it), Qualifier the attacker's held item (a sword, the bow that shot
 * the arrow; none for a turret), credited the way a kill is (the attacker when it is a player, else whoever the
 * composed {@code KillAttribution} says it acts for). A player hit, an invulnerable creature, a cancelled or empty
 * hit and a self-hit fire nothing.
 */
public final class ZigDamageProducer {

    /** The objective kind this producer feeds. */
    public static final String KIND = TrackSources.DEAL_DAMAGE;

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
            if (!TrackSources.listens(KIND) || damage.isCancelled()) {
                return;
            }
            try {
                float health = HealthUtil.current(store, chunk.getReferenceTo(index));
                if (Float.isFinite(health)) {
                    damage.putMetaObject(HEALTH_BEFORE, health);
                }
            } catch (Throwable t) {
                SafeLog.fine("[progression] a hit's health before could not be noted: " + t.getMessage());
            }
        }
    }

    /** The Inspect-group half: the hit as applied, capped and fired. */
    public static final class Dealt extends DamageEventSystem {

        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getInspectDamageGroup();
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return Query.any();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            if (!TrackSources.listens(KIND) || damage.isCancelled()) {
                return;
            }
            try {
                fire(chunk.getReferenceTo(index), store, commandBuffer, damage);
            } catch (Throwable t) {
                SafeLog.warn("[progression] damage dispatch failed", t);
            }
        }

        private static void fire(@Nonnull Ref<EntityStore> victim, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            float amount = damage.getAmount();
            if (!(amount > 0f) || store.getComponent(victim, Player.getComponentType()) != null
                    || store.getComponent(victim, Invulnerable.getComponentType()) != null) {
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
            ProgressDispatch.react(store, credited, commandBuffer, KIND, ZigMobKillProducer.victimId(store, victim),
                    HeldItemUtil.heldItemId(store, attacker), Math.round(dealt), new DamagePayload(victim, damage, dealt));
        }
    }
}
