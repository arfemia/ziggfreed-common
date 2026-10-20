package com.ziggfreed.common.objectives.producer;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.ziggfreed.common.util.SafeLog;

/**
 * The hits a consumer keeps out of {@code DEAL_DAMAGE}. By default every creature hit a player is credited with counts
 * ({@link ZigDamageProducer}); a consumer whose own combat leaves some targets out (a training dummy, a passive
 * animal, a projectile) registers a filter here, and a hit any filter excludes is produced for nobody: no listener, no
 * quest, no achievement. Asked once per hit, before the moment exists.
 *
 * <p>One filter per owner, a second registration replacing the first. A filter that throws keeps no hit out and is
 * reported once.
 */
public final class DamageHitFilters {

    /** Says whether one hit stays out of {@code DEAL_DAMAGE}. World thread, inside the damage dispatch: read only. */
    @FunctionalInterface
    public interface Filter {

        /** True to keep {@code hit} out of {@code DEAL_DAMAGE}. */
        boolean excludes(@Nonnull DamageHit hit);
    }

    private static final Map<String, Filter> FILTERS = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private DamageHitFilters() {
    }

    /** Register {@code owner}'s filter, replacing the one it registered before. */
    public static void register(@Nonnull String owner, @Nonnull Filter filter) {
        FILTERS.put(key(owner), filter);
    }

    /** Take {@code owner}'s filter out. */
    public static void unregister(@Nonnull String owner) {
        FILTERS.remove(key(owner));
    }

    /** Does any registered filter keep {@code hit} out? */
    public static boolean excludes(@Nonnull DamageHit hit) {
        if (FILTERS.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Filter> entry : FILTERS.entrySet()) {
            try {
                if (entry.getValue().excludes(hit)) {
                    return true;
                }
            } catch (Throwable t) {
                if (REPORTED.add(entry.getKey())) {
                    SafeLog.warn("[progression] the DEAL_DAMAGE filter of '" + entry.getKey()
                            + "' failed, so it keeps no hit out: " + t);
                }
            }
        }
        return false;
    }

    @Nonnull
    private static String key(@Nonnull String owner) {
        return owner.trim().toLowerCase(Locale.ROOT);
    }
}
