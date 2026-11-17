package com.ziggfreed.common.instance.dungeon.gen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.blockspawner.BlockSpawnerEntry;
import com.hypixel.hytale.builtin.blockspawner.BlockSpawnerPlugin;
import com.hypixel.hytale.builtin.blockspawner.BlockSpawnerTable;
import com.hypixel.hytale.builtin.blockspawner.state.BlockSpawner;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.Axis;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.HashUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.VariantRotation;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.blocktype.component.BlockPhysics;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.PrefabWeights;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferCall;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockRotationUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedBlockChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedChunk;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.ziggfreed.common.instance.dungeon.layout.Box;
import com.ziggfreed.common.instance.dungeon.layout.asset.PrefabPoolIndex;
import com.ziggfreed.common.util.SafeLog;

/**
 * Writes a job's pieces (a run's rooms, a hub's districts: {@link PaintJob}) into one generating chunk, on a painter
 * thread, the way vanilla's own world generator writes a prefab ({@code ChunkGeneratorExecution.setBlock}): block,
 * rotation, filler, support, the cell's block entity (cloned) and its fluid. Each piece is written in three passes,
 * every one clipped to the piece's {@code Box} (a block a piece holds outside its box is never written, so it never
 * lands in a neighbour):
 * <ol>
 *   <li>its base prefab, then each overlay on top in order (a room's one, {@link RoomOverlays}), then every
 *       {@code Prefab_Spawner_Block} child they hold, one prefab of the child's pool picked from the job's seed
 *       and the child's place, so every chunk the child crosses makes the same pick (vanilla's own expansion,
 *       {@code PrefabPasteUtil.generateChild}, to the same depth);</li>
 *   <li>a {@code Block_Spawner_Block} cell is resolved here, from the job's seed, to the block its table picks, so no
 *       spawner is left for the engine to wake later (a woken spawner dirties its section, which then stays loaded
 *       until the copy closes); a container is painted as air unless the piece keeps its containers;</li>
 *   <li>every cell of the box the passes left unlisted (a listed air cell counts as listed) takes the piece's fill
 *       block, so a piece cut to sit in solid rock shows no holes in an empty world.</li>
 * </ol>
 * Entities are left for someone else (a room's logic): the painter never places one. Nothing here touches a store or
 * a world.
 */
final class RoomPainter {

    /** How deep spawner children nest, as vanilla's world generator caps it. */
    static final int MAX_DEPTH = 10;

    /** Vanilla's own salt for a spawner's pick, kept so a pick reads the same way the engine's would. */
    private static final long SPAWNER_SALT = 0x9ab8cd9fL;

    private static final long OVERLAY_SALT = 0x5DEECE66DL;

    private static final String LOG = "[dungeon] ";

    /** Each painter thread's listed-cell bitmap, reused room after room. */
    private static final ThreadLocal<Mask> MASKS = ThreadLocal.withInitial(Mask::new);

