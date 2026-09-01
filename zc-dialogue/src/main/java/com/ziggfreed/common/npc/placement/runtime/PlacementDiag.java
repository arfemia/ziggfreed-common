package com.ziggfreed.common.npc.placement.runtime;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.util.SafeLog;

/**
 * Once-per-world diagnostic lines for the placement engine's silent decision branches.
 *
 * <p>Every branch that leaves an NPC absent - an anchor group resolving nothing, a spawn provider
 * answering null, a marker entity arriving without the component that identifies it - is
 * individually correct to act on quietly, and collectively indistinguishable from the engine not
 * running at all. Each such branch reports here: the FIRST occurrence per world and key logs at
 * INFO, repeats stay silent, so a 41-attempt retry loop costs one line instead of 41.
 *
 * <p>Evicted per world through {@link WorldEvictors}, so a single-use instance world's keys do not
 * accumulate for the life of the process.
 */
final class PlacementDiag {

    private static final Map<World, Set<String>> SEEN = new ConcurrentHashMap<>();

    static {
        WorldEvictors.registerEvictor(SEEN::remove);
    }

    private PlacementDiag() {
    }

    /** Log {@code message} at INFO, once per (world, key); repeats are silent. */
    static void once(@Nullable World world, @Nonnull String key, @Nonnull String message) {
        try {
            if (world == null) {
                SafeLog.info(message);
                return;
            }
            if (SEEN.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet()).add(key)) {
                SafeLog.info(message);
            }
        } catch (Throwable t) {
            // A diagnostic must never cost the decision it describes.
        }
    }
}
