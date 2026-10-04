package com.ziggfreed.common.world;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Reads block ids straight off a world's loaded chunk sections, keeping the last section it resolved,
 * for a walk over many cells (a look ray, a column scan) and for a module with no zc-world edge. It is
 * the read zc-world's {@code BlockOps} makes, and the one the engine's own reader makes on Update 7:
 * {@code ChunkStore.getChunkSectionReferenceAtBlock}, then the section's {@link BlockSection}, then
 * {@code BlockSection.get} at the world coordinates (the section masks them itself). Every engine call
 * here links unchanged on the live server and on Update 7, which deletes the {@code World} block
 * accessors ({@code getBlock(x, y, z)}, {@code getBlockType(x, y, z)}).
 *
 * <p><b>It never loads a chunk.</b> The lookup answers only what is in memory, so a cell whose section
 * is not loaded, a Y outside the world, and a failed read all read {@link BlockType#EMPTY_ID}, exactly
 * like air: a ray reads them clear and a probe reads them empty. A caller that must read cold ground
 * loads it first.
 *
 * <p><b>One walk's local.</b> Make one with {@link #of(World)} on the world thread for each walk and
 * drop it when the walk ends, since a section it holds can unload between ticks. The cached section is
 * keyed by {@code ChunkUtil.chunkCoordinate}, the function the lookup itself applies, so the cache and
 * the engine always agree on which section a cell is in, negative coordinates included.
 *
 * <p><b>It never throws.</b> A world whose chunk store cannot be read gives a cursor that reads empty
 * everywhere, and a failed read reads empty and tries again on the next call. A {@code LinkageError}
 * (the read itself missing from this server build, the way the {@code World} accessors went missing on
 * Update 7) logs one WARNING per server; any other failure logs at FINE, once per cursor.
 */
public final class SectionBlockCursor {

    /** The section holding a block, as the engine resolves it; null when it is not in memory. Package-private for the test. */
    @FunctionalInterface
    interface Sections {
        @Nullable
        Section at(int x, int y, int z);
    }

    /** One resolved section's block id at a world cell inside it. Package-private for the test. */
    @FunctionalInterface
    interface Section {
        int blockId(int x, int y, int z);
    }

    /** What a world whose chunk store cannot be read answers: no section anywhere. */
    private static final Sections NO_SECTIONS = (x, y, z) -> null;

    /** One WARNING per server for a read that does not link on this server build. */
    private static final AtomicBoolean LINKAGE_REPORTED = new AtomicBoolean();

    @Nonnull
    private final Sections sections;
    private boolean cached;
    private int sectionX;
    private int sectionY;
    private int sectionZ;
    @Nullable
    private Section section;
    private boolean reported;

    SectionBlockCursor(@Nonnull Sections sections) {
        this.sections = sections;
    }

    /**
     * A cursor over the loaded chunk sections of {@code world}. World-thread only. Never throws: a world
     * whose chunk store cannot be read answers a cursor that reads empty everywhere.
     */
    @Nonnull
    public static SectionBlockCursor of(@Nonnull World world) {
        try {
            ChunkStore chunks = world.getChunkStore();
            Store<ChunkStore> store = chunks.getStore();
            return new SectionBlockCursor(sectionsOf(chunks, store));
        } catch (Throwable t) {
            report("chunk store unreadable for a section read", t);
            return new SectionBlockCursor(NO_SECTIONS);
        }
    }

    /**
     * The block id at this world cell: an index into {@code BlockType.getAssetMap()}, or
     * {@link BlockType#EMPTY_ID} for air, for a cell whose section is not in memory, and for a failed
     * read. Looks a section up only when the cell is in a different section from the last read.
     */
    public int blockId(int x, int y, int z) {
        try {
            int sx = ChunkUtil.chunkCoordinate(x);
            int sy = ChunkUtil.chunkCoordinate(y);
            int sz = ChunkUtil.chunkCoordinate(z);
            if (!cached || sx != sectionX || sy != sectionY || sz != sectionZ) {
                cached = false;
                section = sections.at(x, y, z);
                sectionX = sx;
                sectionY = sy;
                sectionZ = sz;
                cached = true;
            }
            return section == null ? BlockType.EMPTY_ID : section.blockId(x, y, z);
        } catch (Throwable t) {
            cached = false;
            section = null;
            if (!reported || t instanceof LinkageError) {
                reported = true;
                report("section read at (" + x + ", " + y + ", " + z + ") failed", t);
            }
            return BlockType.EMPTY_ID;
        }
    }

    /**
     * The engine's section lookup, each call made directly (never through a method reference) so it
     * links the same on both server lines.
     */
    @Nonnull
    private static Sections sectionsOf(@Nonnull ChunkStore chunks, @Nonnull Store<ChunkStore> store) {
        return (x, y, z) -> {
            Ref<ChunkStore> sectionRef = chunks.getChunkSectionReferenceAtBlock(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) {
                return null;
            }
            BlockSection blocks = store.getComponent(sectionRef, BlockSection.getComponentType());
            if (blocks == null) {
                return null;
            }
            return (bx, by, bz) -> blocks.get(bx, by, bz);
        };
    }

    private static void report(@Nonnull String what, @Nonnull Throwable t) {
        if (t instanceof LinkageError) {
            if (LINKAGE_REPORTED.compareAndSet(false, true)) {
                SafeLog.warn("[block] the chunk-section block read does not link on this server build, so every"
                        + " block reads as empty (look rays read clear, surface probes fall back, block sounds stay"
                        + " silent): " + what, t);
            }
            return;
        }
        SafeLog.fine("[block] " + what, t);
    }
}
