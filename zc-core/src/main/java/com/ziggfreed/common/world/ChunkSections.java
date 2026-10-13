package com.ziggfreed.common.world;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/**
 * The chunk section holding a block, resolved the way the engine's own reader does on Update 7:
 * {@code ChunkStore.getChunkSectionReferenceAtBlock}, a null or invalid ref read as absent, then a
 * component off that section ({@code BlockSection}, {@code EnvironmentSection}, a library section).
 * The one spelling of that lookup for zc-world's {@code BlockOps}, {@code BuildPermission} and
 * placed-block ledger and for {@link SectionBlockCursor}.
 *
 * <p><b>It never loads a chunk.</b> The lookup is the synchronous map read, so a section not in
 * memory answers null. Each engine call is made directly, so it links the same on both server lines;
 * a throw (a {@code LinkageError} included) reaches the caller, which keeps its own reporting.
 *
 * <p><b>World-thread only</b>, like the store it reads.
 */
public final class ChunkSections {

    private ChunkSections() {
    }

    /** The valid ref of the section holding this block, or null when it is not in memory. */
    @Nullable
    public static Ref<ChunkStore> at(@Nonnull ChunkStore chunks, int x, int y, int z) {
        Ref<ChunkStore> sectionRef = chunks.getChunkSectionReferenceAtBlock(x, y, z);
        return sectionRef != null && sectionRef.isValid() ? sectionRef : null;
    }

    /**
     * The {@code type} component of the section holding this block, or null when the section is not in
     * memory or carries none.
     */
    @Nullable
    public static <C extends Component<ChunkStore>> C component(@Nonnull ChunkStore chunks, int x, int y, int z,
                                                               @Nonnull ComponentType<ChunkStore, C> type) {
        Ref<ChunkStore> sectionRef = at(chunks, x, y, z);
        return sectionRef != null ? chunks.getStore().getComponent(sectionRef, type) : null;
    }
}
