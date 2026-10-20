package com.ziggfreed.common.world.gather;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Each player's last few placements, for the rules that read where and when they built: a shaped placement (a block
 * continuing a straight run of three, or placed within {@link #FLOW_MS} of one), which {@code BuildThrift_Shaped}
 * reads, and "a block this player placed within N seconds", which a gather shape's {@code Origin.PlacedWithin} reads.
 * In memory only and per player, the newest {@link #HELD} placements kept; thread-safe, the clock passed in.
 */
public final class RecentPlacements {

    /** How long after a shaped placement every placement still counts as shaped. */
    public static final long FLOW_MS = 10_000L;

    /** How many placements a player's memory holds. */
    public static final int HELD = 64;

    /** Past this many players held, memories untouched for a minute are swept. */
    private static final int SWEEP_AT = 512;

    private static final long SWEEP_IDLE_MS = 60_000L;

    private static final Map<UUID, Memory> MEMORIES = new ConcurrentHashMap<>();

    private RecentPlacements() {
    }

    /** One player's ring of placements and the last time one was shaped. */
    static final class Memory {
        final UUID[] worlds = new UUID[HELD];
        final int[] xs = new int[HELD];
        final int[] ys = new int[HELD];
        final int[] zs = new int[HELD];
        final long[] at = new long[HELD];
        int next;
        int count;
        long lastShapedAt = Long.MIN_VALUE;

        /** Record a placement; true when it is shaped. */
        synchronized boolean place(@Nonnull UUID world, int x, int y, int z, long nowMs) {
            boolean straight = false;
            if (count >= 2) {
                int last = (next - 1 + HELD) % HELD;
                int before = (next - 2 + HELD) % HELD;
                straight = world.equals(worlds[last]) && world.equals(worlds[before])
                        && continuesLine(xs[before], ys[before], zs[before], xs[last], ys[last], zs[last], x, y, z);
            }
            worlds[next] = world;
            xs[next] = x;
            ys[next] = y;
            zs[next] = z;
            at[next] = nowMs;
            next = (next + 1) % HELD;
            count = Math.min(HELD, count + 1);
            boolean flowing = lastShapedAt != Long.MIN_VALUE && nowMs - lastShapedAt <= FLOW_MS;
            if (straight) {
                lastShapedAt = nowMs;
            }
            return straight || flowing;
        }

        /** When this player last placed at the position, or {@link Long#MIN_VALUE} for not lately. */
        synchronized long placedAt(@Nonnull UUID world, int x, int y, int z) {
            for (int i = 0; i < count; i++) {
                int slot = (next - 1 - i + HELD) % HELD;
                if (xs[slot] == x && ys[slot] == y && zs[slot] == z && world.equals(worlds[slot])) {
                    return at[slot];
                }
            }
            return Long.MIN_VALUE;
        }

        synchronized long newest() {
            return count == 0 ? Long.MIN_VALUE : at[(next - 1 + HELD) % HELD];
        }
    }

    /**
     * {@code playerId} placed a block at the position at {@code nowMs}; answers whether that placement is shaped (it
     * continues a straight run of three, or comes within {@link #FLOW_MS} of one that did).
     */
    public static boolean place(@Nullable UUID playerId, @Nullable UUID worldId, int x, int y, int z, long nowMs) {
        if (playerId == null || worldId == null) {
            return false;
        }
        boolean shaped = MEMORIES.computeIfAbsent(playerId, id -> new Memory()).place(worldId, x, y, z, nowMs);
        if (MEMORIES.size() > SWEEP_AT) {
            MEMORIES.values().removeIf(memory -> nowMs - memory.newest() > SWEEP_IDLE_MS);
        }
        return shaped;
    }

    /** Did {@code playerId} place the block at the position within the last {@code windowMs}? */
    public static boolean placedWithin(@Nullable UUID playerId, @Nullable UUID worldId, int x, int y, int z,
            long nowMs, long windowMs) {
        long at = placedAt(playerId, worldId, x, y, z);
        return windowMs > 0 && at != Long.MIN_VALUE && nowMs - at <= windowMs;
    }

    /**
     * When {@code playerId} last placed a block at the position, in their recent memory, or {@link Long#MIN_VALUE}
     * for not lately.
     */
    public static long placedAt(@Nullable UUID playerId, @Nullable UUID worldId, int x, int y, int z) {
        Memory memory = playerId == null || worldId == null ? null : MEMORIES.get(playerId);
        return memory == null ? Long.MIN_VALUE : memory.placedAt(worldId, x, y, z);
    }

    /** Forget a player's placements (they left). */
    public static void clear(@Nullable UUID playerId) {
        if (playerId != null) {
            MEMORIES.remove(playerId);
        }
    }

    /**
     * Does {@code (x, y, z)} continue the line {@code a} then {@code b} walks: the same one-block step along one
     * axis, twice? Pure.
     */
    static boolean continuesLine(int ax, int ay, int az, int bx, int by, int bz, int x, int y, int z) {
        int dx = bx - ax;
        int dy = by - ay;
        int dz = bz - az;
        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) {
            return false;
        }
        return x - bx == dx && y - by == dy && z - bz == dz;
    }
}
