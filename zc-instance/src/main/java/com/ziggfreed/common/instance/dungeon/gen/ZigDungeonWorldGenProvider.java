package com.ziggfreed.common.instance.dungeon.gen;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider;
import com.ziggfreed.common.instance.dungeon.layout.RoomCatalogue;
import com.ziggfreed.common.instance.dungeon.layout.asset.DungeonRoomConfig;
import com.ziggfreed.common.instance.dungeon.layout.asset.PrefabPoolIndex;

/**
 * The world generator Type {@code ZigDungeon}, registered into the engine's own {@code IWorldGenProvider.CODEC} at
 * setup, which zc's generated dungeon template names ({@code "WorldGen": {"Type": "ZigDungeon"}}). Each copy decodes
 * its own, so this object is the copy's: it holds the copy's registered runs (never saved, since a copy never outlives
 * its server) and hands its generator ({@link ZigDungeonWorldGen}) every run whose region a chunk falls in. It carries
 * no fields of its own.
 */
public final class ZigDungeonWorldGenProvider implements IWorldGenProvider {

    /** The Type key in {@code IWorldGenProvider.CODEC}. */
    public static final String TYPE = "ZigDungeon";

    public static final BuilderCodec<ZigDungeonWorldGenProvider> CODEC = BuilderCodec
            .builder(ZigDungeonWorldGenProvider.class, ZigDungeonWorldGenProvider::new)
            .documentation("A generated dungeon's world: empty, but for the rooms of every run a party takes in it, "
                    + "written by ziggfreed-common as the ground around the party loads. Named by ziggfreed-common's "
                    + "generated dungeon template; a dungeon with an Area.Layout sets it on every copy it opens.")
            .build();

    /** Every run registered in the copy, by lane and run ({@link #key}). */
    private final Map<Long, DungeonRun> runs = new ConcurrentHashMap<>();
    /** Each run being laid out and prepared, or done, by lane and run, so one is never built twice. */
    private final Map<Long, CompletableFuture<DungeonRun>> opening = new ConcurrentHashMap<>();
    /** Each lane's latest run. */
    private final Map<Integer, DungeonRun> current = new ConcurrentHashMap<>();

    @Nullable private volatile PrefabPoolIndex pools;
    @Nullable private volatile RoomCatalogue catalogue;

    public ZigDungeonWorldGenProvider() {
    }

    @Nonnull
    @Override
    public IWorldGen getGenerator() {
        return new ZigDungeonWorldGen(this);
    }

    /** The generated dungeon provider {@code world} runs on, or null when it runs on another generator. */
    @Nullable
    public static ZigDungeonWorldGenProvider of(@Nullable World world) {
        return world != null && world.getWorldConfig().getWorldGenProvider() instanceof ZigDungeonWorldGenProvider copy
                ? copy : null;
    }

    /**
     * Make sure a copy about to start generates with this Type, whatever generator its template names (a pack's own
     * template for a generated dungeon). Run in the copy's config step, before the world starts.
     */
    public static void install(@Nonnull WorldConfig config) {
        if (!(config.getWorldGenProvider() instanceof ZigDungeonWorldGenProvider)) {
            config.setWorldGenProvider(new ZigDungeonWorldGenProvider());
        }
    }

    /** Every run registered in the copy. */
    @Nonnull
    Collection<DungeonRun> runs() {
        return runs.values();
    }

    /** Lane {@code area}'s latest registered run, or null when it has none. */
    @Nullable
    DungeonRun current(int area) {
        return current.get(area);
    }

    /**
     * Run {@code run} of lane {@code area}: the one already registered or under way, else the one {@code build} makes
     * (called once), registered the moment it is made, before its future completes.
     */
    @Nonnull
    CompletableFuture<DungeonRun> open(int area, int run, @Nonnull Supplier<CompletableFuture<DungeonRun>> build) {
        return opening.computeIfAbsent(key(area, run), k -> build.get().thenApply(made -> {
            runs.put(k, made);
            current.merge(area, made, (was, now) -> now.run >= was.run ? now : was);
            return made;
        }));
    }

    /** The copy's prefab pools, read once per copy (packs do not reload under a running copy). */
    @Nonnull
    PrefabPoolIndex pools() {
        PrefabPoolIndex known = pools;
        if (known == null) {
            synchronized (this) {
                known = pools;
                if (known == null) {
                    known = PrefabPoolIndex.live();
                    pools = known;
                }
            }
        }
        return known;
    }

    /** The copy's room catalogue, folded and counted once per copy over {@link #pools()}. */
    @Nonnull
    RoomCatalogue catalogue() {
        RoomCatalogue known = catalogue;
        if (known == null) {
            synchronized (this) {
                known = catalogue;
                if (known == null) {
                    known = DungeonRoomConfig.getInstance().catalogue(pools());
                    catalogue = known;
                }
            }
        }
        return known;
    }

    private static long key(int area, int run) {
        return ((long) area << 32) | (run & 0xFFFFFFFFL);
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigDungeonWorldGenProvider{runs=" + runs.size() + "}";
    }
}
