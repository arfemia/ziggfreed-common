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
 * of them in full (a retired stat that covered two of today's), never split between them. An alias
 * registered with a scale ({@link #registerScaled}) counts the old value times that scale, for a stat
 * whose successor counts in another unit (a flat bonus onto a percent one, a percent onto seconds). Ids
 * match case-insensitively, as the engine keys its assets. Each owner registers only its own retired ids, at
 * setup: the library its own (the flat damage stats, zc-entity's {@code TypedDamageBonuses}), a consumer its.
 *
 * <p>{@link StackStats} reads and merges through {@link #normalize}, so a saved stack's old entries
 * apply under their new ids, and a stack stamped again stores the new ids.
 */
public final class StatAliases {

    /** One retirement: its successors in registration order, and what one old point is worth on each. */
    private record Alias(@Nonnull List<String> targets, double scale) {
    }

    /** Old id, lower-cased, to its retirement. */
    @Nonnull
    private static final Map<String, Alias> ALIASES = new ConcurrentHashMap<>();

    private StatAliases() {
    }

    /**
     * Retire {@code oldId} in favour of {@code newIds} (one or more), each taking the old value in full. A
     * second registration of the same old id replaces the first. Blank ids, an empty successor list or an id
     * aliased to itself register nothing.
     */
    public static void register(@Nonnull String oldId, @Nonnull String... newIds) {
        registerScaled(oldId, 1.0, newIds);
    }

    /**
     * Retire {@code oldId} in favour of {@code newIds}, each taking the old value times {@code scale}. A scale
     * that is not a finite number above zero registers nothing; otherwise as {@link #register}.
     */
    public static void registerScaled(@Nonnull String oldId, double scale, @Nonnull String... newIds) {
        if (oldId.isBlank() || newIds.length == 0 || !Double.isFinite(scale) || !(scale > 0.0)) {
            return;
        }
        List<String> targets = new ArrayList<>(newIds.length);
        for (String id : newIds) {
            if (id != null && !id.isBlank() && !id.equalsIgnoreCase(oldId) && !targets.contains(id)) {
                targets.add(id);
            }
        }
        if (!targets.isEmpty()) {
            ALIASES.put(oldId.toLowerCase(Locale.ROOT), new Alias(List.copyOf(targets), scale));
        }
    }

    /** Whether {@code id} is a retired id some consumer registered. */
    public static boolean isRetired(@Nullable String id) {
        return id != null && ALIASES.containsKey(id.toLowerCase(Locale.ROOT));
    }

    /** The ids {@code id} counts toward now: its successors when retired, else itself. */
    @Nonnull
    public static List<String> resolve(@Nonnull String id) {
        Alias alias = ALIASES.get(id.toLowerCase(Locale.ROOT));
        return alias != null ? alias.targets() : List.of(id);
    }

    /** What one point of {@code id} is worth on each id {@link #resolve} answers: its alias's scale, else 1. */
    public static double scale(@Nonnull String id) {
        Alias alias = ALIASES.get(id.toLowerCase(Locale.ROOT));
        return alias != null ? alias.scale() : 1.0;
    }

    /**
     * {@code entries} with every retired id replaced by its successors, each successor taking the old
     * value times the alias's scale and summing with whatever already sat under it; first-seen order is
     * kept and null keys or values are dropped. Answers the same map when nothing in it is retired.
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
            double value = e.getValue() * scale(e.getKey());
            for (String target : resolve(e.getKey())) {
                out.merge(target, value, Double::sum);
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
