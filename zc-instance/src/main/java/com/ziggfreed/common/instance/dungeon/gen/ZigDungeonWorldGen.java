package com.ziggfreed.common.instance.dungeon.gen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.blocktype.component.BlockPhysics;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.PrefabWeights;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedBlockChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedEntityChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.core.universe.world.worldgen.WorldGenTimingsCollector;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.ziggfreed.common.util.SafeLog;

import it.unimi.dsi.fastutil.longs.Long2FloatFunction;

/**
 * SPIKE (PD2): paints fixed rooms into a copy's chunks as they generate, on its own worker threads, void
 * elsewhere. The engine calls {@link #generate} on whichever thread asked for the chunk (the world thread for a
 * load miss), so every step past the call hops to {@link #PAINTERS}; the chunk then reaches the world thread
 * through the engine's own {@code postLoadChunk}.
 *
 * <p>For each room overlapping the chunk it walks the prefab buffer with the room's rotation, keeps only the
 * columns inside the chunk, and writes block, rotation, filler, support and the cell's authored block entity
 * (cloned). Entities are counted, never placed (room logic is placed later, PD3). A {@code Prefab_Spawner_Block}
 * child is expanded here, as vanilla's worldgen does: its cell turns to air and one prefab from its pool is
 * picked by a random seeded from the world seed and the child's world position, so every chunk the child
 * crosses makes the same pick.
 */
public final class ZigDungeonWorldGen implements IWorldGen {

    /** A room at a fixed origin (where its anchor goes), turned by {@code rotation}: the spike's layout. */
    public record Placement(@Nonnull String label, @Nonnull String prefabKey, int x, int y, int z,
            @Nonnull PrefabRotation rotation) {
    }

    /** A child the painter picked: where its anchor went, which file, how turned. Read back by the spike. */
    public record ChildPick(int x, int y, int z, @Nonnull Path prefab, @Nonnull PrefabRotation rotation, int depth) {
    }

    static final String STONE = "Dungeon/Stone/Level_1/Room_Small/Stone_Level_1_Room_Small_001.prefab.json";
    static final String LABYRINTH = "Dungeon/Labyrinth/Room/Single/Labyrinth_Room_Single_001.prefab.json";

    /** A: the Stone room across four chunks; B: the same turned 90; C: a room with a child; D, E: far along Z. */
    public static final List<Placement> SPIKE_LAYOUT = List.of(
            new Placement("A", STONE, 256, 64, 264, PrefabRotation.ROTATION_0),
            new Placement("B", STONE, 352, 64, 264, PrefabRotation.ROTATION_90),
            new Placement("C", LABYRINTH, 448, 64, 448, PrefabRotation.ROTATION_0),
            new Placement("D", STONE, 256, 64, 1_048_840, PrefabRotation.ROTATION_0),
            new Placement("E", STONE, 256, 64, 16_777_480, PrefabRotation.ROTATION_0));

    /** Vanilla worldgen's child depth cap ({@code PrefabPasteUtil.MAX_RECURSION_DEPTH}). */
    static final int MAX_DEPTH = 10;

    private static final String LOG = "[pd2-spike] ";
    private static final String PREFAB_SUFFIX = ".prefab.json";

    /** The painter threads. Daemon; the build card stops them at shutdown. */
    private static final ExecutorService PAINTERS = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger next = new AtomicInteger();

