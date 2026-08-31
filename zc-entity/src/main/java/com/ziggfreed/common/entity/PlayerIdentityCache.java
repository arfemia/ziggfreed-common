package com.ziggfreed.common.entity;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Resolves a live {@link Player} object to its player UUID <b>off the world thread</b>.
 *
 * <p><b>Why this exists.</b> Several engine seams hand a callback a bare {@link Player} and nothing
 * else - the world map's per-player {@code MarkerProvider} is the shipped example, and it runs on
 * the per-world WorldMap thread, not the world thread. A bare {@code Player} exposes no
 * non-deprecated identity accessor at all, and the non-deprecated replacement (the
 * {@link UUIDComponent} on the player entity) is a {@code Store} read that is only legal on the
 * world thread. This cache closes that gap: the world-thread half resolves the UUID where the read
 * is legal, the off-thread half is a plain map lookup.
 *
 * <p><b>Population.</b> {@link #onPlayerReady} runs on EVERY {@code PlayerReadyEvent}, not just the
 * first one of a connection. That is deliberate and load-bearing: the engine installs a BRAND NEW
 * {@code Player} component instance for the same ongoing connection when a player is reset onto
 * another world, so a cache populated once at login would go on answering for an object nothing
 * else references any more. Re-populating on every ready event means the live instance is always
 * mapped, whatever the engine swapped underneath.
 *
 * <p><b>Nothing is pinned.</b> Two independent mechanisms keep a superseded {@code Player} from
 * outliving its use. Keys are identity-comparing WEAK references drained through a
 * {@link ReferenceQueue}, so an instance the engine has dropped is collectable and its entry is
 * reaped on the next write; and {@link #onPlayerDisconnect} evicts BY UUID, so every instance that
 * ever mapped to a departing player goes at once, however many swaps that connection saw.
 *
 * <p><b>Threading.</b> {@link #uuidOf} is a lock-free {@link ConcurrentHashMap} read, safe from any
 * thread. {@link #onPlayerReady} hops onto the player's own world before touching the store, so it
 * is safe to register from anywhere. Every method is fully try-guarded and never throws into a
 * caller; a lookup that has not been populated yet returns {@code null}, and a caller is expected
 * to fail CLOSED on that rather than reach for a deprecated fallback.
 *
 * <p><b>Wiring.</b> {@code EntityBootstrap} registers both handlers itself at library setup, so a
 * consumer needs no setup: call {@link #uuidOf} and go.
 */
public final class PlayerIdentityCache {

    private PlayerIdentityCache() {
    }

    /** Cleared keys land here (see {@link #drainCleared}); reaped on every write. */
    private static final ReferenceQueue<Player> CLEARED = new ReferenceQueue<>();

    /** Live {@code Player} instance -> that player's UUID. Identity-keyed, weakly held. */
    private static final Map<PlayerKey, UUID> BY_PLAYER = new ConcurrentHashMap<>();

    // ---- lookup (any thread) ----

    /**
     * The UUID of the player this {@code Player} object belongs to, or {@code null} when it has not
     * been cached yet (or was already evicted). Safe from any thread.
     *
     * <p>A {@code null} answer is a "not known here, not yet" - treat it as a reason to do nothing
     * this pass, never as a reason to fall back to a deprecated identity accessor.
     */
    @Nullable
    public static UUID uuidOf(@Nonnull Player player) {
        try {
            return BY_PLAYER.get(new PlayerKey(player, null));
        } catch (Throwable t) {
            return null;
        }
    }

    /** How many live {@code Player} instances are currently mapped (diagnostics / tests). */
    public static int size() {
        return BY_PLAYER.size();
    }

    // ---- population / eviction ----

    /**
     * Map {@code player} to {@code uuid}, replacing any prior mapping for that same instance. Public
     * so a consumer holding both halves on the world thread can seed the cache directly; the normal
     * path is {@link #onPlayerReady}.
     */
    public static void put(@Nonnull Player player, @Nonnull UUID uuid) {
        try {
            drainCleared();
            BY_PLAYER.put(new PlayerKey(player, CLEARED), uuid);
        } catch (Throwable t) {
            SafeLog.warn("[identity] could not cache a player identity: " + t.getMessage());
        }
    }

    /**
     * Drop EVERY {@code Player} instance mapped to {@code uuid}. Keyed by the uuid rather than by an
     * instance precisely because one connection can have been through several {@code Player}
     * objects, and a disconnect must leave none of them behind.
     */
    public static void evict(@Nonnull UUID uuid) {
        try {
            drainCleared();
            BY_PLAYER.values().removeIf(uuid::equals);
        } catch (Throwable t) {
            SafeLog.warn("[identity] could not evict a player identity: " + t.getMessage());
        }
    }

    /** Forget every mapping (server shutdown / tests). */
    public static void clear() {
        try {
            BY_PLAYER.clear();
            drainCleared();
        } catch (Throwable t) {
            SafeLog.warn("[identity] could not clear the identity cache: " + t.getMessage());
        }
    }

    // ---- engine hooks (registered by EntityBootstrap) ----

    /**
     * Cache the ready player's identity. Hops onto that player's own world first, because the UUID
     * comes from a component read and a {@code Store} may only be touched on its owning world
     * thread. A player with no world (already gone) is skipped.
     */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player.getWorld();
            if (world == null) {
                return;
            }
            world.execute(() -> resolveAndCache(player, ref));
        } catch (Throwable t) {
            SafeLog.warn("[identity] player-ready identity caching failed: " + t.getMessage());
        }
    }

    /** Evict the departing player's identity (by UUID, so every instance mapping to it goes). */
    public static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef playerRef = event.getPlayerRef();
            UUID uuid = playerRef != null ? playerRef.getUuid() : null;
            if (uuid != null) {
                evict(uuid);
            }
        } catch (Throwable t) {
            SafeLog.warn("[identity] player-disconnect identity eviction failed: " + t.getMessage());
        }
    }

    /** World thread: read the non-deprecated {@link UUIDComponent} and cache what it says. */
    private static void resolveAndCache(@Nonnull Player player, @Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            if (store == null) {
                return;
            }
            UUIDComponent uuidComponent = store.getComponent(ref, UUIDComponent.getComponentType());
            if (uuidComponent == null) {
                return;
            }
            UUID uuid = uuidComponent.getUuid();
            if (uuid != null) {
                put(player, uuid);
            }
        } catch (Throwable t) {
            SafeLog.warn("[identity] identity resolve failed on the world thread: " + t.getMessage());
        }
    }

    /** Reap entries whose {@code Player} has been collected. Cheap: a poll on an empty queue. */
    private static void drainCleared() {
        Reference<? extends Player> dead;
        while ((dead = CLEARED.poll()) != null) {
            BY_PLAYER.remove(dead);
        }
    }

    /**
     * An IDENTITY-comparing weak map key. {@code Player} overrides {@code equals}/{@code hashCode}
     * by uuid, which would silently fold two distinct instances of the same player onto one entry;
     * this key compares the referents by reference instead, and caches the identity hash up front so
     * it stays stable (and removable) after the referent has been collected.
     */
    private static final class PlayerKey extends WeakReference<Player> {

        private final int hash;

        PlayerKey(@Nonnull Player player, @Nullable ReferenceQueue<Player> queue) {
            super(player, queue);
            this.hash = System.identityHashCode(player);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true; // the reaping path: a cleared key still removes its own entry
            }
            if (!(other instanceof PlayerKey key)) {
                return false;
            }
            Player mine = get();
            return mine != null && mine == key.get();
        }
    }
}
