package com.ziggfreed.common.instance.dungeon.gen;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.ziggfreed.common.instance.dungeon.layout.Box;
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.instance.dungeon.layout.Layout;

/**
 * One registered run of a generated dungeon copy: the layout its seed made, placed in the run's region of its lane,
 * every room ready to paint, where its players arrive, and what painting it has cost so far. Immutable once
 * registered, but for its counters, which the painters add to from any thread.
 */
final class DungeonRun {

    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /**
     * One room as the painter reads it, in world blocks.
     *
     * @param index          its index in the layout
     * @param roomId         its room file's id, folded
     * @param prefabKey      the variant it pastes, in dot form
     * @param origin         where its prefab anchor stands
     * @param rotation       its turn, the engine's own
     * @param box            the blocks it may write: its room file's {@code Box}, turned and placed
     * @param base           the variant's prefab
     * @param overlay        the overlay painted on top of the base, or null for none
     * @param fillBlock      the block id its unlisted cells are filled with, or -1 for none
     * @param keepContainers whether its prefab's containers are kept (else painted as air)
     */
    record Room(int index, @Nonnull String roomId, @Nonnull String prefabKey, @Nonnull Cell origin,
                @Nonnull PrefabRotation rotation, @Nonnull Box box, @Nonnull IPrefabBuffer base,
                @Nullable IPrefabBuffer overlay, int fillBlock, boolean keepContainers) {
    }

    final int area;
    final int run;
    final long seed;
    @Nonnull final String planId;
    @Nonnull final Layout layout;
    /** Where the layout's frame starts in the world. */
    @Nonnull final Cell frame;
    /** The blocks every room together covers, for a quick no. */
    @Nonnull final Box reach;
    @Nonnull final List<Room> rooms;
    @Nonnull final Transform arrival;
    final double generationMs;
    final double prepareMs;

    final AtomicInteger chunksPainted = new AtomicInteger();
    final AtomicInteger chunksOnCaller = new AtomicInteger();
    final AtomicLong paintNanos = new AtomicLong();
    final AtomicLong childrenExpanded = new AtomicLong();
    final AtomicLong entitiesLeft = new AtomicLong();
    private final AtomicBoolean reported = new AtomicBoolean();

    DungeonRun(int area, int run, long seed, @Nonnull String planId, @Nonnull Layout layout, @Nonnull Cell frame,
            @Nonnull List<Room> rooms, @Nonnull Transform arrival, double generationMs, double prepareMs) {
        this.area = area;
        this.run = run;
        this.seed = seed;
        this.planId = planId;
        this.layout = layout;
        this.frame = frame;
        this.rooms = List.copyOf(rooms);
        this.arrival = arrival;
        this.generationMs = generationMs;
        this.prepareMs = prepareMs;
        this.reach = reachOf(this.rooms, frame);
    }

    /** Does any room of the run reach into the chunk column starting at block {@code (baseX, baseZ)}? */
    boolean touches(int baseX, int baseZ, int size) {
        return reach.max().x() >= baseX && reach.min().x() < baseX + size
                && reach.max().z() >= baseZ && reach.min().z() < baseZ + size;
    }

    /** Count one chunk this run painted rooms into, and what it took. */
    void painted(long nanos, boolean onCaller) {
        chunksPainted.incrementAndGet();
        paintNanos.addAndGet(nanos);
        if (onCaller) {
            chunksOnCaller.incrementAndGet();
        }
    }

    /**
     * The run's one measurement line, the first time it is asked for (its lane freed); null after. Generation is the
     * layout alone, preparing is placing it and loading its prefabs, both off the world thread; painting is every
     * chunk of the region written so far.
     */
    @Nullable
    String reportOnce(@Nonnull String world) {
        if (!reported.compareAndSet(false, true)) {
            return null;
        }
        int chunks = chunksPainted.get();
        double paintMs = paintNanos.get() / NANOS_PER_MILLI;
        int onCaller = chunksOnCaller.get();
        return String.format(Locale.ROOT, "[dungeon] run %d of %s area %d (plan %s, seed %d): %d rooms (%d on the path)"
                        + " generated in %.2f ms and prepared in %.2f ms; %d chunks painted in %.2f ms (%.2f ms a chunk),"
                        + " %s; %d spawner children expanded, %d prefab entities left to the room logic",
                run, world, area, planId, seed, layout.rooms().size(), layout.pathLength(), generationMs, prepareMs,
                chunks, paintMs, chunks == 0 ? 0.0 : paintMs / chunks,
                onCaller == 0 ? "all on the painter threads"
                        : onCaller + " of them on the asking thread while the painters were down",
                childrenExpanded.get(), entitiesLeft.get());
    }

    @Nonnull
    private static Box reachOf(@Nonnull List<Room> rooms, @Nonnull Cell frame) {
        if (rooms.isEmpty()) {
            return new Box(frame, frame);
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Room room : rooms) {
            minX = Math.min(minX, room.box().min().x());
            minY = Math.min(minY, room.box().min().y());
            minZ = Math.min(minZ, room.box().min().z());
            maxX = Math.max(maxX, room.box().max().x());
            maxY = Math.max(maxY, room.box().max().y());
            maxZ = Math.max(maxZ, room.box().max().z());
        }
        return new Box(new Cell(minX, minY, minZ), new Cell(maxX, maxY, maxZ));
    }
}
