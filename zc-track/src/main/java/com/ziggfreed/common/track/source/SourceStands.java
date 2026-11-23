package com.ziggfreed.common.track.source;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.util.ZigScheduler;
import com.ziggfreed.common.world.BlockOps;
import com.ziggfreed.common.world.placed.PlacedBlockLedger;

/**
 * A source's {@code StandSeconds}: a moment at a block (a placement) waits that long before it pays, and pays only if
 * the block still stands then, the same block in the same cell, still the player's (the placed-block ledger's one
 * answer), and the player is still in that world. Building's guard against placing and breaking one block over and
 * over: a block broken or swapped before its time pays nothing, and its diminishing count is never spent, since the
 * count is taken when it pays ({@link TrackSourceReactions#settle}), so a source keyed by {@code Position} pays each
 * spot once a day whatever is broken in between.
 *
 * <p>The block in the cell is read once the placement has landed (one world task later, since the engine's place
 * event fires before the block is down) and again when the time is up, both through zc-world's {@link BlockOps},
 * which never loads a chunk: a cell no longer in memory pays nothing. The wait runs on the library's scheduler
 * ({@link ZigScheduler}) and hops to the world for each read; nothing is kept past a restart, so a block still
 * waiting when the server stops pays nothing.
 */
public final class SourceStands {

    /** What {@link BlockOps} answers for an empty cell: the engine's own empty key. */
    static final String AIR = "Empty";

    private SourceStands() {
    }

    /**
     * Pay {@code amount} of {@code paid} to {@code player} once the block at {@code spot} in {@code world} has stood
     * {@code paid.standSeconds()}, counted under {@code countKey} then. World thread, inside the moment's reaction.
     */
    static void await(@Nonnull World world, @Nonnull UUID worldId, @Nonnull UUID player,
            @Nonnull TrackSources.Paid paid, double amount, @Nonnull String countKey, boolean batched,
            @Nonnull Vector3i spot) {
        int x = spot.x;
        int y = spot.y;
        int z = spot.z;
        String[] landed = new String[1];
        try {
            world.execute(() -> landed[0] = blockAt(world, x, y, z));
            ZigScheduler.schedule(() -> {
                try {
                    String now = blockAt(world, x, y, z);
                    if (stands(landed[0], now) && placedBy(player, worldId, x, y, z) && standsIn(player, world)) {
                        TrackSourceReactions.settle(world, player, paid, amount, countKey, batched);
                    }
                } catch (Throwable t) {
                    SafeLog.fine("[track] a placement's stand time could not be settled: " + t.getMessage());
                }
            }, paid.standSeconds(), TimeUnit.SECONDS, world);
        } catch (Throwable t) {
            SafeLog.fine("[track] a placement's stand time could not start: " + t.getMessage());
        }
    }

    /**
     * Did the block stand: the cell reads a block now ({@code now}, not air and not unknown), the same one it read once
     * the placement landed ({@code landed}, or unknown then, the ledger alone answering)? Pure.
     */
    static boolean stands(@Nullable String landed, @Nullable String now) {
        if (now == null || now.isBlank() || AIR.equalsIgnoreCase(now)) {
            return false;
        }
        return landed == null || landed.equalsIgnoreCase(now);
    }

    /** The block item in the cell, or null when it cannot be told (a chunk not in memory, a read that threw). */
    @Nullable
    private static String blockAt(@Nonnull World world, int x, int y, int z) {
        try {
            return BlockOps.blockItemIdAt(world.getChunkStore(), x, y, z);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean placedBy(@Nonnull UUID player, @Nonnull UUID worldId, int x, int y, int z) {
        try {
            return PlacedBlockLedger.getInstance().isPlacedBy(player, worldId, x, y, z);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Does {@code player} stand in {@code world} right now? */
    private static boolean standsIn(@Nonnull UUID player, @Nonnull World world) {
        Ref<EntityStore> ref = PlayerAccess.liveRef(player);
        try {
            return ref != null && WorldEvictors.worldOf(ref) == world;
        } catch (Throwable t) {
            return false;
        }
    }

}
