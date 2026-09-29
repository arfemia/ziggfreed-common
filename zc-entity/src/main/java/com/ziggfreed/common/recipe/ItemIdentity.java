package com.ziggfreed.common.recipe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a recipe line can recognise an item BY: its id, its native raw tags and its resource
 * families. The three things {@link RecipeMaterial#accepts} weighs, and nothing else, so a reverse
 * lookup asks the same question the engine asks when it consumes the item.
 *
 * @param itemId        the item's id
 * @param tags          the item's native raw tag map ({@code {"<family>": ["<value>", ...]}}), which
 *                      the engine expands so every value and pairing is also a key of its own. Kept
 *                      as an unmodifiable COPY (each value array cloned), never the engine's live
 *                      map, which the engine merges into on a reload
 * @param resourceTypes the resource families the item counts toward
 */
public record ItemIdentity(@Nonnull String itemId, @Nonnull Map<String, String[]> tags,
        @Nonnull List<String> resourceTypes) {

    public ItemIdentity {
        itemId = itemId == null ? "" : itemId.trim();
        tags = copyOf(tags);
        resourceTypes = resourceTypes == null ? List.of()
                : resourceTypes.stream().filter(Objects::nonNull).filter(r -> !r.isBlank()).toList();
    }

    /**
     * An unmodifiable copy of {@code tags}, each value array cloned, so an identity inside an
     * immutable catalog never shares state with the engine's live raw-tag map. A null key is
     * dropped; a null value is kept as it was. Lookups stay null-tolerant, as on the engine's own
     * map.
     */
    @Nonnull
    private static Map<String, String[]> copyOf(@Nullable Map<String, String[]> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String[]> copy = new LinkedHashMap<>(tags.size());
        tags.forEach((key, values) -> {
            if (key != null) {
                copy.put(key, values == null ? null : values.clone());
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    /** An item known by id alone (no tags, no families): it satisfies exact-item lines only. */
    @Nonnull
    public static ItemIdentity ofId(@Nonnull String itemId) {
        return new ItemIdentity(itemId, Map.of(), List.of());
    }
}
