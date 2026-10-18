package com.ziggfreed.common.calendar.moon;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

/**
 * Each world's moon as the world last said it ({@link MoonPhaseSystems}): written on that world's thread when its
 * store comes up and on every {@code MoonPhaseChangeEvent}, read by every gate from any thread. The readings sit in
 * a concurrent map, so a read is a volatile read of the last write and never touches the world itself. A world
 * whose game time is paused fires no change, so it keeps the phase it had; a world that goes is forgotten.
 *
 * <p>A blank world is the server's default world ({@code Universe.getDefaultWorld}), looked up at the moment of
 * asking, so an owner who changes it is followed.
 */
public final class MoonPhases implements MoonSource {

    private static final MoonPhases INSTANCE = new MoonPhases();

    /** The last reading of each world, by lower-cased name (the engine keys its worlds the same way). */
    private final Map<String, Reading> byWorld = new ConcurrentHashMap<>();

    private MoonPhases() {
    }

    @Nonnull
    public static MoonPhases getInstance() {
        return INSTANCE;
    }

    /**
     * {@code world} is in {@code phase} of a cycle of {@code totalPhases}; true when that is news (the first reading,
     * or a phase or count other than the last). On the world's own thread.
     */
    public boolean record(@Nonnull String world, int phase, int totalPhases) {
        Reading next = new Reading(world, phase, Math.max(1, totalPhases));
        Reading before = byWorld.put(key(world), next);
        return before == null || before.phase() != next.phase() || before.totalPhases() != next.totalPhases();
    }

    /** {@code world}'s store went: it has no moon until it comes back. */
    public void forget(@Nonnull String world) {
        byWorld.remove(key(world));
    }

    @Override
    @Nullable
    public Reading reading(@Nullable String world) {
        String name = resolve(world);
        return name == null ? null : byWorld.get(key(name));
    }

    @Override
    public boolean paused(@Nullable String world) {
        try {
            String name = resolve(world);
            Universe universe = name == null ? null : Universe.get();
            World loaded = universe == null ? null : universe.getWorld(name);
            return loaded != null && loaded.getWorldConfig().isGameTimePaused();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    @Nullable
    public String name(@Nullable String world) {
        return resolve(world);
    }

    /** {@code world} trimmed, or the default world's name when it is null or blank; null when there is none. */
    @Nullable
    public static String resolve(@Nullable String world) {
        if (world != null && !world.isBlank()) {
            return world.trim();
        }
        try {
            Universe universe = Universe.get();
            World fallback = universe == null ? null : universe.getDefaultWorld();
            return fallback == null ? null : fallback.getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Forget every world; for a test starting from nothing. */
    public void clearForTests() {
        byWorld.clear();
    }

    @Nonnull
    private static String key(@Nonnull String world) {
        return world.trim().toLowerCase(Locale.ROOT);
    }
}
