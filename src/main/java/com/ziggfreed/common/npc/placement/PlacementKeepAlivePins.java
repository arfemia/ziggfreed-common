package com.ziggfreed.common.npc.placement;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.util.SafeLog;

/**
 * The bookkeeping behind {@code Lifecycle.KeepAlive}, and the reason it needs any.
 *
 * <p>The engine's chunk pin is REFERENCE COUNTED and has no auto-release: every
 * {@code addKeepLoaded()} must be matched by exactly one {@code removeKeepLoaded()}. A sweep runs
 * repeatedly over the same standing NPC, so pinning on each pass would raise the count forever and
 * the chunk could never unload again, in any world, for the rest of the process.
 *
 * <p>So the pin is owned per chunk, not per placement: this class tracks which placement instances
 * want a given chunk and calls the engine only at the edges - pin on the FIRST instance to want a
 * chunk, unpin on the LAST to release it. Two placements in one chunk therefore cost one pin, and
 * removing one of them does not unpin the chunk out from under the other.
 *
 * <p>A whole world's entry is dropped by a {@link WorldEvictors} evictor on world removal. That is
 * not tidiness: an instance world torn down with pins outstanding would otherwise leak both the
 * bookkeeping and (if it ever came back) the count.
 *
 * <p>World-thread only for the engine calls; every one is guarded, so an unloaded or unreachable
 * chunk degrades to "not pinned" rather than throwing into a sweep.
 */
public final class PlacementKeepAlivePins {

    /** world -> chunk index -> the placement instance keys wanting that chunk pinned. */
    private static final Map<World, Map<Long, Set<String>>> PINS = new ConcurrentHashMap<>();

    static {
        WorldEvictors.registerEvictor(PlacementKeepAlivePins::onWorldRemoved);
    }

    private PlacementKeepAlivePins() {
    }

    /**
     * Ensure {@code chunk} containing {@code (blockX, blockZ)} is pinned on behalf of
     * {@code placementKey}. Idempotent: re-registering the same key never adds a second pin.
     *
     * @return true when this call actually pinned the chunk (the first claimant)
     */
    public static boolean pin(@Nonnull World world, @Nonnull String placementKey, double blockX, double blockZ) {
        long index = chunkIndex(blockX, blockZ);
        boolean[] first = {false};
        PINS.computeIfAbsent(world, w -> new ConcurrentHashMap<>())
                .compute(index, (idx, holders) -> {
                    Set<String> set = holders != null ? holders : new LinkedHashSet<>();
                    if (set.add(placementKey) && set.size() == 1) {
                        first[0] = true;
                    }
                    return set;
                });
        if (first[0] && !addKeepLoaded(world, index)) {
            // The engine refused the pin (chunk not resident). Forget the claim so the next sweep
            // retries rather than believing a pin exists that does not.
            releaseClaim(world, index, placementKey);
            return false;
        }
        return first[0];
    }

    /**
     * Release {@code placementKey}'s claim on the chunk containing {@code (blockX, blockZ)},
     * unpinning only when it was the last claimant.
     *
     * @return true when this call actually unpinned the chunk
     */
    public static boolean unpin(@Nonnull World world, @Nonnull String placementKey, double blockX, double blockZ) {
        long index = chunkIndex(blockX, blockZ);
        boolean last = releaseClaim(world, index, placementKey);
        if (last) {
            removeKeepLoaded(world, index);
        }
        return last;
    }

    /** Does {@code placementKey} currently hold a claim in {@code world}? (diagnostics, tests) */
    public static boolean holdsClaim(@Nonnull World world, @Nonnull String placementKey) {
        Map<Long, Set<String>> byChunk = PINS.get(world);
        if (byChunk == null) {
            return false;
        }
        for (Set<String> holders : byChunk.values()) {
            if (holders.contains(placementKey)) {
                return true;
            }
        }
        return false;
    }

