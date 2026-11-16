package com.ziggfreed.common.instance.dungeon.gen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.instance.dungeon.AreaLayout;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.RunLanes;
import com.ziggfreed.common.instance.dungeon.RunSeeds;
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.instance.dungeon.layout.Layout;
import com.ziggfreed.common.instance.dungeon.layout.LayoutGenerator;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan;
import com.ziggfreed.common.instance.dungeon.layout.LayoutResult;
import com.ziggfreed.common.instance.dungeon.layout.PlacedRoom;
import com.ziggfreed.common.instance.dungeon.layout.QuarterTurns;
import com.ziggfreed.common.instance.dungeon.layout.RoomCatalogue;
import com.ziggfreed.common.instance.dungeon.layout.RoomSpec;
import com.ziggfreed.common.instance.dungeon.layout.asset.PrefabPoolIndex;
import com.ziggfreed.common.instance.dungeon.room.RoomTurns;
import com.ziggfreed.common.instance.dungeon.room.RunLogic;
import com.ziggfreed.common.instance.world.CopyKey;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.Facing;

/**
 * A generated dungeon's runs, as the rest of the dungeon sees them: {@link #open} lays a party's run out and
 * registers it with its copy before the party is sent (so no chunk of its region generates before its rooms are
 * known), {@link #arrival} is where its players land and respawn, and {@link #finish} is its measurement line once
 * its lane frees.
 *
 * <p><b>A run.</b> The dungeon's layout plan, the copy's room catalogue and the run's seed ({@link RunSeeds}, taken
 * as the party sets off: the day's for a dungeon with {@code Seed.Daily}, else a fresh random one) make a layout
 * ({@code LayoutGenerator}); it is placed in the run's region of its lane ({@link RunLanes#frameOrigin}), each room's
 * variant resolved to its prefab file ({@link PrefabPoolIndex#paths}) and loaded, all on a painter thread, never the
 * world thread. Its players arrive at the entry room's anchor plus the dungeon's {@code Area.EntryOffset}, turned with
 * the room; they face {@code Area.EntryYaw} turned with the room when the file writes it, else the entry room's first
 * door that leads on.
 *
 * <p><b>Its rooms' logic</b> is prepared with it, on the same painter thread ({@code room/RunLogic}), and handed out by
 * {@link #logicOf}: the area members' ticker places a room's as a member nears it and removes it all when the party
 * leaves (the lane frees, after a clear too), and a
 * copy closing forgets it ({@link #copyClosed}).
 */
public final class GeneratedRuns {

