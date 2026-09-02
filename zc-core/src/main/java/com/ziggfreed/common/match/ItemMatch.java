package com.ziggfreed.common.match;

import java.util.Map;

import javax.annotation.Nullable;

import com.ziggfreed.common.codec.TagMatch;

/**
 * The ONE item-identity matching core: three pure route predicates - exact {@code ItemId},
 * native {@code Tags} (the shared {@link TagMatch} map), native {@code ResourceType} FAMILY -
 * plus the {@link #any} OR-composition, evaluated over a candidate's already-resolved identity
 * (its id, its raw tag map, its resource-family ids). Pure statics over strings and maps: zero
 * engine types, so an engine-live matcher and a bare unit test call the exact same code.
 *
 * <p><b>Why one core:</b> every "does this item satisfy this authored reference?" surface -
 * a recipe ingredient, a held-item action selector, a placement acceptance gate - speaks the same
 * three routes, and each consumer re-implementing the loops drifts one route at a time. The
 * consumer keeps its own LEAF (its codec, its field names, its exactly-one-of or any-of rule)
 * and delegates the answering to these predicates.
 *
 * <p><b>Native consumption precedence (documented, not enforced here):</b> when the engine
 * consumes a {@code MaterialQuantity} that authors several routes, it takes the FIRST authored
 * route in the fixed order exact {@code ItemId} &gt; item tag &gt; resource family
 * ({@code InternalContainerUtilMaterial#internal_removeMaterialFromSlot}, the same order in every
 * test/count/remove sibling). {@link #any}'s parameters follow that order so the convention is
 * visible at every call site; the boolean OR itself is order-independent, and a consumer whose
 * leaf allows only one authored route never sees the difference.
 *
 * <p><b>Route-not-taken is false, never true:</b> each single-route predicate answers
 * {@code false} when its required side is null/blank/empty, and {@link #any} answers
 * {@code false} when NO route is authored. What "no route authored" means (a catch-all matcher,
 * a match-anything ingredient, a closed gate) is the consumer's decision, made at the call site.
 */
public final class ItemMatch {

    private ItemMatch() {
    }

    /** The exact-id route: case-insensitive id equality. False when either side is null/blank. */
    public static boolean itemId(@Nullable String required, @Nullable String candidateItemId) {
        return required != null && !required.isBlank() && candidateItemId != null
                && required.equalsIgnoreCase(candidateItemId);
    }

    /**
     * The resource-FAMILY route against one candidate family id (case-insensitive). False when
     * either side is null/blank.
     */
    public static boolean resourceFamily(@Nullable String required, @Nullable String candidateFamily) {
        return required != null && !required.isBlank() && candidateFamily != null
                && required.equalsIgnoreCase(candidateFamily);
    }

    /**
     * The resource-FAMILY route against a candidate's whole resolved family set: true when ANY
     * family matches. False when the required side is null/blank or the set is null/empty.
     */
    public static boolean resourceFamily(@Nullable String required, @Nullable String[] candidateFamilies) {
        if (required == null || required.isBlank() || candidateFamilies == null) {
            return false;
        }
        for (String family : candidateFamilies) {
            if (family != null && required.equalsIgnoreCase(family)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The native-tag route over the shared {@link TagMatch} map shape
     * ({@code {"<tagFamily>": ["<value>", ...]}}), in two forms:
     *
     * <ul>
     *   <li><b>Family + values</b> ({@link TagMatch#matches}'s ANY-of): any authored family key the
     *       candidate carries, with any listed value present under it.</li>
     *   <li><b>Presence</b>: an authored family with an EMPTY value array ({@code {"Planks": []}})
     *       matches on the KEY's presence alone (case-insensitive). This is the single-tag form the
     *       engine's own expanded tag storage answers - an item's raw tag map carries every value
     *       and every {@code family=value} pair as a key of its own, so "carries tag T" is exactly
     *       "the raw tag map has key T".</li>
     * </ul>
     *
     * False when either map is null/empty (route not taken / candidate identity unresolved).
     */
    public static boolean tags(@Nullable Map<String, String[]> required,
            @Nullable Map<String, String[]> candidateTags) {
        if (required == null || required.isEmpty() || candidateTags == null || candidateTags.isEmpty()) {
            return false;
        }
        if (TagMatch.matches(required, candidateTags)) {
            return true;
        }
        for (Map.Entry<String, String[]> req : required.entrySet()) {
            if (req.getValue() == null || req.getValue().length > 0) {
                continue; // null = an authoring artifact (skipped); non-empty = the values form above
            }
            String wantKey = req.getKey();
            if (wantKey == null || wantKey.isBlank()) {
                continue;
            }
            for (String haveKey : candidateTags.keySet()) {
                if (wantKey.equalsIgnoreCase(haveKey)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The OR-composition: true when ANY authored route matches the candidate's identity. The
     * parameter order (exact id, tags, resource family) mirrors the native consumption precedence
     * documented on the class. False when no route is authored at all - the caller decides what a
     * route-less reference means.
     */
    public static boolean any(@Nullable String requiredItemId, @Nullable Map<String, String[]> requiredTags,
            @Nullable String requiredResourceTypeId,
            @Nullable String candidateItemId, @Nullable Map<String, String[]> candidateTags,
            @Nullable String[] candidateFamilies) {
        return itemId(requiredItemId, candidateItemId)
                || tags(requiredTags, candidateTags)
                || resourceFamily(requiredResourceTypeId, candidateFamilies);
    }
}
