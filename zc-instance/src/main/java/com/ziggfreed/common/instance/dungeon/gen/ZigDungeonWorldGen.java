package com.ziggfreed.common.instance.dungeon.gen;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedBlockChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedEntityChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.core.universe.world.worldgen.WorldGenTimingsCollector;
import com.ziggfreed.common.instance.dungeon.AreaLayout;
import com.ziggfreed.common.util.SafeLog;

import it.unimi.dsi.fastutil.longs.Long2FloatFunction;

/**
 * One generated dungeon copy's world generator: an empty world (vanilla's void, the {@code Default} environment) in
 * which every chunk a registered run's rooms reach is painted with them as it generates ({@link RoomPainter}). The
 * engine asks for a chunk on whatever thread wants it (the world thread on a load miss), so every chunk hops to the
 * painter threads ({@link DungeonPainters}) before anything is read, and comes back to the world through the engine's
 * own chunk load. The runs it reads are its copy's ({@link ZigDungeonWorldGenProvider}).
 *
 * <p>A clean generated chunk may unload and generate again: the same run, seed and room list paint it the same way
 * every time.
 */
final class ZigDungeonWorldGen implements IWorldGen {

    private static final String LOG = "[dungeon] ";

    private final ZigDungeonWorldGenProvider copy;
    private final int environmentId;

    ZigDungeonWorldGen(@Nonnull ZigDungeonWorldGenProvider copy) {
        this.copy = copy;
        int index = Environment.getAssetMap().getIndex("Default");
        this.environmentId = Math.max(index, 0);
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
        CompletableFuture<GeneratedChunk> out = new CompletableFuture<>();
        Thread asking = Thread.currentThread();
        DungeonPainters.run(() -> {
            try {
                out.complete(paint(index, cx, cz, Thread.currentThread() == asking));
            } catch (Throwable t) {
                SafeLog.warn(LOG + "the chunk " + cx + " " + cz + " of a generated dungeon copy could not be painted", t);
                out.complete(empty(index, cx, cz));
            }
        });
        return out;
    }

    /** The chunk at {@code (cx, cz)}: void, with every registered run's rooms that reach into it painted. */
    @Nonnull
    private GeneratedChunk paint(long index, int cx, int cz, boolean onCaller) {
        GeneratedChunk chunk = empty(index, cx, cz);
        int baseX = cx << ChunkUtil.BITS;
        int baseZ = cz << ChunkUtil.BITS;
        for (DungeonRun run : copy.runs()) {
            if (!run.touches(baseX, baseZ, ChunkUtil.SIZE)) {
                continue;
            }
            long start = System.nanoTime();
            RoomPainter.paint(chunk, baseX, baseZ, run, copy.pools());
            run.painted(System.nanoTime() - start, onCaller);
        }
        return chunk;
    }

    /** An empty chunk in the {@code Default} environment, untinted, as vanilla's void generator makes one. */
    @Nonnull
    private GeneratedChunk empty(long index, int cx, int cz) {
        GeneratedBlockChunk blocks = new GeneratedBlockChunk(index, cx, cz);
        for (int x = 0; x < ChunkUtil.SIZE; x++) {
            for (int z = 0; z < ChunkUtil.SIZE; z++) {
                if (environmentId != 0) {
                    blocks.setEnvironmentColumn(x, z, environmentId);
                }
                blocks.setTint(x, z, 0);
            }
        }
        Holder<ChunkStore>[] sections = GeneratedChunk.makeSections();
        return new GeneratedChunk(blocks, new GeneratedEntityChunk(), sections);
    }
}
