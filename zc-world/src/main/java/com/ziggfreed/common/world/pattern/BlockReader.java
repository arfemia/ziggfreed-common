package com.ziggfreed.common.world.pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.world.BlockOps;

/**
 * The block-reading seam a pattern match walks over: world position in, block ITEM id out. A
 * {@code null} answer strictly means "cannot tell" (an unloaded section, a failed read) and always
 * FAILS the cell being tested - a match never loads a chunk to find out. Air answers the engine's
 * own empty key ({@code "Empty"}), so a cell that must be empty is testable like any other.
 *
 * <p>The pure form is any lambda over fixture data; {@link #over(ChunkStore)} is the live wiring.
 */
@FunctionalInterface
public interface BlockReader {

    /** The block item id at this world position, or null when it cannot be told. */
    @Nullable
    String blockItemIdAt(int x, int y, int z);

    /**
     * The live reader over a world's chunk store, delegating to
     * {@link BlockOps#blockItemIdAt(ChunkStore, int, int, int)}: an unloaded section answers null
     * and is never loaded. WORLD-THREAD ONLY, like all chunk access.
     */
    @Nonnull
    static BlockReader over(@Nonnull ChunkStore chunkStore) {
        return (x, y, z) -> BlockOps.blockItemIdAt(chunkStore, x, y, z);
    }
}
