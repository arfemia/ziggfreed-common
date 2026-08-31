package com.ziggfreed.common.world.placed;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Which blocks in one chunk section a player put there: one bit per block, stored on the section
 * itself and saved with the chunk.
 *
 * <p><b>Why it lives on the chunk rather than in a file.</b> "Somebody placed this block" is a fact
 * about a position, and a position belongs to a chunk. Keeping it here means the engine's own chunk
 * save carries it, only loaded chunks cost memory, a lookup is an array index rather than a map
 * probe against every placement the server has ever seen, and nothing has to be scanned, copied or
 * rewritten on a timer. A section that holds no placement carries no array at all, and drops back
 * to none when its last mark is cleared.
 *
 * <p>The layout follows the engine's own per-block section data (see {@code BlockPhysics}, which
 * packs a nibble per block the same way): a lazily allocated byte array indexed by the block's
 * section-local {@link ChunkUtil#indexBlock} index, behind a versioned codec with a single byte-
 * array key.
 *
 * <p><b>One bit is the whole record.</b> A mark says a player placed this, not which player: the
 * guard refuses credit for any placement whoever breaks it, so the placer's identity would never be
 * read back. Placements that should NOT be guarded (an admin standing up an ore vein for players to
 * mine) are settled when the block goes down, by not marking it at all - see
 * {@link PlacedBlockRecorder}.
 *
 * <p>Must only be touched on the owning world's thread, like every other chunk-store component.
 */
public final class PlacedBlockSection implements Component<ChunkStore> {

    /** The codec version, so a later change to the payload can tell an older section apart. */
    public static final int VERSION = 0;

    /** The id the component registers under; also its key in a saved chunk. */
    public static final String REGISTRY_ID = "ZigPlacedBlocks";

    /** One bit per block in a section. 32^3 blocks is 4KB, allocated only once a mark lands. */
    private static final int MARKS_SIZE = ChunkUtil.SIZE_BLOCKS >> 3;

    public static final BuilderCodec<PlacedBlockSection> CODEC =
            BuilderCodec.builder(PlacedBlockSection.class, PlacedBlockSection::new)
                    .versioned()
                    .codecVersion(VERSION)
                    .append(
                            new KeyedCodec<>("Data", Codec.BYTE_ARRAY),
                            PlacedBlockSection::deserialize,
                            PlacedBlockSection::serialize
                    )
                    .add()
                    .build();

    /**
     * The registered type, or null when registration failed. Every reader goes through the static
     * helpers below, which treat a null type as "nothing is marked" - a boot that could not
     * register the component pays out for placements instead of refusing every break outright.
     */
    @Nullable
    private static volatile ComponentType<ChunkStore, PlacedBlockSection> type;

    /** Null until this section holds its first mark; back to null when it holds none again. */
    @Nullable
    private byte[] marks;

    /** How many bits are set, so an emptied section can drop its array instead of carrying zeroes. */
    private int markedCount;

    public PlacedBlockSection() {
    }

    /**
     * Register the component on the chunk-store registry. Call once at plugin setup, BEFORE any
     * world loads. Never throws: a failure logs and leaves the type unset, which reads as "nothing
     * was ever placed" rather than taking the server down.
     */
    public static void register(@Nonnull ComponentRegistryProxy<ChunkStore> registry) {
        try {
            type = registry.registerComponent(PlacedBlockSection.class, REGISTRY_ID, CODEC);
        } catch (Throwable t) {
            SafeLog.warn("[placed] the placed-block chunk component could not be registered:"
                    + " nothing will be remembered this boot, so place-then-break pays out", t);
        }
    }

    /** The registered type, or null when registration failed. */
    @Nullable
    public static ComponentType<ChunkStore, PlacedBlockSection> type() {
        return type;
    }

    /** Test seam: forget the registration, so a fixture can register against its own registry. */
    static void resetTypeForTests() {
        type = null;
    }

    // ==================== per-section reads and writes ====================

    /** Is the block at this section-local index marked as placed? */
    public boolean isMarked(int index) {
        byte[] data = marks;
        if (data == null) {
            return false;
        }
        return (data[index >> 3] & (1 << (index & 7))) != 0;
    }

    /**
     * Set or clear the mark for one section-local index.
     *
     * @return true when this actually changed the mark
     */
    public boolean setMarked(int index, boolean marked) {
        byte[] data = marks;
        if (data == null) {
            if (!marked) {
                return false;
            }
            data = new byte[MARKS_SIZE];
            marks = data;
        }
        int slot = index >> 3;
        int bit = 1 << (index & 7);
        boolean was = (data[slot] & bit) != 0;
        if (was == marked) {
            return false;
        }
        if (marked) {
            data[slot] |= (byte) bit;
            markedCount++;
        } else {
            data[slot] &= (byte) ~bit;
            markedCount--;
            if (markedCount <= 0) {
                // The last mark in this section is gone; carrying 4KB of zeroes helps nobody.
                marks = null;
                markedCount = 0;
            }
        }
        return true;
    }

    /** How many blocks in this section are marked. */
    public int markedCount() {
        return markedCount;
    }

    // ==================== the position-facing helpers every caller uses ====================

    /**
     * Remember that a player put the block at this position down. Does nothing when the component
     * could not be registered.
     */
    public static void mark(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
        ComponentType<ChunkStore, PlacedBlockSection> componentType = type;
        if (componentType == null) {
            return;
        }
        PlacedBlockSection section = accessor.getComponent(sectionRef, componentType);
        if (section == null) {
            section = accessor.ensureAndGetComponent(sectionRef, componentType);
        }
        section.setMarked(ChunkUtil.indexBlock(x, y, z), true);
    }

    /**
     * Was the block at this position placed? Reads without changing anything - the LOOKING read,
     * for a caller that must not spend the mark.
     */
    public static boolean isPlaced(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
        ComponentType<ChunkStore, PlacedBlockSection> componentType = type;
        if (componentType == null) {
            return false;
        }
        PlacedBlockSection section = accessor.getComponent(sectionRef, componentType);
        return section != null && section.isMarked(ChunkUtil.indexBlock(x, y, z));
    }

    /**
     * Was the block at this position placed, clearing the mark if so? The break-time read: the
     * block is going away, so the mark goes with it whatever the answer is used for.
     */
    public static boolean consume(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
        ComponentType<ChunkStore, PlacedBlockSection> componentType = type;
        if (componentType == null) {
            return false;
        }
        PlacedBlockSection section = accessor.getComponent(sectionRef, componentType);
        if (section == null) {
            return false;
        }
        int index = ChunkUtil.indexBlock(x, y, z);
        if (!section.isMarked(index)) {
            return false;
        }
        section.setMarked(index, false);
        return true;
    }

    // ==================== component plumbing ====================

    @Nonnull
    @Override
    public Component<ChunkStore> clone() {
        PlacedBlockSection copy = new PlacedBlockSection();
        byte[] data = marks;
        if (data != null) {
            copy.marks = Arrays.copyOf(data, data.length);
            copy.markedCount = markedCount;
        }
        return copy;
    }

    /**
     * A leading flag byte says whether any marks follow, so a section that holds none costs one
     * byte on disk rather than 4KB of zeroes. Mirrors how the engine's own per-block section data
     * serializes.
     */
    private byte[] serialize(ExtraInfo extraInfo) {
        byte[] data = marks;
        byte[] result = new byte[1 + (data != null ? MARKS_SIZE : 0)];
        MemorySegment out = MemorySegment.ofArray(result);
        out.set(ValueLayout.JAVA_BOOLEAN, 0, data != null);
        if (data != null) {
            MemorySegment.copy(data, 0, out, ValueLayout.JAVA_BYTE, 1, data.length);
        }
        return result;
    }

    private void deserialize(@Nonnull byte[] bytes, ExtraInfo extraInfo) {
        MemorySegment in = MemorySegment.ofArray(bytes);
        if (bytes.length < 1 || !in.get(ValueLayout.JAVA_BOOLEAN, 0) || bytes.length < 1 + MARKS_SIZE) {
            marks = null;
            markedCount = 0;
            return;
        }
        byte[] data = new byte[MARKS_SIZE];
        MemorySegment.copy(in, ValueLayout.JAVA_BYTE, 1, data, 0, MARKS_SIZE);
        int count = 0;
        for (byte b : data) {
            count += Integer.bitCount(b & 0xFF);
        }
        if (count == 0) {
            marks = null;
            markedCount = 0;
            return;
        }
        marks = data;
        markedCount = count;
    }
}