    /** What has been warned about once already. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static final IPrefabBuffer.ColumnPredicate<Walk> COLUMNS = (x, z, blocks, walk) -> walk.columnIn(x, z);

    private static final IPrefabBuffer.BlockConsumer<Walk> BLOCKS =
            (x, y, z, blockId, holder, support, rotation, filler, walk, fluidId, fluidLevel) ->
                    walk.block(x, y, z, blockId, holder, support, rotation, filler, fluidId, fluidLevel);

    private static final IPrefabBuffer.EntityConsumer<Walk> ENTITIES =
            (x, z, holders, walk) -> walk.job.entitiesLeft(holders == null ? 0 : holders.length);

    private static final IPrefabBuffer.ChildConsumer<Walk> CHILDREN =
            (x, y, z, path, fitHeightmap, inheritSeed, inheritHeightCondition, weights, rotation, walk) ->
                    walk.child(x, y, z, path, weights, rotation);

    private RoomPainter() {
    }

    /** Paint every piece of {@code job} that reaches into the chunk starting at {@code (baseX, baseZ)}. */
    static void paint(@Nonnull GeneratedChunk chunk, int baseX, int baseZ, @Nonnull PaintJob job,
            @Nonnull PrefabPoolIndex pools) {
        Holder<ChunkStore>[] sections = chunk.getSections();
        int topY = sections.length * ChunkUtil.SIZE - 1;
        for (PaintPiece piece : job.pieces()) {
            Box box = piece.box();
            Clip clip = new Clip(Math.max(box.min().x(), baseX), Math.max(box.min().y(), ChunkUtil.MIN_Y),
                    Math.max(box.min().z(), baseZ), Math.min(box.max().x(), baseX + ChunkUtil.SIZE - 1),
                    Math.min(box.max().y(), topY), Math.min(box.max().z(), baseZ + ChunkUtil.SIZE - 1));
            if (clip.empty()) {
                continue;
            }
            try {
                paintPiece(chunk, baseX, baseZ, job, piece, clip, pools);
            } catch (Throwable t) {
                warnOnce("piece:" + piece.label(), LOG + "the " + piece.label() + " could not be painted into a "
                        + "chunk at " + baseX + " " + baseZ + " of " + job.describe()
                        + "; it stays partly unpainted there", t);
            }
        }
    }

    private static void paintPiece(@Nonnull GeneratedChunk chunk, int baseX, int baseZ, @Nonnull PaintJob job,
            @Nonnull PaintPiece piece, @Nonnull Clip clip, @Nonnull PrefabPoolIndex pools) {
        Mask mask = MASKS.get().reset(clip);
        List<Child> children = new ArrayList<>();
        int ox = piece.origin().x();
        int oy = piece.origin().y();
        int oz = piece.origin().z();
        Walk base = new Walk(chunk, baseX, baseZ, clip, mask, job, piece, pools, ox, oy, oz, piece.rotation(), 0,
                mix(job.seed(), ox, oy, oz), children);
        piece.base().forEach(COLUMNS, BLOCKS, ENTITIES, CHILDREN, base);
        List<IPrefabBuffer> overlays = piece.overlays();
        for (int i = 0; i < overlays.size(); i++) {
            // The first overlay keeps the salt a room's one overlay always had, so a run paints as it did.
            Walk top = new Walk(chunk, baseX, baseZ, clip, mask, job, piece, pools, ox, oy, oz, piece.rotation(), 0,
                    mix(job.seed() ^ (OVERLAY_SALT + i), ox, oy, oz), children);
            overlays.get(i).forEach(COLUMNS, BLOCKS, ENTITIES, CHILDREN, top);
        }
        expand(children, base);
        if (piece.fillBlock() >= 0) {
            fill(chunk, baseX, baseZ, clip, mask, piece.fillBlock());
        }
    }

    /** Expand each child, then its own children, depth first, under the parent walk's clip. */
    private static void expand(@Nonnull List<Child> children, @Nonnull Walk parent) {
        for (Child child : children) {
            if (child.depth() > MAX_DEPTH) {
                continue;
            }
            if (parent.clip.contains(child.x(), child.y(), child.z())) {
                parent.mask.set(child.x(), child.y(), child.z());
                parent.write(child.x(), child.y(), child.z(), BlockType.EMPTY_ID, null, -1,
                        RotationTuple.NONE_INDEX, FillerBlockUtil.NO_FILLER, Fluid.EMPTY_ID, 0);
                parent.job.childExpanded();
            }
            Path pick = pick(child, parent);
            if (pick == null) {
                continue;
            }
            List<Child> grandchildren = new ArrayList<>();
            Walk walk = new Walk(parent.chunk, parent.baseX, parent.baseZ, parent.clip, parent.mask, parent.job,
                    parent.piece, parent.pools, child.x(), child.y(), child.z(), child.rotation(), child.depth(),
                    mix(parent.job.seed(), child.x(), child.y(), child.z()), grandchildren);
            parent.job.child(pick).forEach(COLUMNS, BLOCKS, ENTITIES, CHILDREN, walk);
            expand(grandchildren, walk);
        }
    }

