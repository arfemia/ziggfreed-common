package com.ziggfreed.common.damage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The pure half of {@link HitTagIndex}: how one damage node's tags are read out of its raw asset
 * tags and those of every asset containing it, and what happens when two nodes share one damage
 * calculator. It works over plain maps and an opaque calculator key, so it is unit-tested without
 * touching an engine class ({@code Interaction}'s static init throws in a bare test JVM, see
 * {@code CastScopes}).
 *
 * <p>A raw tag map is the engine's {@code AssetExtraInfo.Data.getRawTags()}: every authored key
 * with its values, plus each value and each {@code Key=Value} pair as a key of its own with no
 * values. Only a plain key that matches an indexed key (ignoring case) contributes, so those
 * expanded forms never do.
 */
final class HitTagMerge {

    private HitTagMerge() {
    }

    /** The case fold every tag key and value is compared under. */
    @Nonnull
    static String fold(@Nonnull String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * The values each indexed key carries on one node, read from the node's own raw tags first and
     * then from each container above it, nearest first (an inline node does not inherit its file's
     * tags, so the file's are found by climbing).
     *
     * @param chain      the node's raw tag map followed by each container's; a null entry is skipped
     * @param foldedKeys the indexed keys, already {@link #fold folded}
     * @return folded key to its values in authored spelling, in chain order, a value repeated in any
     *         casing kept once (its first spelling); a key with no value is left out. Unmodifiable.
     */
    @Nonnull
    static Map<String, List<String>> merge(@Nonnull List<? extends Map<String, String[]>> chain,
                                           @Nonnull Set<String> foldedKeys) {
        Map<String, LinkedHashMap<String, String>> found = new LinkedHashMap<>();
        for (Map<String, String[]> raw : chain) {
            if (raw == null) {
                continue;
            }
            for (Map.Entry<String, String[]> entry : raw.entrySet()) {
                String key = entry.getKey();
                String[] values = entry.getValue();
                if (key == null || values == null || values.length == 0) {
                    continue;
                }
                String foldedKey = fold(key);
                if (!foldedKeys.contains(foldedKey)) {
                    continue;
                }
                LinkedHashMap<String, String> seen = found.computeIfAbsent(foldedKey, k -> new LinkedHashMap<>());
                for (String value : values) {
                    String trimmed = value == null ? "" : value.trim();
                    if (!trimmed.isEmpty()) {
                        seen.putIfAbsent(fold(trimmed), trimmed);
                    }
                }
            }
        }
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, String>> entry : found.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                out.put(entry.getKey(), List.copyOf(entry.getValue().values()));
            }
        }
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    /**
     * Whether two nodes' tags say the same thing: the same keys, each with the same values, ignoring
     * order and case.
     */
    static boolean sameTags(@Nonnull Map<String, List<String>> a, @Nonnull Map<String, List<String>> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (Map.Entry<String, List<String>> entry : a.entrySet()) {
            if (!foldedSet(entry.getValue()).equals(foldedSet(b.get(entry.getKey())))) {
                return false;
            }
        }
        return true;
    }

    @Nonnull
    private static Set<String> foldedSet(@Nullable List<String> values) {
        Set<String> out = new HashSet<>();
        if (values != null) {
            for (String value : values) {
                out.add(fold(value));
            }
        }
        return out;
    }

    /** Adds one node's values to the running declared-values table (folded key to its values). */
    static void declare(@Nonnull Map<String, Set<String>> declared, @Nonnull Map<String, List<String>> tags) {
        for (Map.Entry<String, List<String>> entry : tags.entrySet()) {
            declared.computeIfAbsent(entry.getKey(), k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                    .addAll(entry.getValue());
        }
    }

    /**
     * The declared-values table made read-only: each set sorted and compared ignoring case (its
     * {@code contains} ignores case too), each value in the first spelling met.
     */
    @Nonnull
    static Map<String, Set<String>> freeze(@Nonnull Map<String, Set<String>> declared) {
        Map<String, Set<String>> out = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : declared.entrySet()) {
            TreeSet<String> copy = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            copy.addAll(entry.getValue());
            out.put(entry.getKey(), Collections.unmodifiableSet(copy));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Collects the tags of every damage calculator, keyed by object identity: a calculator's
     * {@code equals} compares its values, so two unrelated nodes dealing the same numbers must never
     * meet as one key.
     *
     * <p>A calculator two nodes share (a node inheriting its parent's through {@code Parent} without
     * authoring its own) whose nodes disagree on tags cannot tell which one dealt a hit, so it is
     * indexed with no tags at all and reported once. A tagged node whose calculator names more than
     * one damage type is reported too: the engine splits such a strike into one hit per type and
     * only the first carries the calculator, so the others read untagged.
     *
     * @param <K> the calculator type (the engine's {@code DamageCalculator} at runtime)
     */
    static final class Table<K> {

        private static final class Entry {
            @Nonnull
            final String owner;
            @Nonnull
            final Map<String, List<String>> tags;
            boolean conflicted;

            Entry(@Nonnull String owner, @Nonnull Map<String, List<String>> tags) {
                this.owner = owner;
                this.tags = tags;
            }
        }

        private final IdentityHashMap<K, Entry> entries = new IdentityHashMap<>();
        private final List<String> warnings = new ArrayList<>();

        /**
         * Records one node's calculator.
         *
         * @param calculator  the calculator object the engine attaches to the node's hits
         * @param owner       a readable name for the node (its interaction id), for the warnings
         * @param tags        the node's merged tags ({@link #merge})
         * @param damageTypes how many damage types the calculator names; only read when tags is non-empty
         */
        void put(@Nonnull K calculator, @Nonnull String owner, @Nonnull Map<String, List<String>> tags, int damageTypes) {
            Entry prior = entries.get(calculator);
            if (prior == null) {
                entries.put(calculator, new Entry(owner, tags));
                if (!tags.isEmpty() && damageTypes > 1) {
                    warnings.add("[hit-tags] " + owner + " is tagged " + tags + " but its DamageCalculator names "
                            + damageTypes + " damage types; only one of each strike's hits carries the tags, so keep"
                            + " a tagged damage node to one type");
                }
                return;
            }
            if (prior.conflicted || sameTags(prior.tags, tags)) {
                return;
            }
            prior.conflicted = true;
            warnings.add("[hit-tags] " + prior.owner + " " + prior.tags + " and " + owner + " " + tags
                    + " share one DamageCalculator with different tags, so neither one's hits are tagged;"
                    + " give each its own DamageCalculator");
        }

        /** Every calculator with tags whose nodes agree; untagged and conflicted calculators are left out. */
        @Nonnull
        IdentityHashMap<K, Map<String, List<String>>> tagged() {
            IdentityHashMap<K, Map<String, List<String>>> out = new IdentityHashMap<>();
            for (Map.Entry<K, Entry> entry : entries.entrySet()) {
                Entry value = entry.getValue();
                if (!value.conflicted && !value.tags.isEmpty()) {
                    out.put(entry.getKey(), value.tags);
                }
            }
            return out;
        }

        /** The warnings collected so far, in the order met. */
        @Nonnull
        List<String> warnings() {
            return List.copyOf(warnings);
        }
    }
}
