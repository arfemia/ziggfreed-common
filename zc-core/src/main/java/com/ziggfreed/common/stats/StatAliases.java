package com.ziggfreed.common.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The one registry of retired stat ids: a stat a consumer renamed or folded into another keeps reading
 * where it now lives. Every reader and writer of a stat id routes through it, so an id an item saved,
 * an asset named or a mod still writes lands on its successor.
 *
 * <p>An alias maps one old id to one or more new ids. With several, the old value counts toward EACH
 * of them in full (a retired stat that covered two of today's), never split between them. Ids match
 * case-insensitively, as the engine keys its assets. A consumer registers only its own retired ids, at
 * setup; the library registers none.
 *
 * <p>{@link StackStats} reads and merges through {@link #normalize}, so a saved stack's old entries
 * apply under their new ids, and a stack stamped again stores the new ids.
 */
public final class StatAliases {

    /** Old id, lower-cased, to its successors in registration order. */
    @Nonnull
    private static final Map<String, List<String>> ALIASES = new ConcurrentHashMap<>();

    private StatAliases() {
    }

    /**
     * Retire {@code oldId} in favour of {@code newIds} (one or more). A second registration of the same
     * old id replaces the first. Blank ids, an empty successor list or an id aliased to itself register
     * nothing.
     */
    public static void register(@Nonnull String oldId, @Nonnull String... newIds) {
        if (oldId.isBlank() || newIds.length == 0) {
            return;
        }
        List<String> targets = new ArrayList<>(newIds.length);
        for (String id : newIds) {
            if (id != null && !id.isBlank() && !id.equalsIgnoreCase(oldId) && !targets.contains(id)) {
                targets.add(id);
            }
        }
        if (!targets.isEmpty()) {
            ALIASES.put(oldId.toLowerCase(Locale.ROOT), List.copyOf(targets));
        }
    }

    /** Whether {@code id} is a retired id some consumer registered. */
    public static boolean isRetired(@Nullable String id) {
        return id != null && ALIASES.containsKey(id.toLowerCase(Locale.ROOT));
    }

    /** The ids {@code id} counts toward now: its successors when retired, else itself. */
    @Nonnull
    public static List<String> resolve(@Nonnull String id) {
        List<String> targets = ALIASES.get(id.toLowerCase(Locale.ROOT));
        return targets != null ? targets : List.of(id);
    }

    /**
     * {@code entries} with every retired id replaced by its successors, each successor taking the old
     * value in full and summing with whatever already sat under it; first-seen order is kept and null
     * keys or values are dropped. Answers the same map when nothing in it is retired.
     */
    @Nonnull
    public static Map<String, Double> normalize(@Nonnull Map<String, Double> entries) {
        if (ALIASES.isEmpty() || entries.isEmpty() || !anyRetired(entries)) {
            return entries;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : entries.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            for (String target : resolve(e.getKey())) {
                out.merge(target, e.getValue(), Double::sum);
            }
        }
        return out;
    }

    private static boolean anyRetired(@Nonnull Map<String, Double> entries) {
        for (String key : entries.keySet()) {
            if (isRetired(key)) {
                return true;
            }
        }
        return false;
    }

    /** Forget every alias (tests). */
    public static void resetForTests() {
        ALIASES.clear();
    }
}