    /**
     * The prefab {@code child} pastes: one of its pool's files, weighted by its spawner's weights when it names any
     * (each named the way vanilla names it, its key below the pool's folder), else any, picked by a random seeded from
     * the run's seed and the child's place.
     */
    @Nullable
    private static Path pick(@Nonnull Child child, @Nonnull Walk parent) {
        List<Path> pool = parent.pools.paths(child.path());
        if (pool.isEmpty()) {
            warnOnce("pool:" + child.path(), LOG + "a spawner child names the prefab pool " + child.path()
                    + ", which resolves to no prefab a loaded pack ships; it is left empty", null);
            return null;
        }
        Random random = new Random(mix(parent.job.seed(), child.x(), child.y(), child.z()));
        PrefabWeights weights = child.weights();
        if (weights != null && weights.size() > 0 && pool.size() > 1) {
            List<String> keys = parent.pools.keys(child.path());
            Integer[] order = new Integer[pool.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            Integer chosen = weights.get(order, i -> weightName(child.path(), keys.get(i)), random);
            if (chosen != null) {
                return pool.get(chosen);
            }
        }
        return pool.get(pool.size() == 1 ? 0 : random.nextInt(pool.size()));
    }

    /** A pool member's name as a spawner's weights spell it: its key below the pool's folder, or the one prefab's name. */
    @Nonnull
    static String weightName(@Nonnull String pool, @Nonnull String key) {
        if (!pool.endsWith(".*")) {
            return pool;
        }
        String folder = pool.substring(0, pool.length() - 1);
        return key.startsWith(folder) ? key.substring(folder.length()) : key;
    }

    /** Every cell of {@code clip} no pass listed takes {@code fillBlock}. */
    private static void fill(@Nonnull GeneratedChunk chunk, int baseX, int baseZ, @Nonnull Clip clip,
            @Nonnull Mask mask, int fillBlock) {
        GeneratedBlockChunk blocks = chunk.getBlockChunk();
        Holder<ChunkStore>[] sections = chunk.getSections();
        BlockType type = BlockType.getAssetMap().getAsset(fillBlock);
        boolean supported = type != null && type.hasSupport();
        for (int y = clip.minY(); y <= clip.maxY(); y++) {
            Holder<ChunkStore> section = sections[ChunkUtil.indexSection(y)];
            for (int z = clip.minZ(); z <= clip.maxZ(); z++) {
                for (int x = clip.minX(); x <= clip.maxX(); x++) {
                    if (mask.get(x, y, z)) {
                        continue;
                    }
                    int lx = x - baseX;
                    int lz = z - baseZ;
                    blocks.setBlock(lx, y, lz, fillBlock, RotationTuple.NONE_INDEX, FillerBlockUtil.NO_FILLER);
                    if (supported) {
                        BlockPhysics.reset(section, lx, y, lz);
                    } else {
                        BlockPhysics.clear(section, lx, y, lz);
                    }
                }
            }
        }
    }

    /** A position's own seed, so a pick is the same whichever chunk makes it. */
    static long mix(long seed, int x, int y, int z) {
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= x * 0xC2B2AE3D27D4EB4FL;
        h = Long.rotateLeft(h, 31) * 0x165667B19E3779F9L;
        h ^= y * 0x27D4EB2F165667C5L;
        h = Long.rotateLeft(h, 29) * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        return h ^ (h >>> 32);
    }

    private static void warnOnce(@Nonnull String key, @Nonnull String line, @Nullable Throwable t) {
        if (WARNED.add(key.toLowerCase(Locale.ROOT))) {
            if (t == null) {
                SafeLog.warn(line);
            } else {
                SafeLog.warn(line, t);
            }
        }
    }

    // ==================== one walk of one prefab ====================

    /** A spawner child a walk met, in world blocks, its turn already summed with its parent's. */
    private record Child(int x, int y, int z, @Nonnull String path, @Nullable PrefabWeights weights,
                         @Nonnull PrefabRotation rotation, int depth) {
    }

    /** The blocks one room may write in this chunk, world coordinates, both corners in. */
    private record Clip(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        boolean empty() {
            return minX > maxX || minY > maxY || minZ > maxZ;
        }

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        int volume() {
            return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    /** Which cells of a room's clip some pass listed, air included. */
    private static final class Mask {
        private long[] bits = new long[512];
        private Clip clip = new Clip(0, 0, 0, -1, -1, -1);
        private int width;
        private int depth;

        @Nonnull
        Mask reset(@Nonnull Clip next) {
            clip = next;
            width = next.maxX() - next.minX() + 1;
            depth = next.maxZ() - next.minZ() + 1;
            int words = (next.volume() + 63) >>> 6;
            if (bits.length < words) {
                bits = new long[words];
            } else {
                Arrays.fill(bits, 0, words, 0L);
            }
            return this;
        }

        void set(int x, int y, int z) {
            int i = index(x, y, z);
            bits[i >>> 6] |= 1L << i;
        }

        boolean get(int x, int y, int z) {
            int i = index(x, y, z);
            return (bits[i >>> 6] & (1L << i)) != 0;
        }

        private int index(int x, int y, int z) {
            return ((y - clip.minY()) * depth + (z - clip.minZ())) * width + (x - clip.minX());
        }
    }

    /** One walk of one prefab buffer into one chunk, clipped to one piece. */
    private static final class Walk extends PrefabBufferCall {
        final GeneratedChunk chunk;
        final GeneratedBlockChunk blocks;
        final Holder<ChunkStore>[] sections;
        final int baseX;
        final int baseZ;
        final Clip clip;
        final Mask mask;
        final PaintJob job;
        final PaintPiece piece;
        final PrefabPoolIndex pools;
        final int ox;
        final int oy;
        final int oz;
        final int depth;
        final List<Child> children;

        Walk(@Nonnull GeneratedChunk chunk, int baseX, int baseZ, @Nonnull Clip clip, @Nonnull Mask mask,
                @Nonnull PaintJob job, @Nonnull PaintPiece piece, @Nonnull PrefabPoolIndex pools, int ox, int oy,
                int oz, @Nonnull PrefabRotation rotation, int depth, long seed, @Nonnull List<Child> children) {
            super(new Random(seed), rotation);
            this.chunk = chunk;
            this.blocks = chunk.getBlockChunk();
            this.sections = chunk.getSections();
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.clip = clip;
            this.mask = mask;
            this.job = job;
            this.piece = piece;
            this.pools = pools;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.depth = depth;
            this.children = children;
        }

        /** Does the prefab's column at {@code (x, z)}, turned, stand inside the clip? */
        boolean columnIn(int x, int z) {
            int wx = ox + x;
            int wz = oz + z;
            return wx >= clip.minX() && wx <= clip.maxX() && wz >= clip.minZ() && wz <= clip.maxZ();
        }

        /** One listed cell: marked, then written unless it stands above or below the room's box. */
        void block(int x, int y, int z, int blockId, @Nullable Holder<ChunkStore> holder, int support, int rotation,
                int filler, int fluidId, int fluidLevel) {
            int wx = ox + x;
            int wy = oy + y;
            int wz = oz + z;
            if (wy < clip.minY() || wy > clip.maxY()) {
                return;
            }
            mask.set(wx, wy, wz);
            write(wx, wy, wz, blockId, holder, support, rotation, filler, fluidId, fluidLevel);
        }

        /** A spawner child: kept for after the base and overlay are written, its turn summed with this walk's. */
        void child(int x, int y, int z, @Nonnull String path, @Nullable PrefabWeights weights,
                @Nonnull PrefabRotation turn) {
            if (depth >= MAX_DEPTH) {
                return;
            }
            children.add(new Child(ox + x, oy + y, oz + z, path, weights, rotation.add(turn), depth + 1));
        }

        /** Write one cell inside the clip, as vanilla's world generator writes a prefab's. */
        void write(int wx, int wy, int wz, int blockId, @Nullable Holder<ChunkStore> holder, int support,
                int rotation, int filler, int fluidId, int fluidLevel) {
            int id = blockId;
            int turn = rotation;
            int fill = filler;
            int supportValue = support;
            Holder<ChunkStore> state = holder;
            BlockType type = id == BlockType.EMPTY_ID ? null : BlockType.getAssetMap().getAsset(id);
            BlockSpawner spawner = spawnerOf(type, state);
            if (spawner != null) {
                Resolved resolved = resolve(spawner, wx, wy, wz, turn, job.seed());
                id = resolved == null ? BlockType.EMPTY_ID : resolved.blockId();
                type = resolved == null ? null : resolved.type();
                turn = resolved == null ? RotationTuple.NONE_INDEX : resolved.rotation();
                state = resolved == null ? null : resolved.holder();
                fill = FillerBlockUtil.NO_FILLER;
                supportValue = -1;
            }
            if (!piece.keepContainers() && containerOf(type, state)) {
                id = BlockType.EMPTY_ID;
                type = null;
                turn = RotationTuple.NONE_INDEX;
                state = null;
                fill = FillerBlockUtil.NO_FILLER;
                supportValue = -1;
            }
            int lx = wx - baseX;
            int lz = wz - baseZ;
            blocks.setBlock(lx, wy, lz, id, turn, fill);
            if (state != null) {
                chunk.setState(lx, wy, lz, state.clone());
            } else if (chunk.getState(lx, wy, lz) != null) {
                chunk.setState(lx, wy, lz, null);
            }
            Holder<ChunkStore> section = sections[ChunkUtil.indexSection(wy)];
            if (supportValue >= 0) {
                BlockPhysics.setSupportValue(section, lx, wy, lz, supportValue);
            } else if (type == null || !type.hasSupport()) {
                BlockPhysics.clear(section, lx, wy, lz);
            } else {
                BlockPhysics.reset(section, lx, wy, lz);
            }
            if (fluidId != Fluid.EMPTY_ID) {
                section.ensureAndGetComponent(FluidSection.getComponentType())
                        .setFluid(lx, wy, lz, fluidId, (byte) fluidLevel);
            } else {
                FluidSection fluids = section.getComponent(FluidSection.getComponentType());
                if (fluids != null) {
                    fluids.setFluid(lx, wy, lz, Fluid.EMPTY_ID, (byte) 0);
                }
            }
        }
    }

    // ==================== spawner blocks and containers ====================

    /** What a {@code Block_Spawner_Block} cell resolves to. */
    private record Resolved(int blockId, @Nonnull BlockType type, int rotation, @Nullable Holder<ChunkStore> holder) {
    }

    /** The cell's block spawner: its own block entity's, else its block type's default one; null for none. */
    @Nullable
    private static BlockSpawner spawnerOf(@Nullable BlockType type, @Nullable Holder<ChunkStore> holder) {
        if (holder == null && type == null) {
            return null;
        }
        ComponentType<ChunkStore, BlockSpawner> kind = SpawnerType.kind();
        if (kind == null) {
            return null;
        }
        BlockSpawner own = holder == null ? null : holder.getComponent(kind);
        if (own != null) {
            return own;
        }
        Holder<ChunkStore> fallback = type == null ? null : type.getBlockEntity();
        return fallback == null ? null : fallback.getComponent(kind);
    }

    /** Whether the cell is a container: its own block entity holds one, or its block type's default one does. */
    private static boolean containerOf(@Nullable BlockType type, @Nullable Holder<ChunkStore> holder) {
        ComponentType<ChunkStore, ItemContainerBlock> kind = ItemContainerBlock.getComponentType();
        if (holder != null && holder.getComponent(kind) != null) {
            return true;
        }
        Holder<ChunkStore> fallback = type == null ? null : type.getBlockEntity();
        return fallback != null && fallback.getComponent(kind) != null;
    }

    /**
     * The block a spawner's table picks for the cell, from the run's seed and the cell's place, turned the way the
     * engine's own resolve turns it ({@code BlockSpawnerPlugin}); null when its table or pick names nothing loaded,
     * which leaves the cell as air.
     */
    @Nullable
    private static Resolved resolve(@Nonnull BlockSpawner spawner, int x, int y, int z, int cellRotation, long seed) {
        String id = spawner.getBlockSpawnerId();
        BlockSpawnerTable table = id == null ? null : BlockSpawnerTable.getAssetMap().getAsset(id);
        BlockSpawnerEntry entry = table == null ? null : table.getEntries().get(HashUtil.random(x, y, z, seed + SPAWNER_SALT));
        String name = entry == null ? null : entry.getBlockName();
        int blockId = name == null ? AssetMapWithIndexes.NOT_FOUND : BlockType.getAssetMap().getIndex(name);
        BlockType type = blockId == AssetMapWithIndexes.NOT_FOUND ? null : BlockType.getAssetMap().getAsset(blockId);
        if (type == null) {
            warnOnce("spawner:" + id, LOG + "the block spawner '" + id + "' picks no loaded block; its cells are left "
                    + "as air", null);
            return null;
        }
        return new Resolved(blockId, type, turnOf(entry, type, x, y, z, cellRotation, seed), entry.getBlockComponents());
    }

    /** A spawner pick's rotation: none, a seeded yaw, or the spawner cell's own yaw, as the entry's mode says. */
    private static int turnOf(@Nonnull BlockSpawnerEntry entry, @Nonnull BlockType type, int x, int y, int z,
            int cellRotation, long seed) {
        BlockSpawnerEntry.RotationMode mode = entry.getRotationMode();
        VariantRotation variant = type.getVariantRotation();
        if (mode == BlockSpawnerEntry.RotationMode.NONE || variant == null || variant == VariantRotation.None) {
            return RotationTuple.NONE_INDEX;
        }
        Rotation yaw;
        if (mode == BlockSpawnerEntry.RotationMode.RANDOM) {
            int hash = (int) HashUtil.rehash(x, y, z, seed + SPAWNER_SALT);
            yaw = Rotation.NORMAL[(hash & 0xFFFF) % Rotation.NORMAL.length];
        } else {
            yaw = RotationTuple.get(cellRotation).yaw();
        }
        RotationTuple turned = BlockRotationUtil.getRotated(RotationTuple.NONE, Axis.Y, yaw, variant);
        return turned == null ? RotationTuple.NONE_INDEX : turned.index();
    }

    /**
     * The block spawner's component type, kept once it is read; while the engine's block spawner plugin is not up yet
     * (or not loaded) it reads null, is warned about once, and is asked again on the next call.
     */
    private static final class SpawnerType {
        @Nullable private static volatile ComponentType<ChunkStore, BlockSpawner> known;
        private static final AtomicBoolean WARNED_MISSING = new AtomicBoolean();

        @Nullable
        static ComponentType<ChunkStore, BlockSpawner> kind() {
            ComponentType<ChunkStore, BlockSpawner> kind = known;
            if (kind != null) {
                return kind;
            }
            try {
                BlockSpawnerPlugin plugin = BlockSpawnerPlugin.get();
                kind = plugin == null ? null : plugin.getBlockSpawnerComponentType();
            } catch (Throwable t) {
                kind = null;
            }
            if (kind == null) {
                if (WARNED_MISSING.compareAndSet(false, true)) {
                    SafeLog.warn(LOG + "the engine's block spawners cannot be read yet, so a Block_Spawner_Block in a "
                            + "generated room is painted as it stands until they can");
                }
                return null;
            }
            known = kind;
            return kind;
        }
    }
}
