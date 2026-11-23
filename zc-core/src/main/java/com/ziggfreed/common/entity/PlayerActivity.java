package com.ziggfreed.common.entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * When each online player last moved, turned their view or used something: the one answer to "is this player idle?"
 * every module asks (a track's idle factor, a fight that pays nothing for an idle victim). The engine keeps no such
 * time (no last input on the player, the connection's idle timeout is transport liveness only), so a watch fills it:
 * zc-track's activity systems hand each player's position and view in every tick ({@link #observe}) and say when a
 * player starts an interaction themselves or collects or uses a reward ({@link #acted}).
 *
 * <p>A player the watch has not seen yet is never idle ({@link #idleMillis} answers 0), so a module asking before
 * the watch runs, or on a server whose watch is not wired, pays as if every player were active. Kept in memory only:
 * a disconnect forgets the player ({@link #forget}), and a restart starts everyone active.
 */
public final class PlayerActivity {

    /** How far a player must move, in blocks on any axis, to count as moving. */
    static final double MOVE_EPSILON = 0.05;

    /** How far a view must turn, in the engine's rotation units, to count as looking around. */
    static final double LOOK_EPSILON = 0.01;

    /** One player's last seen place and view, and when they were last active. */
    private static final class Seen {
        double x;
        double y;
        double z;
        double yaw;
        double pitch;
        long activeMs;
    }

    private static final Map<UUID, Seen> SEEN = new ConcurrentHashMap<>();

    private PlayerActivity() {
    }

    /**
     * The watch saw {@code player} at a place and a view at {@code nowMs}: a first sight, a move past
     * {@link #MOVE_EPSILON} or a turn past {@link #LOOK_EPSILON} counts as active. Any thread; the watch calls it from
     * the player's world thread.
     */
    public static void observe(@Nonnull UUID player, double x, double y, double z, double yaw, double pitch,
            long nowMs) {
        Seen seen = SEEN.get(player);
        if (seen == null) {
            seen = new Seen();
            seen.x = x;
            seen.y = y;
            seen.z = z;
            seen.yaw = yaw;
            seen.pitch = pitch;
            seen.activeMs = nowMs;
            SEEN.put(player, seen);
            return;
        }
        synchronized (seen) {
            if (changed(seen.x, seen.y, seen.z, seen.yaw, seen.pitch, x, y, z, yaw, pitch)) {
                seen.activeMs = Math.max(seen.activeMs, nowMs);
            }
            seen.x = x;
            seen.y = y;
            seen.z = z;
            seen.yaw = yaw;
            seen.pitch = pitch;
        }
    }

    /** {@code player} used something (started an interaction) at {@code nowMs}: active. */
    public static void acted(@Nonnull UUID player, long nowMs) {
        Seen seen = SEEN.get(player);
        if (seen == null) {
            return; // The watch's first sight counts as active anyway.
        }
        synchronized (seen) {
            seen.activeMs = Math.max(seen.activeMs, nowMs);
        }
    }

    /**
     * How long {@code player} has been idle at {@code nowMs}, in milliseconds: 0 for a player active since, or one the
     * watch has not seen.
     */
    public static long idleMillis(@Nullable UUID player, long nowMs) {
        Seen seen = player == null ? null : SEEN.get(player);
        if (seen == null) {
            return 0L;
        }
        synchronized (seen) {
            return Math.max(0L, nowMs - seen.activeMs);
        }
    }

    /** Has {@code player} been idle at least {@code thresholdMs} at {@code nowMs}? Never, for a threshold of 0 or less. */
    public static boolean isIdle(@Nullable UUID player, long thresholdMs, long nowMs) {
        return thresholdMs > 0L && idleMillis(player, nowMs) >= thresholdMs;
    }

    /** Forget {@code player} (a disconnect): their next sight counts as active. */
    public static void forget(@Nullable UUID player) {
        if (player != null) {
            SEEN.remove(player);
        }
    }

    /** Did a player move or turn between two sights? Pure. */
    static boolean changed(double x0, double y0, double z0, double yaw0, double pitch0, double x1, double y1,
            double z1, double yaw1, double pitch1) {
        return Math.abs(x1 - x0) > MOVE_EPSILON || Math.abs(y1 - y0) > MOVE_EPSILON
                || Math.abs(z1 - z0) > MOVE_EPSILON || Math.abs(yaw1 - yaw0) > LOOK_EPSILON
                || Math.abs(pitch1 - pitch0) > LOOK_EPSILON;
    }
}