        @Override
        public Thread newThread(@Nonnull Runnable task) {
            Thread thread = new Thread(task, "zc-dungeon-gen-" + next.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    /** What generation cost and did, for the spike's report. */
    public static final Stats STATS = new Stats();

    /** Children picked, by their anchor's world position. */
    static final Map<String, ChildPick> PICKS = new ConcurrentHashMap<>();

    private static final Map<String, Path> TOP_PATHS = new ConcurrentHashMap<>();
    private static final Map<String, List<Path>> POOLS = new ConcurrentHashMap<>();

    private final int environmentId;

    ZigDungeonWorldGen() {
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
        return new Transform[]{new Transform(0, 80, 0)};
    }

    @Nonnull
    @Override
    public CompletableFuture<GeneratedChunk> generate(int seed, long index, int cx, int cz,
            @Nullable LongPredicate stillNeeded, @Nullable Long2FloatFunction priority) {
        long asked = System.nanoTime();
        STATS.callers.add(Thread.currentThread().getName());
        STATS.calls.computeIfAbsent(index, i -> new AtomicInteger()).incrementAndGet();
        return CompletableFuture.supplyAsync(() -> paintChunk(seed, index, cx, cz, asked), PAINTERS);
    }

    // ==================== one chunk ====================

    @Nonnull
    private GeneratedChunk paintChunk(int seed, long index, int cx, int cz, long asked) {
        long start = System.nanoTime();
        STATS.painters.add(Thread.currentThread().getName());
        GeneratedBlockChunk blocks = new GeneratedBlockChunk(index, cx, cz);
        Holder<ChunkStore>[] sections = GeneratedChunk.makeSections();
        GeneratedChunk chunk = new GeneratedChunk(blocks, new GeneratedEntityChunk(), sections);
        for (int x = 0; x < ChunkUtil.SIZE; x++) {
            for (int z = 0; z < ChunkUtil.SIZE; z++) {
                if (environmentId != 0) {
                    blocks.setEnvironmentColumn(x, z, environmentId);
                }
                blocks.setTint(x, z, 0);
            }
        }
        Counts counts = new Counts();
        int baseX = cx << ChunkUtil.BITS;
        int baseZ = cz << ChunkUtil.BITS;
        for (Placement room : SPIKE_LAYOUT) {
            try {
                IPrefabBuffer buffer = topBuffer(room.prefabKey());
                if (buffer == null || !overlaps(buffer, room.rotation(), room.x(), room.z(), baseX, baseZ)) {
                    continue;
                }
                paintPrefab(new Paint(chunk, sections, baseX, baseZ, room.x(), room.y(), room.z(), seed, 0,
                        room.rotation(), counts), buffer);
                counts.rooms++;
            } catch (Throwable t) {
                SafeLog.warn(LOG + "room " + room.label() + " could not be painted into chunk " + cx + " " + cz, t);
            }
        }
        long end = System.nanoTime();
        STATS.chunks.add(new long[]{index, end - start, start - asked, counts.cells, counts.holders, counts.rooms});
        STATS.entitiesSkipped.addAndGet(counts.entities);
        STATS.children.addAndGet(counts.children);
        STATS.fluidsSkipped.addAndGet(counts.fluids);
        return chunk;
    }

    /** Walk one prefab buffer into the chunk: blocks, block entities and support; children recursively. */
    private static void paintPrefab(@Nonnull Paint paint, @Nonnull IPrefabBuffer buffer) {
        buffer.forEach(
                (x, z, blocks, p) -> ChunkUtil.isWithinLocalChunk(p.ox + x - p.baseX, p.oz + z - p.baseZ),
                (x, y, z, blockId, holder, supportValue, rotation, filler, p, fluidId, fluidLevel) ->
                        p.put(p.ox + x - p.baseX, p.oy + y, p.oz + z - p.baseZ, blockId, holder, supportValue,
                                rotation, filler, fluidId),
                (x, z, holders, p) -> p.counts.entities += holders == null ? 0 : holders.length,
                (x, y, z, path, fitHeightmap, inheritSeed, inheritHeightCondition, weights, rotation, p) ->
                        expandChild(p, x, y, z, path, weights, rotation),
                paint);
    }

    /**
     * A {@code Prefab_Spawner_Block}'s child: the spawner cell becomes air, then one prefab of its pool is
     * painted at the cell, turned by the parent's rotation plus the spawner's own.
     */
    private static void expandChild(@Nonnull Paint p, int x, int y, int z, @Nonnull String path,
            @Nullable PrefabWeights weights, @Nonnull PrefabRotation rotation) {
        if (p.depth >= MAX_DEPTH) {
            return;
        }
        int wx = p.ox + x;
        int wy = p.oy + y;
        int wz = p.oz + z;
        int lx = wx - p.baseX;
        int lz = wz - p.baseZ;
        if (ChunkUtil.isWithinLocalChunk(lx, lz) && p.inColumn(wy)) {
            p.blocks.setBlock(lx, wy, lz, BlockType.EMPTY_ID, RotationTuple.NONE_INDEX, FillerBlockUtil.NO_FILLER);
            p.chunk.setState(lx, wy, lz, null);
        }
        List<Path> pool = pool(path);
        if (pool.isEmpty()) {
            SafeLog.warn(LOG + "child pool " + path + " resolved to no prefab");
            return;
        }
        Random random = new Random(mix(p.seed, wx, wy, wz));
        Path pick = null;
        if (weights != null && weights.size() > 0) {
            pick = weights.get(pool.toArray(new Path[0]), ZigDungeonWorldGen::stem, random);
        }
        if (pick == null) {
            pick = pool.get(random.nextInt(pool.size()));
        }
        PrefabRotation turned = p.rotation.add(rotation);
        PICKS.putIfAbsent(wx + "," + wy + "," + wz, new ChildPick(wx, wy, wz, pick, turned, p.depth + 1));
        IPrefabBuffer child = PrefabBufferUtil.getCached(pick);
        paintPrefab(new Paint(p.chunk, p.sections, p.baseX, p.baseZ, wx, wy, wz, p.seed, p.depth + 1, turned,
                p.counts), child);
        p.counts.children++;
    }

    // ==================== lookups ====================

    @Nullable
    static IPrefabBuffer topBuffer(@Nonnull String prefabKey) {
        Path path = TOP_PATHS.computeIfAbsent(prefabKey, key -> PrefabStore.get().findAssetPrefabPath(key));
        return path == null ? null : PrefabBufferUtil.getCached(path);
    }

    /** A spawner's pool: {@code A.B.C.*} is every prefab in folder {@code A/B/C}, {@code A.B.C} the one file. */
    @Nonnull
    static List<Path> pool(@Nonnull String dotted) {
        return POOLS.computeIfAbsent(dotted, key -> {
            boolean all = key.endsWith(".*");
            String rel = (all ? key.substring(0, key.length() - 2) : key).replace('.', '/');
            if (!all) {
                Path one = PrefabStore.get().findAssetPrefabPath(rel + PREFAB_SUFFIX);
                return one == null ? List.of() : List.of(one);
            }
            Path dir = PrefabStore.get().findAssetPrefabPath(rel);
            if (dir == null || !Files.isDirectory(dir)) {
                return List.of();
            }
            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(f -> f.getFileName().toString().endsWith(PREFAB_SUFFIX))
                        .sorted(Comparator.comparing(f -> f.getFileName().toString()))
                        .toList();
            } catch (IOException e) {
                SafeLog.warn(LOG + "child pool " + key + " could not be listed", e);
                return List.of();
            }
        });
    }

    @Nonnull
    static String stem(@Nonnull Path prefab) {
        String name = prefab.getFileName().toString();
        return name.endsWith(PREFAB_SUFFIX) ? name.substring(0, name.length() - PREFAB_SUFFIX.length()) : name;
    }

    static boolean overlaps(@Nonnull IPrefabBuffer buffer, @Nonnull PrefabRotation rotation, int ox, int oz,
            int baseX, int baseZ) {
        int minX = ox + buffer.getMinX(rotation);
        int maxX = ox + buffer.getMaxX(rotation);
        int minZ = oz + buffer.getMinZ(rotation);
        int maxZ = oz + buffer.getMaxZ(rotation);
        return maxX >= baseX && minX < baseX + ChunkUtil.SIZE && maxZ >= baseZ && minZ < baseZ + ChunkUtil.SIZE;
    }

    /** A position's own seed, so a pick is the same whichever chunk makes it. */
    static long mix(int seed, int x, int y, int z) {
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= x * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 31) * 0x165667B19E3779F9L;
        h ^= y * 0x27D4EB2F165667C5L;
        h = Long.rotateLeft(h, 29) * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        return h ^ (h >>> 32);
    }

