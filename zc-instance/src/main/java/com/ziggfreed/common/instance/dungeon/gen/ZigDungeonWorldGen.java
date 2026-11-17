package com.ziggfreed.common.instance.dungeon.gen;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.core.universe.world.worldgen.WorldGenTimingsCollector;
import com.ziggfreed.common.instance.dungeon.AreaLayout;

import it.unimi.dsi.fastutil.longs.Long2FloatFunction;

/**
 * One generated dungeon copy's world generator: an empty world (vanilla's void, the {@code Default} environment) in
 * which every chunk a registered run's rooms reach is painted with them as it generates ({@link RoomPainter}). The
 * engine asks for a chunk on whatever thread wants it (the world thread on a load miss), so every chunk goes through
 * the library's one generator route ({@link PrefabPaint#generate}), which hops to the painter threads before anything
 * is read and comes back to the world through the engine's own chunk load. The runs it reads are its copy's
 * ({@link ZigDungeonWorldGenProvider}).
 *
 * <p>A clean generated chunk may unload and generate again: the same run, seed and room list paint it the same way
 * every time.
 */
final class ZigDungeonWorldGen implements IWorldGen {

    private static final String LABEL = "[dungeon] a generated dungeon copy";

    private final ZigDungeonWorldGenProvider copy;
    private final int environmentId;

    ZigDungeonWorldGen(@Nonnull ZigDungeonWorldGenProvider copy) {
        this.copy = copy;
        this.environmentId = PrefabPaint.defaultEnvironment();
    }

    @Nullable
    @Override
    public WorldGenTimingsCollector getTimings() {
        return null;
    }

    @Nonnull
    @Override
    // DEPRECATION-KEPT: IWorldGen declares this abstract, so every generator must implement it; no successor to implement instead
    public Transform[] getSpawnPoints(int seed) {
        return new Transform[]{new Transform(0.5, AreaLayout.AREA_Y, 0.5)};
    }

    @Nonnull
    @Override
    public CompletableFuture<GeneratedChunk> generate(int seed, long index, int cx, int cz,
            @Nullable LongPredicate stillNeeded, @Nullable Long2FloatFunction priority) {
        return PrefabPaint.generate(index, cx, cz, environmentId, LABEL, this::paint);
    }

    /** Every registered run's rooms that reach into the chunk starting at {@code (baseX, baseZ)}. */
    private void paint(@Nonnull GeneratedChunk chunk, int baseX, int baseZ, boolean onCaller) {
        for (DungeonRun run : copy.runs()) {
            if (!run.touches(baseX, baseZ, ChunkUtil.SIZE)) {
                continue;
            }
            long start = System.nanoTime();
            RoomPainter.paint(chunk, baseX, baseZ, run, copy.pools());
            run.painted(System.nanoTime() - start, onCaller);
        }
    }
}
