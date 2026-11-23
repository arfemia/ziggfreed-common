package com.ziggfreed.common.world.gather;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.util.SafeLog;

/**
 * Each player's last few placements, for the rules that read where and when they built: a shaped placement (a block
 * continuing a straight run of three, or placed within the gather rules' flow ({@link GatherRulesAsset#shapedFlowMs})
 * of one), which {@code BuildThrift_Shaped} reads on either half, {@code BuildThrift_Straight} on the run alone and
 * {@code BuildThrift_Flow} in the flow alone ({@link Shape}), and "a block this player placed within N seconds", which
 * a gather shape's {@code Origin.PlacedWithin} reads. In memory only and per player, the newest {@link #HELD}
 * placements kept, forgotten when the player leaves; thread-safe, the clock passed in.
 */
public final class RecentPlacements {

    /**
     * What one placement was to the shaped rules: it continued a straight run of three ({@code straight}), it came
     * within the flow after a placement that did ({@code flowing}), both, or neither. The flow is counted from the
     * last straight placement before this one, so the placement that starts a run is straight and not yet flowing.
     */
    public record Shape(boolean straight, boolean flowing) {

        /** A placement neither half holds for. */
        public static final Shape NONE = new Shape(false, false);

        /** Either half: a shaped placement, as {@code BuildThrift_Shaped} reads it. */
        public boolean shaped() {
            return straight || flowing;
        }
    }

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

        /**
         * Record a placement and say what it was: straight when it continues a straight run of three, flowing when it
         * comes within {@code flowMs} of the last placement that did. Pure over this memory and its arguments.
         */
        @Nonnull
        synchronized Shape place(@Nonnull UUID world, int x, int y, int z, long nowMs, long flowMs) {
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
            boolean flowing = lastShapedAt != Long.MIN_VALUE && nowMs - lastShapedAt <= flowMs;
            if (straight) {
                lastShapedAt = nowMs;
            }
            return straight || flowing ? new Shape(straight, flowing) : Shape.NONE;
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
     * continues a straight run of three, or comes within the gather rules' flow of one that did).
     */
    public static boolean place(@Nullable UUID playerId, @Nullable UUID worldId, int x, int y, int z, long nowMs) {
        return placement(playerId, worldId, x, y, z, nowMs).shaped();
    }

    /**
     * {@code playerId} placed a block at the position at {@code nowMs}; answers which half of the shaped rules it
     * meets ({@link Shape}): it continues a straight run of three, it comes within the gather rules' flow of one that
     * did, both, or {@link Shape#NONE}. Records the placement exactly as {@link #place} does; call one of the two.
     */
    @Nonnull
    public static Shape placement(@Nullable UUID playerId, @Nullable UUID worldId, int x, int y, int z, long nowMs) {
        if (playerId == null || worldId == null) {
            return Shape.NONE;
        }
        long flowMs = GatherRulesConfig.getInstance().rules().shapedFlowMs();
        Shape shape = MEMORIES.computeIfAbsent(playerId, id -> new Memory()).place(worldId, x, y, z, nowMs, flowMs);
        if (MEMORIES.size() > SWEEP_AT) {
            MEMORIES.values().removeIf(memory -> nowMs - memory.newest() > SWEEP_IDLE_MS);
        }
        return shape;
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

    /** A player left the server: their placements go. */
    public static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            if (player != null) {
                clear(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.fine("[gather] a departing player's placements could not be forgotten: " + t.getMessage());
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
