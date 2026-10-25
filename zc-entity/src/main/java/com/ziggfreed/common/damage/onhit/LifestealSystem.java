package com.ziggfreed.common.damage.onhit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSourceType;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.stats.condition.Nearby;
import com.ziggfreed.common.stats.damage.TypedDamageBonuses;
import com.ziggfreed.common.util.SafeLog;

/**
 * The lifesteal step ({@link Lifesteal}): in the Inspect damage group, once the hit is applied, so it heals off the
 * damage the hit really dealt (every bonus, crit, armor and resistance in). Any attacker, a creature as much as a
 * player (R860); a projectile hit heals its shooter. A damage-over-time tick, a cancelled hit and a dead attacker heal
 * nothing. A hit by an attacker whose lifesteal is 0 (every one until something raises it) pays a stat read or two.
 */
public final class LifestealSystem extends DamageEventSystem {

    @Nonnull
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
            if (stats == null) {
                return;
            }
            double points = Lifesteal.points(store, attacker, damage, stats);
            if (!(points > 0.0)) {
                return;
            }
            Ref<EntityStore> target = chunk.getReferenceTo(index);
            if (Nearby.same(attacker, target)
                    || store.getComponent(attacker, DeathComponent.getComponentType()) != null
                    || !TypedDamageBonuses.attackerAllowed(store, attacker)
                    || !Lifesteal.ruleAllows(store, attacker, target, damage)) {
                return;
            }
            float heal = (float) Lifesteal.healFor(damage.getAmount(), points);
            float room = HealthUtil.max(store, attacker) - HealthUtil.current(store, attacker);
            float healed = Math.min(heal, Math.max(0f, room));
            if (healed > 0f && HealthUtil.heal(store, null, attacker, heal)) {
                OnHitEvents.fireLifesteal(store, attacker, target, damage, healed);
            }
        } catch (Throwable t) {
            SafeLog.warn("[lifesteal] the lifesteal step failed: " + t.getMessage());
        }
    }
}