    /** How many chunks are pinned in {@code world} (diagnostics, tests). */
    public static int pinnedChunkCount(@Nonnull World world) {
        Map<Long, Set<String>> byChunk = PINS.get(world);
        return byChunk == null ? 0 : byChunk.size();
    }

    /**
     * Drop a world's whole pin table. Registered as a {@link WorldEvictors} evictor, so an
     * instance teardown cannot leak an entry. The engine pins go away with the world's chunks, so
     * there is nothing to unpin here.
     */
    public static void onWorldRemoved(@Nullable World world) {
        if (world != null) {
            PINS.remove(world);
        }
    }

    /** Drop every world's table (tests). */
    static void clearForTests() {
        PINS.clear();
    }

    // ==================== the pure claim core ====================

    /**
     * Apply one claim to a chunk's holder set and report whether the chunk crossed an edge. PURE
     * (no engine), so the "pin once, unpin once" arithmetic is unit-testable on its own.
     *
     * @return {@link Edge#FIRST} when the set went empty to non-empty, {@link Edge#LAST} when it
     *         went non-empty to empty, {@link Edge#NONE} otherwise
     */
    @Nonnull
    public static Edge applyClaim(@Nonnull Map<Long, Set<String>> byChunk, long chunkIndex,
            @Nonnull String placementKey, boolean claim) {
        Set<String> holders = byChunk.get(chunkIndex);
        if (claim) {
            if (holders == null) {
                holders = new LinkedHashSet<>();
                byChunk.put(chunkIndex, holders);
            }
            boolean wasEmpty = holders.isEmpty();
            holders.add(placementKey);
            return wasEmpty ? Edge.FIRST : Edge.NONE;
        }
        if (holders == null || !holders.remove(placementKey)) {
            return Edge.NONE;
        }
        if (holders.isEmpty()) {
            byChunk.remove(chunkIndex);
            return Edge.LAST;
        }
        return Edge.NONE;
    }

    /** Whether a claim change crossed the pin or unpin edge for a chunk. */
    public enum Edge {
        /** The chunk gained its first claimant: pin it. */
        FIRST,
        /** The chunk lost its last claimant: unpin it. */
        LAST,
        /** The chunk still has claimants and already had some: do nothing. */
        NONE
    }

    /** A fresh empty per-world table, for a test driving {@link #applyClaim} directly. */
    @Nonnull
    public static Map<Long, Set<String>> newChunkTable() {
        return new LinkedHashMap<>();
    }

    // ==================== engine ====================

    private static boolean releaseClaim(@Nonnull World world, long index, @Nonnull String placementKey) {
        Map<Long, Set<String>> byChunk = PINS.get(world);
        if (byChunk == null) {
            return false;
        }
        boolean[] last = {false};
        byChunk.computeIfPresent(index, (idx, holders) -> {
            if (holders.remove(placementKey) && holders.isEmpty()) {
                last[0] = true;
                return null;
            }
            return holders;
        });
        if (byChunk.isEmpty()) {
            PINS.remove(world, byChunk);
        }
        return last[0];
    }

    private static long chunkIndex(double blockX, double blockZ) {
        return ChunkUtil.indexChunkFromBlock(blockX, blockZ);
    }

    private static boolean addKeepLoaded(@Nonnull World world, long index) {
        try {
            WorldChunk chunk = world.getChunkIfLoaded(index);
            if (chunk == null) {
                return false;
            }
            chunk.addKeepLoaded();
            return true;
        } catch (Throwable t) {
            SafeLog.fine("[placement] could not pin chunk: " + t.getMessage());
            return false;
        }
    }

    private static void removeKeepLoaded(@Nonnull World world, long index) {
        try {
            WorldChunk chunk = world.getChunkIfLoaded(index);
            if (chunk != null) {
                chunk.removeKeepLoaded();
            }
        } catch (Throwable t) {
            SafeLog.fine("[placement] could not unpin chunk: " + t.getMessage());
        }
    }
}
