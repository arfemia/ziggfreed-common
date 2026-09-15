package com.ziggfreed.common.progress.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nonnull;

/**
 * The content every owner published, kept as PER-OWNER LAYERS and recomposed into one catalogue on
 * every publish.
 *
 * <p>Layers rather than one accumulating pile, because a content reload has to be able to REPLACE
 * what one owner published without disturbing anybody else's - which is exactly what
 * {@code /reload}-shaped commands do, several times a session, on a live server.
 *
 * <p><b>A layer is further keyed by SLICE.</b> One owner name can publish from more than one fold -
 * a caller elsewhere in the same process can hold its own content store and publish under this same
 * engine's owner name too - and each fold has to be able to replace only what IT published on its
 * own reload, without wiping the other fold's entries out from under it. A publish with no slice
 * named uses the default slice, so an owner that only ever folds once never has to think about this
 * at all.
 *
 * <p>Merge order: the library's own defaults first, then each consumer in registration order; within
 * one owner, every slice folds together, in the order that owner first published each slice. A
 * duplicate id between a default and a consumer is the consumer's, silently. That silence is the
 * WHOLE resolution story for overlapping content: every reader folds the same shared store and
 * publishes what it folded, so a consumer that converts a file into something richer than the
 * generic reading simply publishes it at a higher rank and wins, and a file only the library folded
 * still reaches the engines. Nothing has to be claimed, declared or stood down for that to hold.
 *
 * <p>A duplicate id between two CONSUMERS is a real collision nobody can resolve, so the first
 * registered keeps it and the clash is named once. A duplicate id between two SLICES of the SAME
 * owner is named the same way, because it is just as unresolvable: nothing here knows which of an
 * owner's own folds should give way to the other.
 *
 * @param <T> the content type (a quest, an achievement, a milestone)
 */
final class ContentLayers<T> {

    /** The slice a publish uses when it names none. */
    private static final String DEFAULT_SLICE = "";

    /** owner -> slice -> what that slice published, in registration order. */
    private final Map<String, Map<String, List<T>>> layers = new LinkedHashMap<>();

    /** The owners registered at library-default rank, so they compose first. */
    private final Set<String> defaults = new LinkedHashSet<>();

    /** How an entry names itself, for duplicate detection. */
    private final Function<T, String> idOf;

    /** What this kind of content is called in a collision warning. */
    private final String label;

    ContentLayers(@Nonnull Function<T, String> idOf, @Nonnull String label) {
        this.idOf = idOf;
        this.label = label;
    }

    /** Replace {@code owner}'s DEFAULT slice. */
    void publish(@Nonnull String owner, boolean libraryDefault, @Nonnull Collection<T> layer) {
        publish(owner, libraryDefault, DEFAULT_SLICE, layer);
    }

    /**
     * Replace only {@code owner}'s {@code slice}, leaving every other slice that owner published
     * untouched.
     */
    void publish(@Nonnull String owner, boolean libraryDefault, @Nonnull String slice,
                @Nonnull Collection<T> layer) {
        layers.computeIfAbsent(owner, k -> new LinkedHashMap<>())
                .put(slice, List.copyOf(new ArrayList<>(layer)));
        if (libraryDefault) {
            defaults.add(owner);
        } else {
            defaults.remove(owner);
        }
    }

    /** Is anything published at all? */
    boolean isEmpty() {
        return layers.isEmpty();
    }

    /**
     * Every layer merged, defaults first, an owner's own slices folded together in the order it
     * first published each one. A duplicate id between two slices of ONE owner keeps the first slice
     * and reports itself through {@code warn}; a duplicate id between two consumers does the same.
     */
    @Nonnull
    List<T> compose(@Nonnull Consumer<String> warn) {
        Map<String, T> byId = new LinkedHashMap<>();
        Map<String, String> ownerOf = new LinkedHashMap<>();
        for (String owner : orderedOwners()) {
            boolean libraryDefault = defaults.contains(owner);
            Map<String, String> sliceOfId = new LinkedHashMap<>();
            for (Map.Entry<String, List<T>> sliceEntry
                    : layers.getOrDefault(owner, Map.of()).entrySet()) {
                String slice = sliceEntry.getKey();
                for (T entry : sliceEntry.getValue()) {
                    String id = idOf.apply(entry);
                    if (id == null) {
                        continue;
                    }
                    String heldSlice = sliceOfId.get(id);
                    if (heldSlice != null) {
                        warn.accept(heldSlice.equals(slice)
                                ? "'" + owner + "' publishes the " + label + " '" + id
                                        + "' twice in its " + sliceName(slice)
                                        + " slice; the first stands"
                                : "'" + owner + "' publishes the " + label + " '" + id
                                        + "' twice, in its " + sliceName(heldSlice) + " and "
                                        + sliceName(slice) + " slices; the one from its "
                                        + sliceName(heldSlice) + " slice stands");
                        continue;
                    }
                    sliceOfId.put(id, slice);

                    String holder = ownerOf.get(id);
                    if (holder != null && !defaults.contains(holder) && !libraryDefault) {
                        warn.accept("'" + owner + "' and '" + holder + "' both publish the " + label
                                + " '" + id + "'; the one from '" + holder + "' stands");
                        continue;
                    }
                    if (holder != null && !libraryDefault) {
                        // A consumer over a library default: the claim contract working, nothing to
                        // say.
                        byId.put(id, entry);
                        ownerOf.put(id, owner);
                        continue;
                    }
                    if (holder != null) {
                        continue;
                    }
                    byId.put(id, entry);
                    ownerOf.put(id, owner);
                }
            }
        }
        return List.copyOf(new ArrayList<>(byId.values()));
    }

    /** A slice as a warning names it: the default slice has no name of its own. */
    @Nonnull
    private static String sliceName(@Nonnull String slice) {
        return slice.isEmpty() ? "default" : "'" + slice + "'";
    }

    /** How many entries each owner contributed AFTER the merge, across every slice, for the boot diagnostic. */
    @Nonnull
    Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String owner : orderedOwners()) {
            int total = 0;
            for (List<T> slice : layers.getOrDefault(owner, Map.of()).values()) {
                total += slice.size();
            }
            out.put(owner, Integer.valueOf(total));
        }
        return out;
    }

    void clear() {
        layers.clear();
        defaults.clear();
    }

    /** Library defaults first, then consumers in registration order. */
    @Nonnull
    private List<String> orderedOwners() {
        List<String> ordered = new ArrayList<>();
        for (String owner : layers.keySet()) {
            if (defaults.contains(owner)) {
                ordered.add(owner);
            }
        }
        for (String owner : layers.keySet()) {
            if (!defaults.contains(owner)) {
                ordered.add(owner);
            }
        }
        return ordered;
    }
}