    private static final String LOG = "[dungeon] ";
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /** What has been warned about once already. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private GeneratedRuns() {
    }

    /**
     * Lay out run {@code run} of {@code area} in {@code world} from {@code dungeon}'s layout plan and register it with
     * the copy, off the world thread; a member following into a run already registered or under way waits on that one.
     * Completes once the run is registered, exceptionally when the copy does not generate with {@code ZigDungeon}, the
     * plan is not loaded, or no layout could be made; either way it completes on the copy's own world thread, so what
     * waits on it (the entry's ready future, the engine's teleport) never runs on a painter.
     */
    @Nonnull
    public static CompletableFuture<Void> open(@Nonnull World world, @Nonnull CopyKey area, int run,
            @Nonnull AreaLayout layout, @Nonnull DungeonAsset dungeon) {
        ZigDungeonWorldGenProvider copy = ZigDungeonWorldGenProvider.of(world);
        if (copy == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("the copy " + world.getName()
                    + " does not generate with " + ZigDungeonWorldGenProvider.TYPE + ", so its rooms cannot be painted"));
        }
        String worldName = world.getName();
        // Taken now, as the party sets off: a daily run started a moment before midnight keeps that day's layout.
        long seed = RunSeeds.seedFor(dungeon, System.currentTimeMillis());
        return copy.open(area.area(), run, () -> {
            CompletableFuture<DungeonRun> made = new CompletableFuture<>();
            DungeonPainters.run(() -> {
                try {
                    made.complete(build(copy, worldName, area.area(), run, layout, dungeon, seed));
                } catch (Throwable t) {
                    made.completeExceptionally(t);
                }
            });
            return made;
        }).handleAsync((registered, error) -> {
            if (error != null) {
                throw error instanceof CompletionException wrapped ? wrapped : new CompletionException(error);
            }
            return null;
        }, onWorld(world));
    }

    /**
     * {@code world}'s own executor, except that a task the world turns away (it refuses tasks once it is shutting down)
     * runs on the calling thread instead, so the stage still completes and nothing waiting on it is stranded.
     */
    @Nonnull
    private static Executor onWorld(@Nonnull World world) {
        return task -> {
            try {
                world.execute(task);
            } catch (Throwable refused) {
                task.run();
            }
        };
    }

    /**
     * The seed lane {@code area}'s latest run in {@code world} was laid out from, or null when the copy generates nothing
     * or the lane has no run registered. A map read, safe from any thread: the run's start reads its omens' seed here.
     */
    @Nullable
    public static Long seedOf(@Nullable World world, int area) {
        ZigDungeonWorldGenProvider copy = ZigDungeonWorldGenProvider.of(world);
        DungeonRun run = copy == null ? null : copy.current(area);
        return run == null ? null : run.seed;
    }

    /** Where a player of lane {@code area}'s run in {@code world} lands; null when the lane has no run registered. */
    @Nullable
    public static Transform arrival(@Nullable World world, int area) {
        ZigDungeonWorldGenProvider copy = ZigDungeonWorldGenProvider.of(world);
        DungeonRun run = copy == null ? null : copy.current(area);
        return run == null ? null : new Transform(run.arrival);
    }

    /**
     * A run's entry room as a pasted area's prefab spot reads: the block its prefab anchor stands on, and its quarter
     * turns ({@code QuarterTurns}, the engine's {@code PrefabRotation} index).
     */
    public record Entry(@Nonnull Cell anchor, int turns) {
    }

    /** Lane {@code area}'s run's entry room in {@code world}; null when the lane has no run registered. */
    @Nullable
    public static Entry entry(@Nullable World world, int area) {
        ZigDungeonWorldGenProvider copy = ZigDungeonWorldGenProvider.of(world);
        DungeonRun run = copy == null ? null : copy.current(area);
        if (run == null) {
            return null;
        }
        PlacedRoom room = run.layout.room(0);
        return new Entry(run.frame.plus(room.origin()), QuarterTurns.normalize(room.rotation()));
    }

    /**
     * Lane {@code area}'s run in {@code world} is over (its lane freed): its one measurement line, or null when the
     * copy generates nothing, the lane has no run, or its line was already given.
     */
    @Nullable
    public static String finish(@Nullable World world, @Nonnull CopyKey area) {
        ZigDungeonWorldGenProvider copy = ZigDungeonWorldGenProvider.of(world);
        DungeonRun run = copy == null ? null : copy.current(area.area());
        return run == null || world == null ? null : run.reportOnce(world.getName());
    }

    /**
     * The room logic of lane {@code area}'s latest run in {@code world} ({@link RunLogic}: where a member stands, what
     * is placed as they near it, what the party's leaving removes); null when the copy generates nothing or the lane has no
     * run registered. A map read, safe from any thread.
     */
    @Nullable
    public static RunLogic logicOf(@Nullable World world, int area) {
        ZigDungeonWorldGenProvider copy = ZigDungeonWorldGenProvider.of(world);
        DungeonRun run = copy == null ? null : copy.current(area);
        return run == null ? null : run.logic;
    }

    /**
     * A copy closed ({@code WorldEvictors}): every run's room logic is forgotten, nothing more placed, since its
     * volumes, points and entities went with the world. Never throws.
     */
    public static void copyClosed(@Nonnull World world) {
        try {
            ZigDungeonWorldGenProvider copy = ZigDungeonWorldGenProvider.of(world);
            if (copy != null) {
                for (DungeonRun run : copy.runs()) {
                    run.logic.forget();
                }
            }
        } catch (Throwable t) {
            SafeLog.fine(LOG + "a closing copy's room logic could not be forgotten: " + t.getMessage());
        }
    }

    // ==================== building a run ====================

    @Nonnull
    private static DungeonRun build(@Nonnull ZigDungeonWorldGenProvider copy, @Nonnull String worldName, int area,
            int run, @Nonnull AreaLayout lanes, @Nonnull DungeonAsset dungeon, long seed) {
        String planId = dungeon.layoutId();
        LayoutPlan plan = dungeon.layoutPlan();
        if (plan == null) {
            throw new IllegalStateException("the dungeon " + dungeon.getId() + " names the layout plan " + planId
                    + ", which no loaded file defines under Server/ZiggfreedCommon/DungeonLayouts/");
        }
        RoomCatalogue catalogue = copy.catalogue();
        long start = System.nanoTime();
        LayoutResult result = LayoutGenerator.generate(plan, catalogue, seed);
        long generated = System.nanoTime();
        Layout layout = result.layout();
        if (layout == null) {
            throw new IllegalStateException("the layout plan " + plan.id() + " made no layout from seed " + seed
                    + " in " + result.tries() + " tries (relaxed to " + result.relax() + "): " + result.failure());
        }
        Cell bounds = plan.bounds();
        if (bounds.x() + RunLanes.VIEW_MARGIN > lanes.spacing() || bounds.z() + RunLanes.VIEW_MARGIN > lanes.runStride()) {
            warnOnce("bounds:" + plan.id() + ":" + lanes.spacing() + ":" + lanes.runStride(), LOG + "the layout plan "
                    + plan.id() + " is " + bounds.x() + " by " + bounds.z() + " blocks now, wider than the lanes of a copy"
                    + " opened before it changed (" + lanes.spacing() + " apart, runs " + lanes.runStride() + " apart);"
                    + " its runs there may see each other until that copy closes");
        }
        Cell frame = RunLanes.frameOrigin(area, run, lanes.spacing(), lanes.runStride(), bounds);
        PrefabPoolIndex pools = copy.pools();
        List<DungeonRun.Room> rooms = new ArrayList<>(layout.rooms().size());
        for (PlacedRoom placed : layout.rooms()) {
            rooms.add(room(placed, catalogue, pools, frame));
        }
        Transform arrival = arrivalOf(layout, frame, dungeon);
        RunLogic logic = prepareLogic(worldName, area, run, seed, layout, rooms, catalogue);
        long prepared = System.nanoTime();
        return new DungeonRun(area, run, seed, plan.id(), layout, frame, rooms, arrival,
                (generated - start) / NANOS_PER_MILLI, (prepared - generated) / NANOS_PER_MILLI, logic);
    }

    /**
     * The run's room logic, prepared from its painted rooms: each room's anchor, turn, box and prefabs, whether its
     * room file keeps its markers, and the rooms its doors join. A run whose logic cannot be prepared runs without it
     * (warned), so the party still gets its rooms.
     */
    @Nonnull
    private static RunLogic prepareLogic(@Nonnull String worldName, int area, int run, long seed,
            @Nonnull Layout layout, @Nonnull List<DungeonRun.Room> rooms, @Nonnull RoomCatalogue catalogue) {
        try {
            List<RunLogic.Room> logicRooms = new ArrayList<>(rooms.size());
            for (int i = 0; i < rooms.size(); i++) {
                DungeonRun.Room room = rooms.get(i);
                PlacedRoom placed = layout.rooms().get(i);
                RoomSpec spec = catalogue.room(placed.roomId());
                List<Integer> neighbours = new ArrayList<>();
                for (PlacedRoom.DoorLink door : placed.doors()) {
                    if (door.room() >= 0 && !neighbours.contains(door.room())) {
                        neighbours.add(door.room());
                    }
                }
                logicRooms.add(new RunLogic.Room(room.index(), room.origin(), room.rotation().ordinal(), room.box(),
                        room.base(), room.overlay(), spec != null && spec.keepMarkers(), neighbours,
                        room.roomId() + " (" + room.prefabKey() + ")"));
            }
            return RunLogic.prepare(worldName, area, run, seed, logicRooms);
        } catch (Throwable t) {
            if (WARNED.add(("logic:" + layout.planId()).toLowerCase(Locale.ROOT))) {
                SafeLog.warn(LOG + "the room logic of a run of the layout plan " + layout.planId() + " could not be "
                        + "prepared; its rooms are placed without their logic", t);
            }
            return RunLogic.none(area, run);
        }
    }

    /** One placed room as the painter reads it: in world blocks, its variant's prefab loaded. */
    @Nonnull
    private static DungeonRun.Room room(@Nonnull PlacedRoom placed, @Nonnull RoomCatalogue catalogue,
            @Nonnull PrefabPoolIndex pools, @Nonnull Cell frame) {
        RoomSpec spec = catalogue.room(placed.roomId());
        if (spec == null) {
            throw new IllegalStateException("the layout placed the room " + placed.roomId()
                    + ", which the catalogue no longer holds");
        }
        List<String> keys = pools.keys(spec.prefab());
        List<Path> paths = pools.paths(spec.prefab());
        int variant = placed.variant();
        if (variant < 0 || variant >= paths.size()) {
            throw new IllegalStateException("the room " + placed.roomId() + " was placed as variant " + variant
                    + " of " + spec.prefab() + ", which ships " + paths.size());
        }
        IPrefabBuffer base = PrefabBufferUtil.getCached(paths.get(variant));
        PrefabRotation rotation = PrefabRotation.VALUES[QuarterTurns.normalize(placed.rotation())];
        return new DungeonRun.Room(placed.index(), placed.roomId(), keys.get(variant), frame.plus(placed.origin()),
                rotation, placed.box().shifted(frame), base, overlayOf(spec, pools),
                fillOf(placed.fill(), placed.roomId()), spec.keepContainers());
    }

    /** The room's overlay prefab, loaded, when it names one ({@link RoomOverlays}); null for none or none shipped. */
    @Nullable
    private static IPrefabBuffer overlayOf(@Nonnull RoomSpec spec, @Nonnull PrefabPoolIndex pools) {
        String overlay = RoomOverlays.overlayOf(spec);
        if (overlay == null) {
            return null;
        }
        List<Path> paths = pools.paths(overlay);
        if (paths.isEmpty()) {
            warnOnce("overlay:" + overlay, LOG + "the room " + spec.id() + " names the overlay " + overlay
                    + ", which no loaded pack ships under Server/Prefabs/; the room is painted without it");
            return null;
        }
        return PrefabBufferUtil.getCached(paths.get(0));
    }

    /** The block id a room's fill names, or -1 for none (or a name no loaded block answers to, said once). */
    private static int fillOf(@Nullable String fill, @Nonnull String roomId) {
        if (fill == null) {
            return -1;
        }
        int id = BlockType.getAssetMap().getIndex(fill);
        if (id == AssetMapWithIndexes.NOT_FOUND) {
            warnOnce("fill:" + fill, LOG + "the room " + roomId + " fills its unlisted cells with " + fill
                    + ", which no loaded block answers to; they are left empty");
            return -1;
        }
        return id;
    }

    /**
     * Where the run's players land: the entry room's anchor plus the dungeon's {@code Area.EntryOffset}, both turned
     * with the room, at the anchor block's centre; facing {@code Area.EntryYaw} turned with the room when the file
     * writes it, else the entry room's first door that leads on (a room turned with no such door faces its own north).
     */
    @Nonnull
    static Transform arrivalOf(@Nonnull Layout layout, @Nonnull Cell frame, @Nonnull DungeonAsset dungeon) {
        PlacedRoom entry = layout.room(0);
        Cell anchor = frame.plus(entry.origin());
        int turns = QuarterTurns.normalize(entry.rotation());
        Vec3 offset = dungeon.entryOffset();
        double dx = RoomTurns.x(offset.effectiveX(), offset.effectiveZ(), turns);
        double dz = RoomTurns.z(offset.effectiveX(), offset.effectiveZ(), turns);
        float yaw = dungeon.entryYawAuthored() ? dungeon.entryYaw() + 90f * turns : doorYaw(entry, turns);
        return new Transform(new Vector3d(anchor.x() + 0.5 + dx, anchor.y() + offset.effectiveY(),
                anchor.z() + 0.5 + dz), Facing.rotation(normalizeYaw(yaw)));
    }

    /** The yaw facing the entry room's first door that joins another room; its own north turned, without one. */
    private static float doorYaw(@Nonnull PlacedRoom entry, int turns) {
        for (PlacedRoom.DoorLink door : entry.doors()) {
            var facing = door.facing();
            if (facing != null && door.room() >= 0 && !door.capped()) {
                // Yaw 0 looks toward -Z and 90 toward -X: the look direction is (-sin yaw, -cos yaw).
                return (float) Math.toDegrees(Math.atan2(-facing.dx(), -facing.dz()));
            }
        }
        return 90f * turns;
    }

    private static float normalizeYaw(float yaw) {
        float folded = yaw % 360f;
        return folded < 0 ? folded + 360f : folded;
    }

    private static void warnOnce(@Nonnull String key, @Nonnull String line) {
        if (WARNED.add(key.toLowerCase(Locale.ROOT))) {
            SafeLog.warn(line);
        }
    }
}
