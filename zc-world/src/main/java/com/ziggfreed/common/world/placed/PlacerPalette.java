package com.ziggfreed.common.world.placed;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.math.util.ChunkUtil;

/**
 * Who placed each marked block of one chunk section, kept as small as the section allows: a palette of the placers
 * seen in it and, only once a second one arrives, a packed index per block into that palette, the way the engine
 * keeps a section's block ids. A section one player built in holds one id and nothing per block; two placers cost a
 * bit a block (4KB), up to four two bits (8KB), up to sixteen four (16KB), and up to {@value #MAX_ENTRIES} a byte
 * (32KB). A null entry stands for a block marked before placers were kept, which nobody owns.
 *
 * <p>The value at an unmarked block means nothing: {@link PlacedBlockSection} reads it only where its mark is set,
 * and empties the palette when its last mark is spent. World-thread only, like the section that holds it.
 */
final class PlacerPalette {

    /** The most entries one section's palette holds; past it a new placer is recorded as unknown. */
    static final int MAX_ENTRIES = 256;

    private static final UUID[] NONE = new UUID[0];

    /** The placers seen here in arrival order; a null entry is the unknown placer. */
    @Nonnull
    private UUID[] entries = NONE;

    /** Bits a block in {@link #packed}: 0 while the palette holds at most one entry, else 1, 2, 4 or 8. */
    private int bits;

    /** One value a block, {@link #bits} wide, packed into longs; null while {@link #bits} is 0. */
    @Nullable
    private long[] packed;

    PlacerPalette() {
    }

    /** Whether the palette names nobody yet. */
    boolean isEmpty() {
        return entries.length == 0;
    }

    /** How many entries the palette holds (the unknown placer included). */
    int size() {
        return entries.length;
    }

    /** Bits a block the palette spends, 0 while one entry stands for every block. */
    int bits() {
        return bits;
    }

    /** Who placed the block at section-local {@code index}: null when unknown or when the palette is empty. */
    @Nullable
    UUID get(int index) {
        if (entries.length == 0) {
            return null;
        }
        return bits == 0 ? entries[0] : entries[read(index)];
    }

    /**
     * Record {@code placer} at section-local {@code index}. {@code othersMarked} says the section already holds marks
     * this palette never saw (a section saved before placers were kept), so they stay unknown rather than reading as
     * the first placer to arrive.
     */
    void set(int index, @Nullable UUID placer, boolean othersMarked) {
        if (entries.length == 0 && othersMarked) {
            append(null);
        }
        int value = indexOf(placer);
        if (value < 0) {
            if (placer != null && entries.length >= MAX_ENTRIES - 1 && indexOf(null) < 0) {
                // One slot is always left for the unknown placer, so a crowded section still records something.
                value = append(null);
            } else if (entries.length >= MAX_ENTRIES) {
                value = Math.max(0, indexOf(null));
            } else {
                value = append(placer);
            }
        }
        if (bits > 0) {
            write(index, value);
        }
    }

    /** Forget every placer: the section's last mark is gone. */
    void clear() {
        entries = NONE;
        bits = 0;
        packed = null;
    }

    @Nonnull
    PlacerPalette copy() {
        PlacerPalette copy = new PlacerPalette();
        copy.entries = entries.length == 0 ? NONE : Arrays.copyOf(entries, entries.length);
        copy.bits = bits;
        copy.packed = packed == null ? null : Arrays.copyOf(packed, packed.length);
        return copy;
    }

    // ==================== the saved form ====================

    /** The entries, for the section's codec. */
    @Nonnull
    UUID[] entries() {
        return entries;
    }

    /** The packed values, for the section's codec; null while {@link #bits} is 0. */
    @Nullable
    long[] packed() {
        return packed;
    }

    /** How many longs the packed values take at {@code width} bits a block. */
    static int longsFor(int width) {
        return width == 0 ? 0 : ChunkUtil.SIZE_BLOCKS * width / Long.SIZE;
    }

    /** Whether {@code width} is one this palette writes (0, 1, 2, 4 or 8). */
    static boolean validWidth(int width) {
        return width == 0 || width == 1 || width == 2 || width == 4 || width == 8;
    }

    /**
     * Restore a saved palette; false (the palette left empty) when the pieces do not fit together, which a caller
     * reads as every marked block's placer unknown.
     */
    boolean restore(@Nonnull UUID[] savedEntries, int savedBits, @Nullable long[] savedPacked) {
        clear();
        if (savedEntries.length == 0 || savedEntries.length > MAX_ENTRIES || !validWidth(savedBits)
                || widthFor(savedEntries.length) > savedBits
                || (savedBits == 0) != (savedPacked == null)
                || (savedPacked != null && savedPacked.length != longsFor(savedBits))) {
            return false;
        }
        entries = savedEntries;
        bits = savedBits;
        packed = savedPacked;
        return true;
    }

    // ==================== the packing ====================

    private int indexOf(@Nullable UUID placer) {
        for (int i = 0; i < entries.length; i++) {
            if (Objects.equals(entries[i], placer)) {
                return i;
            }
        }
        return -1;
    }

    private int append(@Nullable UUID placer) {
        int value = entries.length;
        entries = Arrays.copyOf(entries, value + 1);
        entries[value] = placer;
        int needed = widthFor(entries.length);
        if (needed > bits) {
            widen(needed);
        }
        return value;
    }

    /** The bits a block a palette of {@code size} entries needs: 0, 1, 2, 4 or 8. */
    static int widthFor(int size) {
        if (size <= 1) {
            return 0;
        }
        if (size <= 2) {
            return 1;
        }
        if (size <= 4) {
            return 2;
        }
        return size <= 16 ? 4 : 8;
    }

    private void widen(int width) {
        long[] wider = new long[longsFor(width)];
        if (bits > 0 && packed != null) {
            int perWord = Long.SIZE / width;
            long mask = (1L << width) - 1L;
            for (int index = 0; index < ChunkUtil.SIZE_BLOCKS; index++) {
                long value = read(index) & mask;
                if (value != 0L) {
                    wider[index / perWord] |= value << ((index % perWord) * width);
                }
            }
        }
        // From 0 bits every block read entry 0, which an all-zero array still says.
        packed = wider;
        bits = width;
    }

    private int read(int index) {
        long[] data = packed;
        if (data == null) {
            return 0;
        }
        int perWord = Long.SIZE / bits;
        long word = data[index / perWord];
        return (int) ((word >>> ((index % perWord) * bits)) & ((1L << bits) - 1L));
    }

    private void write(int index, int value) {
        long[] data = packed;
        if (data == null) {
            return;
        }
        int perWord = Long.SIZE / bits;
        int shift = (index % perWord) * bits;
        long mask = ((1L << bits) - 1L) << shift;
        int slot = index / perWord;
        data[slot] = (data[slot] & ~mask) | (((long) value << shift) & mask);
    }
}
