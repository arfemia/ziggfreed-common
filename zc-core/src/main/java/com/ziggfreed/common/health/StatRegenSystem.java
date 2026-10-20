package com.ziggfreed.common.health;

import java.time.Instant;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.damage.DamageDataComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Pays the {@link StatRegen} stats every tick, for every living player: {@code Regen_Health} unless a hit landed on
 * them inside {@link StatRegen#HEALTH_PAUSE}, {@code Regen_Mana} always, each as a percent of the stat's maximum a
 * second, scaled by the tick. A player with neither stat above zero costs two stat reads and nothing else; a full
 * stat is left alone. Registered once, by {@link RegenBootstrap} (the system registry is class-keyed).
 */
public final class StatRegenSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Query<EntityStore> query = Query.and(PlayerRef.getComponentType(),
            EntityStatMap.getComponentType(), Query.not(DeathComponent.getComponentType()));

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        EntityStatMap stats = chunk.getComponent(index, EntityStatMap.getComponentType());
        if (stats == null) {
            return;
        }
        double health = StatRegen.healthPoints(stats);
        double mana = StatRegen.manaPoints(stats);
        if (!(health > 0.0) && !(mana > 0.0)) {
            return;
        }
        try {
            if (health > 0.0 && !tookDamageRecently(store, chunk.getReferenceTo(index), commandBuffer)) {
                regain(stats, DefaultEntityStatTypes.getHealth(), health, dt, true);
            }
            if (mana > 0.0) {
                regain(stats, DefaultEntityStatTypes.getMana(), mana, dt, false);
            }
        } catch (Throwable t) {
            // A stat index not ready yet (a player still loading): nothing to regain this tick.
        }
    }

    /** Add one tick's regeneration to the stat at {@code statIndex}, up to its maximum. */
    private static void regain(@Nonnull EntityStatMap stats, int statIndex, double percentPerSecond, float dt,
            boolean livingOnly) {
        EntityStatValue value = stats.get(statIndex);
        if (value == null) {
            return;
        }
        float current = value.get();
        float max = value.getMax();
        if ((livingOnly && current <= 0f) || current >= max) {
            return;
        }
        float amount = StatRegen.amountFor(max, percentPerSecond, dt);
        if (amount > 0f) {
            stats.addStatValue(statIndex, amount);
        }
    }

    /**
     * Whether the player was hit inside {@link StatRegen#HEALTH_PAUSE}, off the engine's own
     * {@code DamageDataComponent.getLastDamageTime()} (the field the engine's {@code NoDamageTaken} condition reads
     * for NPC regeneration, stamped on every hit), so anything that damages a player counts. Unreadable state answers
     * false, which leaves regeneration on rather than switching it off on a guess.
     */
    private static boolean tookDamageRecently(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            DamageDataComponent damage = store.getComponent(ref, DamageDataComponent.getComponentType());
            if (damage == null) {
                return false;
            }
            Instant last = damage.getLastDamageTime();
            TimeResource time = commandBuffer.getResource(TimeResource.getResourceType());
            return last != null && time != null && time.getNow().isBefore(last.plus(StatRegen.HEALTH_PAUSE));
        } catch (Throwable t) {
            return false;
        }
    }
}
