package com.ziggfreed.common.world.gather;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.OwnedSources;

/**
 * Stats a mod counts toward a gathering number beyond the library's own: a consumer's skill-shaped stat for the
 * blocks and harvests its skill pays for, say ({@code GatherSpeed_Mining} on ore, {@code GatherYield_Harvesting} on a
 * crop). A source is asked for one line ({@link #SPEED} or {@link #YIELD}, the stat id's base), the moment
 * ({@link On}) and the block or item id alone, so the answer never depends on who gathers; the step reading the line
 * reads that stat off the player and adds it in, before the line's ceiling where it has one.
 *
 * <p>Registered by owner (zc-core's {@link OwnedSources}: matched without regard to case, re-registering replaces,
 * asked in owner id order, a source that throws counts nothing and is named once until it registers again). The
 * library registers none: with no source a step reads only its own stats.
 */
public final class GatherStatSources {

    /** The speed line's base, {@code GatherSpeed}, which {@link GatherSpeedSystem} reads on a hit. */
    public static final String SPEED = GatherSpeedSystem.SPEED_STAT;

    /** The yield line's base, {@code GatherYield}, which the library's bonus finds read ({@link GatherYields}). */
    public static final String YIELD = GatherYields.YIELD_STAT;

    /** The moment a line is asked at. */
    public enum On {
        /** A player broke (or is breaking) a block; the id is the block's. */
        BREAK,
        /** A player harvested something by hand; the id is the item harvested. */
        HARVEST
    }

    /** One mod's answer for a line at a moment: the stat id that counts, or null for none. */
    @FunctionalInterface
    public interface Source {
        @Nullable
        String statFor(@Nonnull String line, @Nonnull On on, @Nonnull String id);
    }

    private static final OwnedSources<Source> SOURCES = new OwnedSources<>();

    private static final String[] NONE = new String[0];

    private GatherStatSources() {
    }

    /** Register (or replace) {@code owner}'s source. */
    public static void register(@Nonnull String owner, @Nonnull Source source) {
        SOURCES.register(owner.trim(), source);
    }

    /** Remove {@code owner}'s source (a test, or a mod switching its own off). */
    public static void unregister(@Nonnull String owner) {
        SOURCES.unregister(owner.trim());
    }

    /** Whether any mod registered a source. */
    public static boolean any() {
        return !SOURCES.isEmpty();
    }

    /**
     * Every registered source's stat for {@code line} at {@code on} for {@code id}, in owner order, blanks left out.
     * Never throws; a throwing source is skipped.
     */
    @Nonnull
    public static String[] statsFor(@Nonnull String line, @Nonnull On on, @Nonnull String id) {
        if (SOURCES.isEmpty()) {
            return NONE;
        }
        List<String> stats = SOURCES.collect(source -> {
            String stat = source.statFor(line, on, id);
            return stat == null || stat.isBlank() ? null : List.of(stat.trim());
        }, owner -> "[gather] the stat source of '" + owner + "'");
        return stats.isEmpty() ? NONE : stats.toArray(NONE);
    }
}