    // ==================== state ====================

    /** One walk of one buffer into one chunk. */
    private static final class Paint extends PrefabBufferCall {
        final GeneratedChunk chunk;
        final GeneratedBlockChunk blocks;
        final Holder<ChunkStore>[] sections;
        final int baseX;
        final int baseZ;
        final int ox;
        final int oy;
        final int oz;
        final int seed;
        final int depth;
        final Counts counts;

        Paint(@Nonnull GeneratedChunk chunk, @Nonnull Holder<ChunkStore>[] sections, int baseX, int baseZ, int ox,
                int oy, int oz, int seed, int depth, @Nonnull PrefabRotation rotation, @Nonnull Counts counts) {
            super(new Random(mix(seed, ox, oy, oz)), rotation);
            this.chunk = chunk;
            this.blocks = chunk.getBlockChunk();
            this.sections = sections;
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.seed = seed;
            this.depth = depth;
            this.counts = counts;
        }

        /** Is {@code y} inside this generated column: its own section holders bound it, never a fixed world height. */
        boolean inColumn(int y) {
            return y >= ChunkUtil.MIN_Y && ChunkUtil.indexSection(y) < sections.length;
        }

        /** One cell, in chunk-local x and z and world y, as vanilla's {@code ChunkGeneratorExecution.setBlock} writes it. */
        void put(int lx, int y, int lz, int blockId, @Nullable Holder<ChunkStore> holder, int supportValue,
                int rotation, int filler, int fluidId) {
            if (!inColumn(y)) {
                return;
            }
            blocks.setBlock(lx, y, lz, blockId, rotation, filler);
            chunk.setState(lx, y, lz, holder == null ? null : holder.clone());
            Holder<ChunkStore> section = sections[ChunkUtil.indexSection(y)];
            if (supportValue >= 0) {
                BlockPhysics.setSupportValue(section, lx, y, lz, supportValue);
            } else {
                BlockType type = BlockType.getAssetMap().getAsset(blockId);
                if (type == null || !type.hasSupport()) {
                    BlockPhysics.clear(section, lx, y, lz);
                } else {
                    BlockPhysics.reset(section, lx, y, lz);
                }
            }
            if (blockId != BlockType.EMPTY_ID) {
                counts.cells++;
            }
            if (holder != null) {
                counts.holders++;
            }
            if (fluidId != 0) {
                counts.fluids++;
            }
        }
    }

    private static final class Counts {
        int rooms;
        long cells;
        long holders;
        long entities;
        long children;
        long fluids;
    }

    /** What generation cost and did, across every chunk this server generated with the Type. */
    public static final class Stats {
        /** Per chunk: index, paint ns, queue wait ns, cells written, block entities written, rooms painted. */
        final ConcurrentLinkedQueue<long[]> chunks = new ConcurrentLinkedQueue<>();
        final Set<String> callers = ConcurrentHashMap.newKeySet();
        final Set<String> painters = ConcurrentHashMap.newKeySet();
        final Map<Long, AtomicInteger> calls = new ConcurrentHashMap<>();
        final AtomicLong entitiesSkipped = new AtomicLong();
        final AtomicLong children = new AtomicLong();
        final AtomicLong fluidsSkipped = new AtomicLong();
    }
}
