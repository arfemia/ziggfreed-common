package com.ziggfreed.common.instance.dungeon.gen;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.blocktype.component.BlockPhysics;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedBlockChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedEntityChunk;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.ziggfreed.common.instance.dungeon.layout.Box;
import com.ziggfreed.common.instance.dungeon.layout.asset.PrefabPoolIndex;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's one route for a world generator of its own: every chunk the engine asks for hops to the painter
 * threads ({@link DungeonPainters}), starts as vanilla's void (the {@code Default} environment, untinted), and comes
 * back through the engine's own chunk load once its pieces are written ({@link #paint}, vanilla's prefab write, the
 * way {@code ChunkGeneratorExecution.setBlock} writes one). The generated dungeon's {@code ZigDungeon} Type and the
 * hub's {@code ZigHub} Type both go through it, so a change in how the engine asks for a chunk (Update 8's cubic
 * generator) is made here once.
 */
public final class PrefabPaint {

    /** What a generator writes into one chunk of the void, on a painter thread. */
    @FunctionalInterface
    public interface ChunkPaint {

        /**
         * Write into {@code chunk}, whose first block is {@code (baseX, baseZ)}. {@code onCaller} is true when the
         * painters were down and this runs on the asking thread. Nothing here may touch a store or a world.
         */
        void paint(@Nonnull GeneratedChunk chunk, int baseX, int baseZ, boolean onCaller);
    }

    private PrefabPaint() {
    }

    /**
     * The chunk at {@code (cx, cz)}: void, then whatever {@code paint} writes, made on a painter thread. A paint that
     * throws is warned about as {@code label} plus the chunk ({@code "[dungeon] a generated dungeon copy"}), and the
     * chunk comes back empty, so a world never waits on it.
     */
    @Nonnull
    public static CompletableFuture<GeneratedChunk> generate(long index, int cx, int cz, int environmentId,
            @Nonnull String label, @Nonnull ChunkPaint paint) {
        CompletableFuture<GeneratedChunk> out = new CompletableFuture<>();
        Thread asking = Thread.currentThread();
        DungeonPainters.run(() -> {
            try {
                GeneratedChunk chunk = empty(index, cx, cz, environmentId);
                paint.paint(chunk, cx << ChunkUtil.BITS, cz << ChunkUtil.BITS, Thread.currentThread() == asking);
                out.complete(chunk);
            } catch (Throwable t) {
                SafeLog.warn(label + " could not paint the chunk " + cx + " " + cz, t);
                out.complete(empty(index, cx, cz, environmentId));
            }
        });
        return out;
    }

    /** Paint every piece of {@code job} that reaches into the chunk starting at {@code (baseX, baseZ)}. */
    public static void paint(@Nonnull GeneratedChunk chunk, int baseX, int baseZ, @Nonnull PaintJob job,
            @Nonnull PrefabPoolIndex pools) {
        RoomPainter.paint(chunk, baseX, baseZ, job, pools);
    }

    /**
     * Lay {@code blockId} in every cell of {@code box} that lies in the chunk starting at {@code (baseX, baseZ)}, over
     * whatever stands there, its support set the way a painted block's is: a floor under a hub's lots.
     */
    public static void fillBox(@Nonnull GeneratedChunk chunk, int baseX, int baseZ, @Nonnull Box box, int blockId) {
        int minX = Math.max(box.min().x(), baseX);
        int maxX = Math.min(box.max().x(), baseX + ChunkUtil.SIZE - 1);
        int minZ = Math.max(box.min().z(), baseZ);
        int maxZ = Math.min(box.max().z(), baseZ + ChunkUtil.SIZE - 1);
        Holder<ChunkStore>[] sections = chunk.getSections();
        int minY = Math.max(box.min().y(), ChunkUtil.MIN_Y);
        int maxY = Math.min(box.max().y(), sections.length * ChunkUtil.SIZE - 1);
        if (minX > maxX || minZ > maxZ || minY > maxY) {
            return;
        }
        GeneratedBlockChunk blocks = chunk.getBlockChunk();
        BlockType type = BlockType.getAssetMap().getAsset(blockId);
        boolean supported = type != null && type.hasSupport();
        for (int y = minY; y <= maxY; y++) {
            Holder<ChunkStore> section = sections[ChunkUtil.indexSection(y)];
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    int lx = x - baseX;
                    int lz = z - baseZ;
                    blocks.setBlock(lx, y, lz, blockId, RotationTuple.NONE_INDEX, FillerBlockUtil.NO_FILLER);
                    if (supported) {
                        BlockPhysics.reset(section, lx, y, lz);
                    } else {
                        BlockPhysics.clear(section, lx, y, lz);
                    }
                }
            }
        }
    }

    /** The {@code Default} environment's id, or 0 when it is not loaded. */
    public static int defaultEnvironment() {
        return Math.max(Environment.getAssetMap().getIndex("Default"), 0);
    }

    /** An empty chunk in {@code environmentId}, untinted, as vanilla's void generator makes one. */
    @Nonnull
    public static GeneratedChunk empty(long index, int cx, int cz, int environmentId) {
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
