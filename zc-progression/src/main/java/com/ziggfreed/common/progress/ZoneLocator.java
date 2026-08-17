package com.ziggfreed.common.progress;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.WorldMapTracker;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * WHERE a player is right now, as the {@link ZoneRef} a zone-scoped objective is matched against.
 *
 * <p>There is exactly one of these because a zone-scoped objective is unsatisfiable without it: an
 * event dispatched with no zone never matches an objective that names one, so a surface that
 * forgets to resolve a zone does not degrade, it silently switches that content off. Every dispatch
 * that can reach zone-scoped content asks here, and gets the same answer.
 *
 * <p>Read off the engine's own {@link WorldMapTracker}, which the engine refreshes per player at
 * about 1 Hz, so this is an in-memory field read rather than a worldgen query and is cheap enough
 * to sit on a per-break path. Answers null when nothing is resolved (a void world, a world with no
 * ChunkGenerator worldgen, a player who has not spawned yet): zone-scoped content fails closed
 * there by design, and null is the ordinary answer rather than an error.
 *
 * <p>World thread only, like every other store read.
 */
public final class ZoneLocator {

    private ZoneLocator() {
    }

    /** The player's current zone, or null when the engine has none resolved for them. */
    @Nullable
    public static ZoneRef currentZone(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return null;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return null;
            }
            WorldMapTracker tracker = player.getWorldMapTracker();
            if (tracker == null) {
                return null;
            }
            WorldMapTracker.ZoneDiscoveryInfo zone = tracker.getCurrentZone();
            if (zone == null) {
                return null;
            }
            return new ZoneRef(zone.zoneName(), zone.regionName());
        } catch (Throwable unavailable) {
            return null;
        }
    }
}
