package com.ziggfreed.common.world.gather;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * Stats a mod counts toward a block's gathering speed beyond the library's own (GatherSpeed, the block's families,
 * a placed block, the rhythm chain): a consumer's skill-shaped stat for the blocks its skill pays for, say. A source
 * answers the stat id that counts for one block, or null, from the block id alone, so the answer never depends on
 * who swings; the gather step reads that stat off the breaker and adds it before the ceiling.
 *
 * <p>Registered by owner (matched without regard to case), so re-registering replaces and a mod never adds a
 * second copy of its own source. A source that throws counts nothing and is named once until it registers again.
 * The library registers none: with no source the step reads only its own stats.
 */
public final class GatherSpeedSources {

    /** One mod's answer for a block: the stat id that counts toward its speed, or null for none. */
    @FunctionalInterface
    public interface Source {
        @Nullable
        String speedStatFor(@Nonnull String blockId);
    }

    private static final Map<String, Source> SOURCES = new ConcurrentHashMap<>();

    /** The sources in owner order, rebuilt on every registration so a hit reads it with no sort. */
    @Nonnull
    private static volatile List<Map.Entry<String, Source>> ordered = List.of();

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static final String[] NONE = new String[0];

    private GatherSpeedSources() {
    }

    /** Register (or replace) {@code owner}'s source. */
    public static void register(@Nonnull String owner, @Nonnull Source source) {
        String key = owner.trim().toLowerCase(Locale.ROOT);
        SOURCES.put(key, source);
        WARNED.remove(key);
        reorder();
    }

    /** Remove {@code owner}'s source (a test, or a mod switching its own off). */
    public static void unregister(@Nonnull String owner) {
        SOURCES.remove(owner.trim().toLowerCase(Locale.ROOT));
        reorder();
    }

    private static synchronized void reorder() {
        ordered = List.copyOf(new TreeMap<>(SOURCES).entrySet());
    }

    /** Whether any mod registered a source. */
    public static boolean any() {
        return !ordered.isEmpty();
    }

    /**
     * Every registered source's stat for {@code blockId}, in owner order, nulls left out. Never throws; a throwing
     * source is skipped.
     */
    @Nonnull
    public static String[] statsFor(@Nonnull String blockId) {
        List<Map.Entry<String, Source>> sources = ordered;
        if (sources.isEmpty()) {
            return NONE;
        }
        String[] out = new String[sources.size()];
        int n = 0;
        for (Map.Entry<String, Source> entry : sources) {
            try {
                String stat = entry.getValue().speedStatFor(blockId);
                if (stat != null && !stat.isBlank()) {
                    out[n++] = stat.trim();
                }
            } catch (Throwable t) {
                if (WARNED.add(entry.getKey())) {
                    SafeLog.warn("[gather] the speed source '" + entry.getKey() + "' failed for a block, so it counts"
                            + " nothing until it registers again: " + t.getMessage());
                }
            }
        }
        if (n == out.length) {
            return out;
        }
        String[] trimmed = new String[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** Test seam: forget every source. */
    static void clearForTests() {
        SOURCES.clear();
        WARNED.clear();
        reorder();
    }
}
