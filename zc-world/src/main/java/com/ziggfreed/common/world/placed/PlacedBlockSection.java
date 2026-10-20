package com.ziggfreed.common.world.placed;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;

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
 * <p><b>The mark is the guard's whole record.</b> The guard refuses credit for any placement whoever
 * breaks it, so it reads only the bit. Placements that should NOT be guarded (an admin standing up an
 * ore vein for players to mine) are settled when the block goes down, by not marking it at all - see
 * {@link PlacedBlockRecorder}. Who placed a marked block is kept beside it for the rules that take only
 * a player's own blocks (a shape that tears down what you built), in a {@link PlacerPalette}: one id for
 * a section one player built in, a packed index a block only once a second placer arrives.
 *
 * <p><b>Spared blocks.</b> A sparse map, allocated only once one lands, says which placements did not
 * use up their item (a thrift step spared it), so breaking the block must not hand the item back a
 * second time. Each entry keeps what the spared block was (its own item id), so a block that later
 * stands in its cell without the mark being cleared (physics or another mod took the spared one) is
 * told apart. It is kept whether or not the placement was guarded, and cleared when the block goes.
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

    /** The saved form's leading flags: which parts follow it, in this order. */
    private static final int HAS_MARKS = 1;
    /** Bare spared bits, written only by builds before the spared block was kept; read past and dropped. */
    private static final int HAS_SPARED_BITS = 2;
    /** The spared blocks and what each was; it sits where the bare bits did, and the two are never both written. */
    private static final int HAS_SPARED = 8;
    private static final int HAS_PLACERS = 4;

    /** A saved palette entry: a presence byte, then the id's two halves. */
    private static final int ENTRY_BYTES = 1 + 2 * Long.BYTES;

    public static final BuilderCodec<PlacedBlockSection> CODEC =
            BuilderCodec.builder(PlacedBlockSection.class, PlacedBlockSection::new)
                    .versioned()
                    .codecVersion(VERSION)
                    // The engine's own note on the constant reads only "Replace with a fully decodeJson
                    // compatible binary codec", which no build ships yet, and every engine section codec
                    // (BlockPhysics, BlockSection, FluidSection, BlockHealthChunk) still keys "Data" on it.
                    .append(
                            // DEPRECATION-KEPT: no successor exists yet, and a saved chunk holds "Data" as BSON binary that a swap must read back
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

    /** Who placed each marked block; emptied with the last mark. */
    @Nonnull
    private PlacerPalette placers = new PlacerPalette();

    /**
     * What each spared block here was when it was spared (its own item id), by section-local index; null until one
     * lands here, back to null when none is left.
     */
    @Nullable
    private Short2ObjectOpenHashMap<String> spared;

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
     * Set or clear the mark for one section-local index, its placer unknown.
     *
     * @return true when this actually changed the mark
     */
    public boolean setMarked(int index, boolean marked) {
        return setMarked(index, marked, null);
    }

    /**
     * Set or clear the mark for one section-local index; a set records {@code placer} as who placed it (null for
     * unknown), replacing whoever a mark already there named.
     *
     * @return true when this actually changed the mark
     */
    public boolean setMarked(int index, boolean marked, @Nullable UUID placer) {
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
        if (marked) {
            placers.set(index, placer, markedCount > (was ? 1 : 0));
        }
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
                placers.clear();
            }
        }
        return true;
    }

    /** How many blocks in this section are marked. */
    public int markedCount() {
        return markedCount;
    }

    /** Who placed the marked block at this section-local index: null when unmarked or unknown. */
    @Nullable
    public UUID placerAt(int index) {
        return isMarked(index) ? placers.get(index) : null;
    }

    /** Is the block at this section-local index one whose placement did not use up its item? */
    public boolean isSpared(int index) {
        Short2ObjectOpenHashMap<String> kept = spared;
        return kept != null && kept.containsKey((short) index);
    }

    /** What the spared block at this section-local index was when it was spared (its own item id); null when none is. */
    @Nullable
    public String sparedAs(int index) {
        Short2ObjectOpenHashMap<String> kept = spared;
        return kept == null ? null : kept.get((short) index);
    }

    /**
     * Mark the block at this section-local index spared, as {@code identity} (its own item id), replacing a mark
     * already there.
     *
     * @return true when this changed the mark
     */
    public boolean spare(int index, @Nonnull String identity) {
        Short2ObjectOpenHashMap<String> kept = spared;
        if (kept == null) {
            kept = new Short2ObjectOpenHashMap<>();
            spared = kept;
        }
        return !identity.equals(kept.put((short) index, identity));
    }

    /**
     * Forget the spared mark at this section-local index.
     *
     * @return true when there was one
     */
    public boolean unspare(int index) {
        Short2ObjectOpenHashMap<String> kept = spared;
        if (kept == null || kept.remove((short) index) == null) {
            return false;
        }
        if (kept.isEmpty()) {
            spared = null;
        }
        return true;
    }

    /** How many blocks in this section are spared. */
    public int sparedCount() {
        Short2ObjectOpenHashMap<String> kept = spared;
        return kept == null ? 0 : kept.size();
    }

    /** How many placers this section's palette names (the unknown placer included), for the memory report. */
    public int placerCount() {
        return placers.size();
    }

    /** Bits a block this section spends on its placers: 0 while one placer stands for every marked block. */
    public int placerBits() {
        return placers.bits();
    }

    // ==================== the position-facing helpers every caller uses ====================

    /**
     * Remember that a player put the block at this position down. Does nothing when the component
     * could not be registered.
     */
    public static void mark(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
        mark(accessor, sectionRef, x, y, z, null);
    }

    /** {@link #mark(ComponentAccessor, Ref, int, int, int)}, naming who placed it (null for unknown). */
    public static void mark(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z, @Nullable UUID placer) {
        PlacedBlockSection section = ensure(accessor, sectionRef);
        if (section != null) {
            section.setMarked(ChunkUtil.indexBlock(x, y, z), true, placer);
        }
    }

    /** Who placed the marked block at this position: null when unmarked, unknown or unregistered. */
    @Nullable
    public static UUID placerOf(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
        ComponentType<ChunkStore, PlacedBlockSection> componentType = type;
        if (componentType == null) {
            return null;
        }
        PlacedBlockSection section = accessor.getComponent(sectionRef, componentType);
        return section == null ? null : section.placerAt(ChunkUtil.indexBlock(x, y, z));
    }

    /**
     * What the spared block at this position was when it was spared (its own item id): null when none is spared there,
     * or the component is unregistered.
     */
    @Nullable
    public static String sparedAs(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
        ComponentType<ChunkStore, PlacedBlockSection> componentType = type;
        if (componentType == null) {
            return null;
        }
        PlacedBlockSection section = accessor.getComponent(sectionRef, componentType);
        return section == null ? null : section.sparedAs(ChunkUtil.indexBlock(x, y, z));
    }

    /**
     * Mark the block at this position spared, as {@code identity} (its own item id).
     *
     * @return true when this changed the mark
     */
    public static boolean markSpared(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z, @Nonnull String identity) {
        PlacedBlockSection section = ensure(accessor, sectionRef);
        return section != null && section.spare(ChunkUtil.indexBlock(x, y, z), identity);
    }

    /**
     * Forget the spared mark at this position. A section holding none gains nothing.
     *
     * @return true when there was one
     */
    public static boolean clearSpared(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef, int x, int y, int z) {
        ComponentType<ChunkStore, PlacedBlockSection> componentType = type;
        if (componentType == null) {
            return false;
        }
        PlacedBlockSection section = accessor.getComponent(sectionRef, componentType);
        return section != null && section.unspare(ChunkUtil.indexBlock(x, y, z));
    }

    @Nullable
    private static PlacedBlockSection ensure(@Nonnull ComponentAccessor<ChunkStore> accessor,
            @Nonnull Ref<ChunkStore> sectionRef) {
        ComponentType<ChunkStore, PlacedBlockSection> componentType = type;
        if (componentType == null) {
            return null;
        }
        PlacedBlockSection section = accessor.getComponent(sectionRef, componentType);
        return section != null ? section : accessor.ensureAndGetComponent(sectionRef, componentType);
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
            copy.placers = placers.copy();
        }
        Short2ObjectOpenHashMap<String> kept = spared;
        if (kept != null) {
            copy.spared = new Short2ObjectOpenHashMap<>(kept);
        }
        return copy;
    }

    /**
     * A leading flag byte says which parts follow (the marks, the spared blocks, the placers), so a
     * section that holds none costs one byte on disk rather than 4KB of zeroes. Mirrors how the
     * engine's own per-block section data serializes. A section saved before the spared blocks and the
     * placers were kept wrote the flag as a boolean, which reads as "marks only", every placer unknown.
     */
    private byte[] serialize(ExtraInfo extraInfo) {
        byte[] data = marks;
        byte[] kept = sparedBytes();
        UUID[] entries = placers.entries();
        boolean named = data != null && entries.length > 0 && !(entries.length == 1 && entries[0] == null);
        long[] packed = named ? placers.packed() : null;
        int bits = named ? placers.bits() : 0;
        int placerBytes = named ? 2 + entries.length * ENTRY_BYTES + PlacerPalette.longsFor(bits) * Long.BYTES : 0;
        int size = 1 + (data != null ? MARKS_SIZE : 0) + (kept != null ? Integer.BYTES + kept.length : 0) + placerBytes;
        byte[] result = new byte[size];
        MemorySegment out = MemorySegment.ofArray(result);
        int flags = (data != null ? HAS_MARKS : 0) | (kept != null ? HAS_SPARED : 0) | (named ? HAS_PLACERS : 0);
        out.set(ValueLayout.JAVA_BYTE, 0, (byte) flags);
        long at = 1;
        if (data != null) {
            MemorySegment.copy(data, 0, out, ValueLayout.JAVA_BYTE, at, MARKS_SIZE);
            at += MARKS_SIZE;
        }
        if (kept != null) {
            out.set(ValueLayout.JAVA_INT_UNALIGNED, at, kept.length);
            MemorySegment.copy(kept, 0, out, ValueLayout.JAVA_BYTE, at + Integer.BYTES, kept.length);
            at += Integer.BYTES + kept.length;
        }
        if (named) {
            out.set(ValueLayout.JAVA_BYTE, at++, (byte) (entries.length - 1));
            out.set(ValueLayout.JAVA_BYTE, at++, (byte) bits);
            for (UUID entry : entries) {
                out.set(ValueLayout.JAVA_BYTE, at, (byte) (entry == null ? 0 : 1));
                if (entry != null) {
                    out.set(ValueLayout.JAVA_LONG_UNALIGNED, at + 1, entry.getMostSignificantBits());
                    out.set(ValueLayout.JAVA_LONG_UNALIGNED, at + 1 + Long.BYTES, entry.getLeastSignificantBits());
                }
                at += ENTRY_BYTES;
            }
            if (packed != null) {
                for (long word : packed) {
                    out.set(ValueLayout.JAVA_LONG_UNALIGNED, at, word);
                    at += Long.BYTES;
                }
            }
        }
        return result;
    }

    private void deserialize(@Nonnull byte[] bytes, ExtraInfo extraInfo) {
        marks = null;
        markedCount = 0;
        spared = null;
        placers = new PlacerPalette();
        if (bytes.length < 1) {
            return;
        }
        MemorySegment in = MemorySegment.ofArray(bytes);
        int flags = in.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
        long at = 1;
        if ((flags & HAS_MARKS) != 0) {
            if (bytes.length < at + MARKS_SIZE) {
                return;
            }
            byte[] data = readBits(in, at);
            at += MARKS_SIZE;
            int count = countBits(data);
            if (count > 0) {
                marks = data;
                markedCount = count;
            }
        }
        if ((flags & HAS_SPARED_BITS) != 0) {
            // A build that kept bare spared bits wrote them here; it never shipped, and a mark that cannot say which
            // block it spared is dropped rather than guessed at.
            if (bytes.length < at + MARKS_SIZE) {
                return;
            }
            at += MARKS_SIZE;
        }
        if ((flags & HAS_SPARED) != 0) {
            if (bytes.length < at + Integer.BYTES) {
                return;
            }
            int length = in.get(ValueLayout.JAVA_INT_UNALIGNED, at);
            at += Integer.BYTES;
            if (length < 0 || bytes.length < at + length) {
                return;
            }
            readSpared(in, at, at + length);
            at += length;
        }
        if ((flags & HAS_PLACERS) != 0 && marks != null) {
            readPlacers(in, bytes.length, at);
        }
    }

    /**
     * The saved spared blocks: each identity once (a count, then each as a length and its UTF-8 bytes), then each spared
     * block as its section-local index and which identity it holds. Null when none is spared.
     */
    @Nullable
    private byte[] sparedBytes() {
        Short2ObjectOpenHashMap<String> kept = spared;
        if (kept == null || kept.isEmpty()) {
            return null;
        }
        Map<String, Integer> slots = new HashMap<>();
        List<byte[]> names = new ArrayList<>();
        short[] indexes = new short[kept.size()];
        short[] which = new short[kept.size()];
        int entries = 0;
        int nameBytes = 0;
        for (Short2ObjectMap.Entry<String> entry : kept.short2ObjectEntrySet()) {
            String identity = entry.getValue();
            Integer slot = slots.get(identity);
            if (slot == null) {
                byte[] utf8 = identity.getBytes(StandardCharsets.UTF_8);
                if (utf8.length > 0xFFFF) {
                    // No block id runs this long; one that did could not be written back, so its mark is not kept.
                    continue;
                }
                slot = names.size();
                slots.put(identity, slot);
                names.add(utf8);
                nameBytes += Short.BYTES + utf8.length;
            }
            indexes[entries] = entry.getShortKey();
            which[entries] = (short) slot.intValue();
            entries++;
        }
        if (entries == 0) {
            return null;
        }
        byte[] result = new byte[Short.BYTES + nameBytes + Integer.BYTES + entries * 2 * Short.BYTES];
        MemorySegment out = MemorySegment.ofArray(result);
        long at = 0;
        out.set(ValueLayout.JAVA_SHORT_UNALIGNED, at, (short) names.size());
        at += Short.BYTES;
        for (byte[] name : names) {
            out.set(ValueLayout.JAVA_SHORT_UNALIGNED, at, (short) name.length);
            at += Short.BYTES;
            MemorySegment.copy(name, 0, out, ValueLayout.JAVA_BYTE, at, name.length);
            at += name.length;
        }
        out.set(ValueLayout.JAVA_INT_UNALIGNED, at, entries);
        at += Integer.BYTES;
        for (int i = 0; i < entries; i++) {
            out.set(ValueLayout.JAVA_SHORT_UNALIGNED, at, indexes[i]);
            out.set(ValueLayout.JAVA_SHORT_UNALIGNED, at + Short.BYTES, which[i]);
            at += 2 * Short.BYTES;
        }
        return result;
    }

    /** Read the saved spared blocks between {@code at} and {@code end}; a part that does not fit drops them all. */
    private void readSpared(@Nonnull MemorySegment in, long at, long end) {
        if (end < at + Short.BYTES) {
            return;
        }
        int count = in.get(ValueLayout.JAVA_SHORT_UNALIGNED, at) & 0xFFFF;
        at += Short.BYTES;
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            if (end < at + Short.BYTES) {
                return;
            }
            int size = in.get(ValueLayout.JAVA_SHORT_UNALIGNED, at) & 0xFFFF;
            at += Short.BYTES;
            if (end < at + size) {
                return;
            }
            byte[] utf8 = new byte[size];
            MemorySegment.copy(in, ValueLayout.JAVA_BYTE, at, utf8, 0, size);
            at += size;
            names[i] = new String(utf8, StandardCharsets.UTF_8).intern();
        }
        if (end < at + Integer.BYTES) {
            return;
        }
        int entries = in.get(ValueLayout.JAVA_INT_UNALIGNED, at);
        at += Integer.BYTES;
        if (entries <= 0 || entries > ChunkUtil.SIZE_BLOCKS || end < at + (long) entries * 2 * Short.BYTES) {
            return;
        }
        Short2ObjectOpenHashMap<String> kept = new Short2ObjectOpenHashMap<>(entries);
        for (int i = 0; i < entries; i++) {
            int index = in.get(ValueLayout.JAVA_SHORT_UNALIGNED, at) & 0xFFFF;
            int slot = in.get(ValueLayout.JAVA_SHORT_UNALIGNED, at + Short.BYTES) & 0xFFFF;
            at += 2 * Short.BYTES;
            if (index >= ChunkUtil.SIZE_BLOCKS || slot >= count) {
                return;
            }
            kept.put((short) index, names[slot]);
        }
        spared = kept;
    }

    /** Read a saved palette; one that does not fit together is dropped, every placer then unknown. */
    private void readPlacers(@Nonnull MemorySegment in, int length, long at) {
        if (length < at + 2) {
            return;
        }
        int count = (in.get(ValueLayout.JAVA_BYTE, at) & 0xFF) + 1;
        int bits = in.get(ValueLayout.JAVA_BYTE, at + 1) & 0xFF;
        at += 2;
        if (!PlacerPalette.validWidth(bits)
                || length < at + (long) count * ENTRY_BYTES + (long) PlacerPalette.longsFor(bits) * Long.BYTES) {
            return;
        }
        UUID[] entries = new UUID[count];
        for (int i = 0; i < count; i++) {
            if (in.get(ValueLayout.JAVA_BYTE, at) != 0) {
                entries[i] = new UUID(in.get(ValueLayout.JAVA_LONG_UNALIGNED, at + 1),
                        in.get(ValueLayout.JAVA_LONG_UNALIGNED, at + 1 + Long.BYTES));
            }
            at += ENTRY_BYTES;
        }
        long[] packed = null;
        if (bits > 0) {
            packed = new long[PlacerPalette.longsFor(bits)];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = in.get(ValueLayout.JAVA_LONG_UNALIGNED, at);
                at += Long.BYTES;
            }
        }
        placers.restore(entries, bits, packed);
    }

    @Nonnull
    private static byte[] readBits(@Nonnull MemorySegment in, long at) {
        byte[] data = new byte[MARKS_SIZE];
        MemorySegment.copy(in, ValueLayout.JAVA_BYTE, at, data, 0, MARKS_SIZE);
        return data;
    }

    private static int countBits(@Nonnull byte[] data) {
        int count = 0;
        for (byte b : data) {
            count += Integer.bitCount(b & 0xFF);
        }
        return count;
    }
}
