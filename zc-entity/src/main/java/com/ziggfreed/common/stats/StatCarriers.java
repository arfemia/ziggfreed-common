package com.ziggfreed.common.stats;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Who carries the library's stats, and how often each carrier is read (R860): every living entity with an
 * {@code EntityStatMap}, a creature as much as a player, so a creature's {@code DamageBonus_<Type>},
 * {@code DamageResist_<Type>}, {@code MoveSpeed} and conditional stats count as a player's do. A player is read
 * every tick; a creature on the holder pass's cadence, every {@value #CREATURE_EVERY_TICKS} world ticks (about four
 * times a second), staggered by its entity index so the creatures of a world spread over the ticks. A pass that finds
 * a creature's stats of its family all at 0 and nothing of its family on it does nothing more.
 *
 * <p>Every per-entity pass of the library's stat families (the stat-to-effect sync, the conditional holder pass, the
 * effect-length pass) asks this one rule, so a creature is read on the same tick by each of them, in their order.
 */
public final class StatCarriers {

    /** How many world ticks apart a creature is read: about four times a second at the engine's 30 ticks. */
    public static final int CREATURE_EVERY_TICKS = 8;

    private StatCarriers() {
    }

    /** The carriers a stat pass walks: a living entity with stats and effects (players and creatures alike). */
    @Nonnull
    public static Query<EntityStore> livingWithEffects() {
        return Query.and(EntityStatMap.getComponentType(), EffectControllerComponent.getComponentType(),
                Query.not(DeathComponent.getComponentType()));
    }

    /** The carriers a pass that reads only stats walks: a living entity with stats. */
    @Nonnull
    public static Query<EntityStore> livingWithStats() {
        return Query.and(EntityStatMap.getComponentType(), Query.not(DeathComponent.getComponentType()));
    }

    /** Is the chunk's archetype a player's? */
    public static boolean isPlayer(@Nonnull ArchetypeChunk<EntityStore> chunk) {
        return chunk.getArchetype().contains(PlayerRef.getComponentType());
    }

    /**
     * Is the entity at {@code index} of {@code chunk} read on this tick: a player always, a creature on its staggered
     * turn of the world's tick ({@link #dueAt}). A creature whose world cannot be read is read.
     */
    public static boolean due(@Nonnull Store<EntityStore> store, @Nonnull ArchetypeChunk<EntityStore> chunk,
            int index) {
        if (isPlayer(chunk)) {
            return true;
        }
        try {
            World world = store.getExternalData().getWorld();
            return world == null || dueAt(world.getTick(), chunk.getReferenceTo(index).getIndex());
        } catch (Throwable t) {
            return true;
        }
    }

    /** Is a creature at entity index {@code entityIndex} read on world tick {@code tick}? Pure. */
    public static boolean dueAt(long tick, int entityIndex) {
        return Math.floorMod(tick + entityIndex, CREATURE_EVERY_TICKS) == 0;
    }
}
