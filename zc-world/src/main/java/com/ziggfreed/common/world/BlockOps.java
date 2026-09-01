package com.ziggfreed.common.world;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.ziggfreed.common.util.SafeLog;

/**
 * Read and write single blocks at world coordinates over the engine's CURRENT block surface:
 * {@code ChunkStore.getChunkSectionReferenceAtBlock} to find the section, {@code BlockSection} to
 * read, {@code BlockOperations.setBlock} / {@code setBlockInteractionState} to write. This is the
 * one place the library touches raw block IO, so a consumer probing a neighbour cell or swapping a
 * block never reaches for the engine's older {@code World.getBlock} / {@code WorldChunk} accessor
 * family, which is deprecated wholesale.
 *
 * <p><b>An unloaded chunk answers, it is never loaded.</b> Every method resolves the section with
 * the synchronous map lookup, which returns null for a section not in memory and never triggers a
 * load or generation. A read degrades to {@code null} ("cannot tell") and a write to {@code false};
 * a caller that must reach cold chunks requests them itself.
 *
 * <p><b>Fail-closed throughout.</b> An unresolved id, a missing section and any engine throw all
 * degrade to {@code null}/{@code false} with a guarded log line, never a throw into the caller.
 *
 * <p><b>World-thread only</b> (resolves section refs and components on the owning world's store);
 * the caller guarantees the thread.
 */
public final class BlockOps {

    private BlockOps() {
    }

    // ==================== read ====================

    /**
     * The block ITEM id at this world position ({@code BlockType} ids are item ids: the asset's
     * filename), or null when it cannot be told - the section is not loaded, or the read failed.
     * Air answers the engine's own empty key ({@code "Empty"}), never null: null strictly means
     * "no answer", so a matcher can tell an empty cell from an unknowable one.
     */
    @Nullable
    public static String blockItemIdAt(@Nonnull ChunkStore chunkStore, int x, int y, int z) {
        try {
            Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) {
                return null;
            }
            BlockSection blocks = chunkStore.getStore().getComponent(sectionRef, BlockSection.getComponentType());
            if (blocks == null) {
                return null;
            }
            BlockType type = BlockType.getAssetMap().getAsset(blocks.get(x, y, z));
            return type != null ? type.getId() : null;
        } catch (Throwable t) {
            SafeLog.fine("[block] blockItemIdAt(" + x + ", " + y + ", " + z + ") failed: " + t.getMessage());
            return null;
        }
    }

    /** {@link Store} form of {@link #blockItemIdAt(ChunkStore, int, int, int)}. */
    @Nullable
    public static String blockItemIdAt(@Nonnull Store<ChunkStore> store, int x, int y, int z) {
        return blockItemIdAt(store.getExternalData(), x, y, z);
    }

    // ==================== write ====================

    /**
     * Set the block at this world position to the named block item, carrying an explicit rotation
     * index (the caller preserving a replaced block's rotation reads it first and passes it here).
     * Runs the engine's full setBlock (heightmap, particles, block entity swap, lighting, fillers,
     * physics). Returns true when the block actually changed; false for an unresolved id, an
     * unloaded section, a same-block no-op, or any engine throw.
     */
    public static boolean setBlock(@Nonnull ChunkStore chunkStore, int x, int y, int z,
            @Nonnull String blockItemId, int rotationIndex) {
        try {
            int id = BlockType.getAssetMap().getIndex(blockItemId);
            if (id == Integer.MIN_VALUE) {
                SafeLog.warn("[block] setBlock: block type '" + blockItemId + "' is not registered");
                return false;
            }
            BlockType type = BlockType.getAssetMap().getAsset(id);
            if (type == null) {
                SafeLog.warn("[block] setBlock: block type '" + blockItemId + "' resolved no asset");
                return false;
            }
            Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) {
                SafeLog.fine("[block] setBlock: section not loaded at (" + x + ", " + y + ", " + z + ")");
                return false;
            }
            return BlockOperations.setBlock(chunkStore, sectionRef, x, y, z, id, type,
                    rotationIndex, FillerBlockUtil.NO_FILLER, SetBlockSettings.NONE);
        } catch (Throwable t) {
            SafeLog.warn("[block] setBlock '" + blockItemId + "' at (" + x + ", " + y + ", " + z
                    + ") failed: " + t.getMessage());
            return false;
        }
    }

    /** {@link #setBlock(ChunkStore, int, int, int, String, int)} with no rotation. */
    public static boolean setBlock(@Nonnull ChunkStore chunkStore, int x, int y, int z,
            @Nonnull String blockItemId) {
        return setBlock(chunkStore, x, y, z, blockItemId, RotationTuple.NONE_INDEX);
    }

    /**
     * Move the block at this world position to one of its OWN authored interaction states (the
     * {@code State} family a block asset declares, e.g. an on/off pair), keeping its rotation. The
     * engine resolves the sibling block type for the state name and swaps to it; a block with no
     * state family, or an unknown state name, changes nothing. Returns true when the engine call
     * was reached with a resolved block; false when the position could not be read or the call
     * threw. Pass {@code force} to re-apply a state the block already shows.
     */
    public static boolean setInteractionState(@Nonnull ChunkStore chunkStore, int x, int y, int z,
            @Nonnull String state, boolean force) {
        try {
            Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) {
                SafeLog.fine("[block] setInteractionState: section not loaded at (" + x + ", " + y + ", " + z + ")");
                return false;
            }
            BlockSection blocks = chunkStore.getStore().getComponent(sectionRef, BlockSection.getComponentType());
            if (blocks == null) {
                return false;
            }
            BlockType current = BlockType.getAssetMap().getAsset(blocks.get(x, y, z));
            if (current == null) {
                return false;
            }
            BlockOperations.setBlockInteractionState(chunkStore, sectionRef, x, y, z, current, state, force);
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[block] setInteractionState '" + state + "' at (" + x + ", " + y + ", " + z
                    + ") failed: " + t.getMessage());
            return false;
        }
    }
}
