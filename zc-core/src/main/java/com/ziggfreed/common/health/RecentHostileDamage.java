package com.ziggfreed.common.health;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.ziggfreed.common.util.SafeLog;

/**
 * The recent hostile damage a player took, the one reading of it: per player, what creatures' hits took from them in
 * the last ten seconds ({@link HostileDamagePool#WINDOW_MS}), and what a heal may still be credited for (R788). A heal
 * given earns credit (a skill's healing XP, a quest's health healed) only for the health it restores out of the healed
 * player's pool, and draws the pool down by that much ({@link #draw}), so no credit is ever paid for damage a creature
 * did not deal: a fall, fire, drowning, a hit of one's own or another player's (PvP never counts, even in a PvP
 * world) fills nothing.
 *
 * <p>Fed after a hit lands ({@link HostileDamageSystems.Taken}, in the Inspect group: the damage as applied, never a
 * cancelled hit) when its source is a creature, a non-player NPC, its own blow, projectile or damage over time (an
 * effect it put on). Never more than the health the player is missing: every hit they take, whatever dealt it, first
 * holds the pool to what they were missing just before it, and so does every heal before it draws, so health a potion,
 * food or regeneration gave back is never credited again. Kept in memory, per player, bounded
 * ({@link HostileDamagePool#MAX_ENTRIES} entries each) and pruned as it fades, and forgotten when the player leaves
 * the server or their world, or dies. Works on a server running the library alone; a consumer only reads it.
 *
 * <p>World thread: a player's pool is filled and drawn on their own world's thread; the map is concurrent so a
 * departure on another thread can forget it.
 */
public final class RecentHostileDamage {

    /** How long a creature's hit stays creditable, in milliseconds. */
    public static final long WINDOW_MS = HostileDamagePool.WINDOW_MS;

    private static final Map<UUID, HostileDamagePool> POOLS = new ConcurrentHashMap<>();

    private RecentHostileDamage() {
    }

    /**
     * A hit landed on {@code player} just now, from any source: their pool is first held to {@code missingBefore},
     * the health they were missing just before it (so damage some other heal already gave back, a potion's or
     * regeneration's, never stays creditable, and a hit taken at full health leaves nothing behind it), then
     * {@code creatureDamage}, the part a creature dealt (0 for a fall, fire or a player's hit), is added. A
     * {@code missingBefore} that is not a number holds nothing back.
     */
    public static void landed(@Nonnull UUID player, double missingBefore, double creatureDamage) {
        long now = System.currentTimeMillis();
        HostileDamagePool pool = POOLS.get(player);
        if (pool != null) {
            pool.clampTo(missingBefore, now);
        }
        if (creatureDamage > 0.0 && Double.isFinite(creatureDamage)) {
            POOLS.computeIfAbsent(player, id -> new HostileDamagePool()).add(creatureDamage, now);
        } else if (pool != null && pool.isEmpty(now)) {
            POOLS.remove(player, pool);
        }
    }

    /** Does {@code player} hold a pool now (a hit on them that a creature did not deal still has one to hold back)? */
    public static boolean holds(@Nullable UUID player) {
        return player != null && POOLS.containsKey(player);
    }

    /**
     * The health a player was missing just before a hit of {@code hit} landed, read after it from their health now
     * ({@code current}) and its maximum, never under 0; NaN when either reading is not a number. What
     * {@link #landed} holds a pool to.
     */
    public static double missingBefore(double current, double max, double hit) {
        return HostileDamagePool.missingBefore(current, max, hit);
    }

    /** What creatures' hits took from {@code player} in the last ten seconds, less what heals already drew; 0 for none. */
    public static double held(@Nullable UUID player) {
        HostileDamagePool pool = player == null ? null : POOLS.get(player);
        if (pool == null) {
            return 0.0;
        }
        long now = System.currentTimeMillis();
        double held = pool.held(now);
        if (pool.isEmpty(now)) {
            POOLS.remove(player, pool);
        }
        return held;
    }

    /**
     * A heal restores {@code restored} health on {@code player}, who is missing {@code missing} as it lands: hold the
     * pool to {@code missing} first (damage some other heal already gave back is not creditable), then draw what of
     * the heal it can credit, and say how much, never more than {@code restored} nor than the pool holds; 0 for a
     * player with no recent creature damage. A {@code missing} that is not a number holds nothing back.
     */
    public static double draw(@Nullable UUID player, double restored, double missing) {
        HostileDamagePool pool = player == null ? null : POOLS.get(player);
        if (pool == null) {
            return 0.0;
        }
        long now = System.currentTimeMillis();
        pool.clampTo(missing, now);
        double drawn = pool.draw(restored, now);
        if (pool.isEmpty(now)) {
            POOLS.remove(player, pool);
        }
        return drawn;
    }

    /** Forget {@code player}'s pool (they died, left their world or the server). */
    public static void forget(@Nullable UUID player) {
        if (player != null) {
            POOLS.remove(player);
        }
    }

    /**
     * Is {@code damage}'s source a creature that may fill {@code victim}'s pool: an entity (a blow, the shooter of a
     * projectile, the one who put a damage-over-time effect on) that is a non-player NPC and not the victim itself?
     * Never throws: an unreadable source is no creature.
     */
    static boolean fromCreature(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Damage damage,
            @Nonnull Ref<EntityStore> victim) {
        try {
            if (!(damage.getSource() instanceof Damage.EntitySource source)) {
                return false;
            }
            Ref<EntityStore> attacker = source.getRef();
            if (attacker == null || !attacker.isValid() || attacker.equals(victim)) {
                return false;
            }
            return accessor.getComponent(attacker, PlayerRef.getComponentType()) == null
                    && accessor.getComponent(attacker, NPCEntity.getComponentType()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A player left a world: their pool goes (the next world's creatures fill a new one). */
    public static void onLeftWorld(@Nonnull RemovedPlayerFromWorldEvent event) {
        try {
            PlayerRef player = event.getHolder().getComponent(PlayerRef.getComponentType());
            if (player != null) {
                forget(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.fine("[health] a departing player's recent damage could not be forgotten: " + t.getMessage());
        }
    }

    /** A player left the server: their pool goes. */
    public static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            if (player != null) {
                forget(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.fine("[health] a departing player's recent damage could not be forgotten: " + t.getMessage());
        }
    }
}
