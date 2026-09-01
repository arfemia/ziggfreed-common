package com.ziggfreed.common.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
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
 * family, which is deprecated wholesale. Beside the IO sit the block IDENTITY reads - what a block
 * item id IS (its base block behind a state variant, its containing item, that item's tags and
 * resource types) - so every consumer comparing or classifying blocks resolves identity the same
 * way.
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

    /**
     * The rotation index stored for the block at this world position (rotation lives in its own
     * storage layer beside the block ids, read through the same section resolution as
     * {@link #blockItemIdAt}), or null when it cannot be told - the section is not loaded, or the
     * read failed. The index feeds the engine's {@code RotationTuple.get(index)} and the rotation
     * parameter of {@link #setBlock(ChunkStore, int, int, int, String, int)}, so a caller
     * replacing a block preserves its facing by reading here first.
     */
    @Nullable
    public static Integer rotationIndexAt(@Nonnull ChunkStore chunkStore, int x, int y, int z) {
        try {
            Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) {
                return null;
            }
            BlockSection blocks = chunkStore.getStore().getComponent(sectionRef, BlockSection.getComponentType());
            if (blocks == null) {
                return null;
            }
            return blocks.getRotationIndex(x, y, z);
        } catch (Throwable t) {
            SafeLog.fine("[block] rotationIndexAt(" + x + ", " + y + ", " + z + ") failed: " + t.getMessage());
            return null;
        }
    }

    /** {@link Store} form of {@link #rotationIndexAt(ChunkStore, int, int, int)}. */
    @Nullable
    public static Integer rotationIndexAt(@Nonnull Store<ChunkStore> store, int x, int y, int z) {
        return rotationIndexAt(store.getExternalData(), x, y, z);
    }

    // ==================== identity reads ====================

    /**
     * The BASE block item id behind a possibly state-variant block id. A block's authored state
     * family registers each state as its OWN block type under a generated id, and a placed state
     * variant reads back under that generated id; this resolves it to the block that authored the
     * family in one hop, so two readings of one block (lit and unlit, open and closed) compare
     * equal. A base block, an unknown id and any failed lookup all answer the INPUT itself - never
     * null for a non-null input - so the result is always usable as an id.
     */
    @Nonnull
    public static String baseItemIdOf(@Nonnull String blockItemId) {
        try {
            BlockType type = BlockType.getAssetMap().getAsset(blockItemId);
            if (type == null) {
                return blockItemId;
            }
            String base = type.getDefaultStateKey();
            return base != null ? base : blockItemId;
        } catch (Throwable t) {
            SafeLog.fine("[block] baseItemIdOf('" + blockItemId + "') failed: " + t.getMessage());
            return blockItemId;
        }
    }

    /**
     * The Item asset containing this block type (a block type can only be defined inside an item,
     * so this is the block's whole item-side identity: tags, resource types, display), or null
     * when it cannot be told - an unknown id, one of the engine's few synthetic block types that
     * have no item, or a failed lookup. A state-variant block answers its BASE block's item, since
     * the whole state family shares one containing item.
     */
    @Nullable
    public static Item itemOf(@Nonnull String blockItemId) {
        try {
            BlockType type = BlockType.getAssetMap().getAsset(blockItemId);
            return type != null ? type.getItem() : null;
        } catch (Throwable t) {
            SafeLog.fine("[block] itemOf('" + blockItemId + "') failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * The raw tag map of the ITEM containing this block type (tag name to values, e.g.
     * {@code Type} to {@code ["Rock"]}), or null when the block or its item cannot be resolved.
     * Tags are read off the ITEM deliberately: a block type carries a tag map of its own, but in
     * practice it is empty - the authored tags live on the containing item - so a matcher that
     * read the block-side map would see nothing. An unmodifiable view.
     */
    @Nullable
    public static Map<String, String[]> rawTagsOf(@Nonnull String blockItemId) {
        try {
            Item item = itemOf(blockItemId);
            if (item == null) {
                return null;
            }
            AssetExtraInfo.Data data = item.getData();
            return data != null ? data.getRawTags() : null;
        } catch (Throwable t) {
            SafeLog.fine("[block] rawTagsOf('" + blockItemId + "') failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * The resource-type ids of the ITEM containing this block type, in authored order (the first
     * entry is the item's primary resource type), or null when the block or its item cannot be
     * resolved. An item with no resource types answers an EMPTY list - that is an answer, not a
     * failure. Like {@link #rawTagsOf}, this reads the item, where the authored identity lives.
     */
    @Nullable
    public static List<String> resourceTypeIdsOf(@Nonnull String blockItemId) {
        try {
            Item item = itemOf(blockItemId);
            if (item == null) {
                return null;
            }
            ItemResourceType[] types = item.getResourceTypes();
            if (types == null) {
                return List.of();
            }
            List<String> ids = new ArrayList<>(types.length);
            for (ItemResourceType type : types) {
                if (type != null && type.id != null) {
                    ids.add(type.id);
                }
            }
            return List.copyOf(ids);
        } catch (Throwable t) {
            SafeLog.fine("[block] resourceTypeIdsOf('" + blockItemId + "') failed: " + t.getMessage());
            return null;
        }
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
