package com.ziggfreed.common.progress;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An inverted index from objective KIND to the objectives that listen for it, so a progress event
 * touches only the handful of objectives that could possibly match instead of every objective in
 * the whole catalogue.
 *
 * <p>Events fire constantly and owners are relatively few, so this is the difference between a
 * per-event cost that scales with the whole content catalogue and one that scales with the tiny
 * slice authored against a single kind. Built once per content load and replaced wholesale when the
 * catalogue changes - never mutated in place, so a dispatch already walking it is unaffected.
 *
 * <p>Owner-agnostic on purpose: {@link #of} takes the owners plus two accessors, so any lifecycle
 * engine indexes its own content type without this index learning what that type is.
 *
 * <p>Immutable and safe to share.
 */
public final class ObjectiveIndex {

    /** One authored objective, remembered together with the id of the owner it belongs to. */
    public record Entry(@Nonnull String ownerId, @Nonnull ObjectiveDef objective) {
    }

    /** An index over nothing. */
    public static final ObjectiveIndex EMPTY = new ObjectiveIndex(Map.of());

    private final Map<String, List<Entry>> byKind;

    private ObjectiveIndex(@Nonnull Map<String, List<Entry>> byKind) {
        this.byKind = byKind;
    }

    /**
     * Index every objective on every owner. {@code idOf} names an owner and {@code objectivesOf}
     * lists what it authored, which is all this index needs to know about a content type. Kind keys
     * are normalized the same way the vocabulary normalizes them, so a lookup matches regardless of
     * how the content spelled the id.
     */
    @Nonnull
    public static <T> ObjectiveIndex of(@Nonnull Collection<T> owners,
                                        @Nonnull Function<T, String> idOf,
                                        @Nonnull Function<T, List<ObjectiveDef>> objectivesOf) {
        Map<String, List<Entry>> byKind = new LinkedHashMap<>();
        for (T owner : owners) {
            String ownerId = idOf.apply(owner);
            for (ObjectiveDef objective : objectivesOf.apply(owner)) {
                byKind.computeIfAbsent(normalize(objective.kind()), key -> new ArrayList<>())
                        .add(new Entry(ownerId, objective));
            }
        }
        Map<String, List<Entry>> frozen = new LinkedHashMap<>();
        byKind.forEach((kind, entries) -> frozen.put(kind, List.copyOf(entries)));
        return new ObjectiveIndex(Map.copyOf(frozen));
    }

    /** Every objective listening for {@code kindId}, in content order. Empty when none do. */
    @Nonnull
    public List<Entry> forKind(@Nullable String kindId) {
        if (kindId == null || kindId.isBlank()) {
            return List.of();
        }
        List<Entry> entries = byKind.get(normalize(kindId));
        return entries != null ? entries : List.of();
    }

    /** Every kind at least one objective listens for. Tells a producer which events are worth firing. */
    @Nonnull
    public Collection<String> listenedKinds() {
        return byKind.keySet();
    }

    /** How many objectives are indexed in total. */
    public int size() {
        int total = 0;
        for (List<Entry> entries : byKind.values()) {
            total += entries.size();
        }
        return total;
    }

    @Nonnull
    private static String normalize(@Nonnull String kindId) {
        return kindId.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
