package com.ziggfreed.common.world;

import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.Opacity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * Finds the top SOLID (opaque) surface block at a world (x, z) by scanning a
 * column downward, so generated/runtime content can be placed on procedural,
 * uneven terrain instead of a hardcoded Y. Mirrors the engine's own
 * {@code GeneratedBlockChunk.getHeight} logic (skip air {@code blockId == 0} and
 * any {@link Opacity#Transparent} block - leaves, glass, foliage - and stop at the
 * first opaque block), but reads a live world's loaded chunk sections through
 * zc-core's {@link SectionBlockCursor} (the read both the live server and Update 7
 * keep, where Update 7 deletes the {@code World} block reads), so it works on
 * already-generated chunks at runtime.
 *
 * <p><b>World-thread only</b> (it reads loaded blocks): call it inside a
 * {@code world.execute(...)} hop. It never loads a chunk: a column whose sections
 * are not in memory reads as empty, so the probe answers the caller's
 * {@code fallbackY}, as it does for an out-of-range coordinate or any failed read,
 * and nothing throws into the caller. A caller probing ground no player is near
 * loads those chunks first.
 */
public final class SurfaceProbe {

    /** Default Y the column scan starts from (well above any expected terrain or foliage). */
    public static final int DEFAULT_SCAN_TOP = 200;

    private SurfaceProbe() {
    }

    /**
     * The Y of the topmost opaque-solid block at {@code (x, z)}, scanning down from
     * {@code scanTop}. A standable surface is one block above the return value.
     *
     * @param world    the world (read on its own thread)
     * @param x        world block X
     * @param z        world block Z
     * @param scanTop  the Y to start scanning down from
     * @param fallbackY returned if no solid block is found or a read fails
     * @return the top opaque-solid block Y, or {@code fallbackY}
     */
    public static int topSolidY(@Nonnull World world, int x, int z, int scanTop, int fallbackY) {
        return topSolidY(world, x, z, scanTop, fallbackY, null);
    }

    /**
     * {@link #topSolidY(World, int, int, int, int)} that additionally SKIPS any block whose registered
     * key is in {@code skipBlockKeys}, on top of skipping air and {@link Opacity#Transparent} blocks.
     *
     * <p>The plain probe stops at the first opaque block, which at RUNTIME (after worldgen has decorated
     * trees onto the surface) is often a tree trunk/branch or a leaf block sitting ABOVE the real ground
     * - so a runtime-pasted structure floats on the canopy instead of the floor. Worldgen's own height
     * snap dodges this because it runs against the terrain buffer BEFORE the prop/tree phase; a runtime
     * caller does not, so it passes the foliage block keys here (resolve them from the engine's authored
     * {@code BlockTypeList} assets via {@link BlockTypeLists#keys(String...)}, e.g. {@code "TreeWoodAndLeaves"}
     * + {@code "AllScatter"}) to scan PAST the decoration to the genuine top-solid surface.
     *
     * @param skipBlockKeys block-type keys to treat as non-surface (skip), or {@code null} for none
     */
    public static int topSolidY(@Nonnull World world, int x, int z, int scanTop, int fallbackY,
                                @Nullable Set<String> skipBlockKeys) {
        try {
            IntUnaryOperator column = columnOf(world, x, z);
            return topSurfaceY(y -> isSurfaceBlock(column.applyAsInt(y), skipBlockKeys), scanTop, fallbackY);
        } catch (Throwable ignored) {
            // a failed engine read -> the caller's fallback
            return fallbackY;
        }
    }

    /** {@link #topSolidY(World, int, int, int, int)} starting from {@link #DEFAULT_SCAN_TOP}. */
    public static int topSolidY(@Nonnull World world, int x, int z, int fallbackY) {
        return topSolidY(world, x, z, DEFAULT_SCAN_TOP, fallbackY, null);
    }

    /**
     * {@link #topSolidY(World, int, int, int, int, Set)} from {@link #DEFAULT_SCAN_TOP}, skipping the
     * given block keys (e.g. foliage) so the probe finds the real ground under runtime tree decoration.
     */
    public static int topSolidY(@Nonnull World world, int x, int z, int fallbackY,
                                @Nullable Set<String> skipBlockKeys) {
        return topSolidY(world, x, z, DEFAULT_SCAN_TOP, fallbackY, skipBlockKeys);
    }

    /**
     * The standable surface Y (one above the top opaque-solid block) at {@code (x, z)}.
     *
     * @param fallbackStandY returned (verbatim) if no solid block is found or a read fails
     */
    public static int standableY(@Nonnull World world, int x, int z, int fallbackStandY) {
        return standableY(world, x, z, fallbackStandY, null);
    }

    /**
     * {@link #standableY(World, int, int, int)} skipping the given block keys (e.g. foliage), so the
     * standable Y lands on the genuine surface under runtime tree decoration rather than on a canopy.
     */
    public static int standableY(@Nonnull World world, int x, int z, int fallbackStandY,
                                 @Nullable Set<String> skipBlockKeys) {
        return standableOver(topSolidY(world, x, z, DEFAULT_SCAN_TOP, Integer.MIN_VALUE, skipBlockKeys),
                fallbackStandY);
    }

    /**
     * The scan itself, over one column's verdicts: the first Y from {@code scanTop} down to 1 whose cell
     * is a surface, else {@code fallbackY}. Row 0 is never read. Package-private for the test.
     */
    static int topSurfaceY(@Nonnull IntPredicate surfaceAt, int scanTop, int fallbackY) {
        for (int y = scanTop; y > 0; y--) {
            if (surfaceAt.test(y)) {
                return y;
            }
        }
        return fallbackY;
    }

    /**
     * One cell's verdict from its block id: an empty cell (air, or a section not in memory) is never the
     * surface and is answered before any block type is looked up; an id no block answers is passed too.
     * Package-private for the test.
     */
    static boolean isSurfaceBlock(int blockId, @Nullable Set<String> skipBlockKeys) {
        if (blockId == BlockType.EMPTY_ID) {
            return false;
        }
        BlockType type = BlockType.getAssetMap().getAsset(blockId);
        return type != null && isSurface(type.getOpacity(), type.getId(), skipBlockKeys);
    }

    /**
     * A loaded block's verdict: a {@link Opacity#Transparent} block, or one whose key is skipped, is
     * passed; any other block is the surface. Package-private for the test.
     */
    static boolean isSurface(@Nullable Opacity opacity, @Nullable String blockKey,
                             @Nullable Set<String> skipBlockKeys) {
        if (opacity == Opacity.Transparent) {
            return false;
        }
        return skipBlockKeys == null || skipBlockKeys.isEmpty() || !skipBlockKeys.contains(blockKey);
    }

    /**
     * One above the top surface, or the caller's fallback as given when the scan found none
     * ({@code Integer.MIN_VALUE}). Package-private for the test.
     */
    static int standableOver(int topY, int fallbackStandY) {
        return topY == Integer.MIN_VALUE ? fallbackStandY : topY + 1;
    }

    /** The block ids of one column, read off the world's loaded chunk sections. */
    @Nonnull
    private static IntUnaryOperator columnOf(@Nonnull World world, int x, int z) {
        SectionBlockCursor blocks = SectionBlockCursor.of(world);
        return y -> blocks.blockId(x, y, z);
    }
}
