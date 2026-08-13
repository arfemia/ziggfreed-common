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
 * <p>Merge order: the library's own defaults first, then each consumer in registration order. A
 * duplicate id between a default and a consumer is the consumer's, silently - that is the namespace
 * claim working as intended. A duplicate id between two CONSUMERS is a real collision nobody can
 * resolve, so the first registered keeps it and the clash is named once.
 *
 * @param <T> the content type (a quest, an achievement, a milestone)
 */
final class ContentLayers<T> {

    /** owner -> what that owner published, in registration order. */
    private final Map<String, List<T>> layers = new LinkedHashMap<>();

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

    /** Replace {@code owner}'s whole layer. */
    void publish(@Nonnull String owner, boolean libraryDefault, @Nonnull Collection<T> layer) {
        layers.put(owner, List.copyOf(new ArrayList<>(layer)));
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
     * Every layer merged, defaults first. A duplicate id between two consumers keeps the first and
     * reports itself through {@code warn}.
     */
    @Nonnull
    List<T> compose(@Nonnull Consumer<String> warn) {
        Map<String, T> byId = new LinkedHashMap<>();
        Map<String, String> ownerOf = new LinkedHashMap<>();
        for (String owner : orderedOwners()) {
            boolean libraryDefault = defaults.contains(owner);
            for (T entry : layers.getOrDefault(owner, List.of())) {
                String id = idOf.apply(entry);
                if (id == null) {
                    continue;
                }
                String holder = ownerOf.get(id);
                if (holder != null && !defaults.contains(holder) && !libraryDefault) {
                    warn.accept("'" + owner + "' and '" + holder + "' both publish the " + label + " '"
                            + id + "'; the one from '" + holder + "' stands");
                    continue;
                }
                if (holder != null && !libraryDefault) {
                    // A consumer over a library default: the claim contract working, nothing to say.
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
        return List.copyOf(new ArrayList<>(byId.values()));
    }

    /** How many entries each owner contributed AFTER the merge, for the boot diagnostic. */
    @Nonnull
    Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String owner : orderedOwners()) {
            out.put(owner, Integer.valueOf(layers.getOrDefault(owner, List.of()).size()));
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
