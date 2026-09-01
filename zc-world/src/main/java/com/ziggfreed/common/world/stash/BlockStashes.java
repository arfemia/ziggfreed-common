package com.ziggfreed.common.world.stash;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.world.record.BlockRecordSection;

/**
 * The library's per-block stash store: one {@link BlockStash} per block position, kept on the
 * block's own chunk section (the {@link BlockRecordSection} substrate, registered once under
 * {@value #REGISTRY_ID}) and saved and loaded with the chunk. Every consumer reads and writes the
 * same store, so two mods asking "what is this block holding?" can never disagree.
 *
 * <p><b>An unloaded section answers "no stash" and is never loaded.</b> The accessors take the
 * section ref the caller already resolved; a caller that could not resolve one has its answer.
 *
 * <p><b>Whoever mutates, marks.</b> {@link #ensureStashAt} (when it creates) and
 * {@link #removeStashAt} (when it removes) flag the owning section for a save themselves; a caller
 * that mutates a fetched stash in place - adds to a pile, advances a clock - calls
 * {@link #markDirty} once when done, or the change survives only until the section's next unload.
 *
 * <p><b>World-thread only</b> throughout, like every other chunk-store read. Registration happens
 * once at library setup ({@link BlockStashBootstrap}); a failed registration degrades every read
 * to "no stash was ever stored" and every write to a no-op rather than taking the server down.
 */
public final class BlockStashes {

    /** The id the stash component registers under; also its key in a saved chunk. */
    public static final String REGISTRY_ID = "ZigBlockStash";

    /** Null until {@link #register} runs; a handle that failed registration degrades to no-op. */
    @Nullable
    private static volatile BlockRecordSection.Handle<BlockStash> handle;

    private BlockStashes() {
    }

    /**
     * Register the stash component on the chunk-store registry. Call once at plugin setup, BEFORE
     * any world loads (the library's own bootstrap does; a consumer never calls this). Never
     * throws.
     */
    public static void register(@Nonnull ComponentRegistryProxy<ChunkStore> registry) {
        handle = BlockRecordSection.register(registry, REGISTRY_ID, BlockStash.CODEC);
    }

    /** Test seam: forget the registration, so a fixture can register against its own registry. */
    static void resetForTests() {
        handle = null;
    }

    /** Whether the stash component is registered this boot. */
    public static boolean isRegistered() {
        BlockRecordSection.Handle<BlockStash> h = handle;
        return h != null && h.isRegistered();
    }

    /**
     * The stash at this position, or null when there is none (or none can be told). The caller who
     * mutates it calls {@link #markDirty} when done.
     */
    @Nullable
    public static BlockStash stashAt(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
        BlockRecordSection.Handle<BlockStash> h = handle;
        return h != null ? h.get(accessor, sectionRef, x, y, z) : null;
    }

    /**
     * The stash at this position, created empty when absent (creation marks the section dirty; a
     * caller's own later mutation still calls {@link #markDirty}). Null only when the component
     * could not be registered or the ref is not valid.
     */
    @Nullable
    public static BlockStash ensureStashAt(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
        BlockRecordSection.Handle<BlockStash> h = handle;
        return h != null ? h.ensureAndGet(accessor, sectionRef, x, y, z) : null;
    }

    /**
     * Remove the stash at this position outright (marking the section dirty when one was there).
     * What becomes of the contents is the CALLER's business, settled before this call.
     *
     * @return true when a stash was actually removed
     */
    public static boolean removeStashAt(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
        BlockRecordSection.Handle<BlockStash> h = handle;
        return h != null && h.remove(accessor, sectionRef, x, y, z);
    }

    /** Visit every stash in this section (section-local coordinates), in no defined order. */
    public static void forEachInSection(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef,
            @Nonnull BlockRecordSection.Visitor<BlockStash> visitor) {
        BlockRecordSection.Handle<BlockStash> h = handle;
        if (h != null) {
            h.forEach(accessor, sectionRef, visitor);
        }
    }

    /** How many blocks in this section carry a stash. */
    public static int countInSection(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef) {
        BlockRecordSection.Handle<BlockStash> h = handle;
        return h != null ? h.count(accessor, sectionRef) : 0;
    }

    /**
     * Flag the owning section as needing a save. Call once after mutating a fetched stash in
     * place; without it the engine has no reason to write the section out.
     */
    public static void markDirty(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef) {
        BlockRecordSection.Handle<BlockStash> h = handle;
        if (h != null) {
            h.markDirty(accessor, sectionRef);
        }
    }
}
