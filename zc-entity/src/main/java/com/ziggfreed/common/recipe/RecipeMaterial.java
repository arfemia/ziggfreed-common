package com.ziggfreed.common.recipe;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.match.ItemMatch;

/**
 * One side of a native recipe line: WHAT it names and HOW MANY, read off the engine's
 * {@code MaterialQuantity}.
 *
 * <p><b>Exactly one route is taken, in the engine's own order.</b> A native material may name an
 * exact {@code ItemId}, an {@code ItemTag}, or a {@code ResourceTypeId}; when the engine consumes
 * one it takes the first of those that is authored, in that order, and this record keeps only that
 * route (the others read null). A material whose tag could not be named (no loaded item carries it,
 * so nothing could satisfy it natively either) keeps NO route and matches nothing.
 *
 * <p>{@code quantity} is the authored count, always at least 1. {@code excludedItemIds} are the
 * items the engine refuses for this line even when a tag or resource route would accept them (a
 * recipe never consumes its own output as a generic ingredient).
 *
 * @param itemId          the exact item route, or null
 * @param tag             the native item-tag route (the tag's name), or null
 * @param resourceTypeId  the resource-family route, or null
 * @param quantity        how many, at least 1
 * @param excludedItemIds items refused for this line whatever the route says
 */
public record RecipeMaterial(@Nullable String itemId, @Nullable String tag, @Nullable String resourceTypeId,
        int quantity, @Nonnull Set<String> excludedItemIds) {

    public RecipeMaterial {
        itemId = blankToNull(itemId);
        tag = itemId != null ? null : blankToNull(tag);
        resourceTypeId = itemId != null || tag != null ? null : blankToNull(resourceTypeId);
        quantity = Math.max(1, quantity);
        excludedItemIds = excludedItemIds == null ? Set.of() : excludedItemIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** An exact-item line. */
    @Nonnull
    public static RecipeMaterial item(@Nonnull String itemId, int quantity) {
        return new RecipeMaterial(itemId, null, null, quantity, Set.of());
    }

    /** A native item-tag line, named by the tag. */
    @Nonnull
    public static RecipeMaterial tagged(@Nonnull String tag, int quantity) {
        return new RecipeMaterial(null, tag, null, quantity, Set.of());
    }

    /** A resource-family line. */
    @Nonnull
    public static RecipeMaterial resource(@Nonnull String resourceTypeId, int quantity) {
        return new RecipeMaterial(null, null, resourceTypeId, quantity, Set.of());
    }

    /** True when this line names something an item could satisfy. */
    public boolean hasRoute() {
        return itemId != null || tag != null || resourceTypeId != null;
    }

    /**
     * Would this line accept {@code item}? Answered through the shared {@link ItemMatch} core on the
     * one route the line takes, after the engine's own exclusions. False for a route-less line.
     */
    public boolean accepts(@Nonnull ItemIdentity item) {
        if (isExcluded(item.itemId())) {
            return false;
        }
        if (itemId != null) {
            return ItemMatch.itemId(itemId, item.itemId());
        }
        if (tag != null) {
            return ItemMatch.tags(Map.of(tag, new String[0]), item.tags());
        }
        return resourceTypeId != null
                && ItemMatch.resourceFamily(resourceTypeId, item.resourceTypes().toArray(new String[0]));
    }

    /** True when this line names {@code candidateItemId} by its exact item route. */
    public boolean isItem(@Nullable String candidateItemId) {
        return ItemMatch.itemId(itemId, candidateItemId);
    }

    private boolean isExcluded(@Nonnull String candidateItemId) {
        String wanted = candidateItemId.toLowerCase(Locale.ROOT);
        for (String excluded : excludedItemIds) {
            if (excluded.toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
