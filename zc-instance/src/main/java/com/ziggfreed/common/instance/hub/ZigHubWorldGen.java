package com.ziggfreed.common.instance.hub;

import java.util.concurrent.CompletableFuture;
import java.util.function.LongPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.worldgen.GeneratedChunk;
import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.core.universe.world.worldgen.WorldGenTimingsCollector;
import com.ziggfreed.common.instance.dungeon.gen.PrefabPaint;
import com.ziggfreed.common.instance.dungeon.layout.Box;

import it.unimi.dsi.fastutil.longs.Long2FloatFunction;

/**
 * One hub copy's world generator: vanilla's void, the {@code Default} environment, with the hub's ground written into
 * every chunk it reaches ({@link HubPlan#paint}), through the library's one generator route ({@link PrefabPaint}), so
 * no chunk is painted on a world thread. A chunk outside the hub's ground stays void. A clean chunk may unload and
 * generate again: the same plan paints it the same way every time.
 */
final class ZigHubWorldGen implements IWorldGen {

    private static final String LABEL = "[hub] a hub copy";

    private final ZigHubWorldGenProvider copy;
    private final int environmentId;
    private final Box reach;

    ZigHubWorldGen(@Nonnull ZigHubWorldGenProvider copy) {
        this.copy = copy;
        this.environmentId = PrefabPaint.defaultEnvironment();
        this.reach = HubLots.reach();
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
        return new Transform[]{new Transform(0.5, HubLots.FLOOR_Y + 1.0, 0.5)};
    }

    @Nonnull
    @Override
    public CompletableFuture<GeneratedChunk> generate(int seed, long index, int cx, int cz,
            @Nullable LongPredicate stillNeeded, @Nullable Long2FloatFunction priority) {
        return PrefabPaint.generate(index, cx, cz, environmentId, LABEL, this::paint);
    }

    /** The hub's ground in the chunk starting at {@code (baseX, baseZ)}, when it reaches that far. */
    private void paint(@Nonnull GeneratedChunk chunk, int baseX, int baseZ, boolean onCaller) {
        if (reach.max().x() < baseX || reach.min().x() >= baseX + ChunkUtil.SIZE
                || reach.max().z() < baseZ || reach.min().z() >= baseZ + ChunkUtil.SIZE) {
            return;
        }
        copy.plan().paint(chunk, baseX, baseZ, copy.pools());
    }
}
