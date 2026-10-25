package com.ziggfreed.common.damage.onhit;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

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
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSourceType;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.damage.weakspot.WeakSpotSettingsAsset;
import com.ziggfreed.common.damage.weakspot.WeakSpotSettingsConfig;
import com.ziggfreed.common.damage.weakspot.WeakSpotSystem;
import com.ziggfreed.common.stats.condition.Nearby;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.util.SafeLog;

/**
 * The crit step ({@link Crits}): in the Filter damage group, right after the weak-spot step ({@link WeakSpotSystem},
 * itself after the route step and the use gate), so a critical hit multiplies the whole hit, a weak spot's bonus
 * included, and a refused hit arrives cancelled. A consumer whose fold must see the critical amount orders itself
 * {@code AFTER} this class. Any attacker, a creature as much as a player (R860); a projectile hit counts its
 * shooter's stats.
 *
 * <p><b>Cost.</b> A damage-over-time tick never asks. A hit by an attacker whose chance stat, chance sources and
 * conditional stats are all 0 (every one until something raises them) pays a few stat reads and returns. Otherwise
 * the chance is read for this hit (its conditional terms against this target), rolled once, and a critical hit is
 * multiplied, marked ({@link Crits#hitKey}), announced ({@link ZigCritEvent}) and shown to the attacker alone, while
 * the owner's {@code CritText} and the attacker's own choice ({@link Crits#wordWanted}) want the word.
 */
public final class CritSystem extends DamageEventSystem {

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, WeakSpotSystem.class));

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
            if (damage.isCancelled() || !(damage.getAmount() > 0f)
                    || !(damage.getSource() instanceof Damage.EntitySource source)
                    || source.getSourceType() == DamageSourceType.DoT) {
                return;
            }
            Ref<EntityStore> attacker = source.getRef();
            if (attacker == null || !attacker.isValid()) {
                return;
            }
            EntityStatMap stats = store.getComponent(attacker, EntityStatMap.getComponentType());
            if (stats == null || !Crits.mayCrit(attacker, stats)) {
                return;
            }
            Ref<EntityStore> target = chunk.getReferenceTo(index);
            if (Nearby.same(attacker, target) || !TypedDamageBonuses.attackerAllowed(store, attacker)
                    || !Crits.ruleAllows(store, attacker, target, damage)) {
                return;
            }
            double chance = Crits.chance(Crits.chancePoints(store, attacker, target, damage, stats));
            if (chance <= 0.0 || ThreadLocalRandom.current().nextDouble() >= chance) {
                return;
            }
            double multiplier = Crits.multiplier(Crits.damagePoints(store, attacker, damage, stats));
            damage.setAmount((float) (damage.getAmount() * multiplier));
            damage.putMetaObject(Crits.hitKey(), (float) multiplier);
            OnHitEvents.fireCrit(store, commandBuffer, attacker, target, damage, multiplier);
            WeakSpotSettingsAsset settings = WeakSpotSettingsConfig.getInstance().settings();
            if (settings.critTextEnabled() && Crits.wordWanted(store, attacker)) {
                HitWords.show(store, attacker, target, Crits.TEXT_KEY, settings.critTextColor(), Crits.TEXT_ANGLE);
            }
        } catch (Throwable t) {
            SafeLog.warn("[crit] the crit step failed: " + t.getMessage());
        }
    }
}
