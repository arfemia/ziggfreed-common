package com.ziggfreed.common.world.temp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.record.BlockRecordSection;

/**
 * The crumble sweep: every {@value #INTERVAL_SEC} seconds it visits the chunk sections holding a temporary block's
 * record (its query is the record component, so a world with none costs nothing) and reads each recorded cell through
 * {@link TempBlocks#sweep}, deferred onto the world thread after the tick, since a block write inside a chunk-store
 * system's tick could change the sections it is walking: a record whose block is gone (broken, by anything) is
 * forgotten, and a block whose time is up crumbles. A section that loads after a restart is swept the same way, so a
 * wall left standing by a shutdown comes down once its chunk is back.
 */
public final class TempBlockSystem extends EntityTickingSystem<ChunkStore> {

    /** How often the sweep reads the records, in seconds of server time. */
    public static final float INTERVAL_SEC = 0.25f;

    @Nonnull private final ComponentType<ChunkStore, BlockRecordSection<TempBlock>> recordType;
    @Nonnull private final Query<ChunkStore> query;

    /** Each world store's time since its last sweep. */
    private final Map<Store<ChunkStore>, float[]> sinceLast = Collections.synchronizedMap(new WeakHashMap<>());

    /** Each world store whose current tick sweeps (its interval came round). */
    private final Map<Store<ChunkStore>, Boolean> sweeping = Collections.synchronizedMap(new WeakHashMap<>());

    TempBlockSystem(@Nonnull ComponentType<ChunkStore, BlockRecordSection<TempBlock>> recordType) {
        this.recordType = recordType;
        this.query = recordType;
    }

    @Nonnull
    @Override
    public Query<ChunkStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<ChunkStore> store) {
        float[] since = sinceLast.computeIfAbsent(store, s -> new float[1]);
        since[0] += dt;
        if (since[0] < INTERVAL_SEC) {
            return;
        }
        since[0] = 0.0f;
        sweeping.put(store, Boolean.TRUE);
        try {
            super.tick(dt, systemIndex, store);
        } finally {
            sweeping.remove(store);
        }
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        try {
            if (!Boolean.TRUE.equals(sweeping.get(store))) {
                return;
            }
            BlockRecordSection<TempBlock> records = archetypeChunk.getComponent(index, recordType);
            if (records == null || records.recordCount() == 0) {
                return;
            }
            List<int[]> cells = new ArrayList<>();
            records.forEachRecord((localX, localY, localZ, record) -> {
                if (record.hasCell()) {
                    cells.add(new int[] {record.x(), record.y(), record.z()});
                }
            });
            if (cells.isEmpty()) {
                return;
            }
            World world = store.getExternalData().getWorld();
            if (world == null) {
                return;
            }
            world.execute(() -> {
                long now = System.currentTimeMillis();
                for (int[] cell : cells) {
                    TempBlocks.sweep(world, cell[0], cell[1], cell[2], now);
                }
            });
        } catch (Throwable t) {
            SafeLog.fine("[temp] a section's temporary blocks could not be swept: " + t.getMessage());
        }
    }
}
