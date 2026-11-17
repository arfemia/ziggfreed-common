package com.ziggfreed.common.proc;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.meta.MetaKey;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A Kill moment's overkill: how much more the killing blow dealt than the health it took. The engine keeps the
 * killing blow ({@code DeathComponent.getDeathInfo}, its amount as it landed) but not the health the creature had just
 * before it, so {@link Stamp} notes that health on every hit just before the engine takes it off, while a Kill proc
 * listens, and {@link #overkill} reads the two. World thread.
 */
final class KillBlows {

    /** The health the struck one had as the hit was about to land, noted on the hit. */
    private static final class Meta {
        static final MetaKey<Float> HEALTH_BEFORE = Damage.META_REGISTRY.registerMetaObject();
    }

    private KillBlows() {
    }

    /**
     * The overkill of the blow that slew {@code slain}: its amount less the health it had just before, never below 0;
     * 0 when either is not known. Never throws.
     */
    static double overkill(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> slain) {
        return overkill(blow(accessor, slain));
    }

    /** The overkill of {@code blow}, a killing blow: its amount less the health noted on it, 0 when not known. */
    static double overkill(@Nullable Damage blow) {
        try {
            Float before = blow == null ? null : blow.getIfPresentMetaObject(Meta.HEALTH_BEFORE);
            return before == null ? 0.0 : overkill(blow.getAmount(), before);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** The blow that slew {@code slain} (the engine's {@code DeathComponent.getDeathInfo}), or null when not known. */
    @Nullable
    static Damage blow(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> slain) {
        if (slain == null || !slain.isValid()) {
            return null;
        }
        try {
            DeathComponent death = accessor.getComponent(slain, DeathComponent.getComponentType());
            return death == null ? null : death.getDeathInfo();
        } catch (Throwable t) {
            return null;
        }
    }

    /** A blow of {@code amount} on one at {@code healthBefore}: what it dealt past their health, never below 0. Pure. */
    static double overkill(double amount, double healthBefore) {
        double over = amount - Math.max(0.0, healthBefore);
        return Double.isFinite(over) ? Math.max(0.0, over) : 0.0;
    }

    /**
     * Notes the health the struck one has on each hit about to land, after every Filter-group step and just before
     * the engine's {@code ApplyDamage} takes it off, while a Kill proc listens. A cancelled hit, a hit of nothing and
     * a hit on the dead are passed over.
     */
    public static final class Stamp extends DamageEventSystem {

        @Nonnull
        private final Set<Dependency<EntityStore>> dependencies = Set.of(
                new SystemGroupDependency<>(Order.AFTER, DamageModule.get().getGatherDamageGroup()),
                new SystemGroupDependency<>(Order.AFTER, DamageModule.get().getFilterDamageGroup()),
                new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return dependencies;
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return EntityStatMap.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            try {
                if (!Procs.listens(ProcTrigger.KILL) || damage.isCancelled() || !(damage.getAmount() > 0f)
                        || chunk.getArchetype().contains(DeathComponent.getComponentType())) {
                    return;
                }
                EntityStatMap stats = chunk.getComponent(index, EntityStatMap.getComponentType());
                EntityStatValue health = stats == null ? null : stats.get(DefaultEntityStatTypes.getHealth());
                if (health != null) {
                    damage.putMetaObject(Meta.HEALTH_BEFORE, health.get());
                }
            } catch (Throwable t) {
                Procs.failed("noting a hit's health", t);
            }
        }
    }
}
