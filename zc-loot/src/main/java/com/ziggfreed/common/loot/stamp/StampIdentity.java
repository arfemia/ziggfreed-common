package com.ziggfreed.common.loot.stamp;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a stamp makes an item CALLED and how rare it looks - both authored, never derived.
 *
 * <p>Nothing is renamed or re-tinted unless a file says to. That is deliberate: an enhanced item is
 * the item it always was, and a library inventing "Superior" or "Masterwork" would be shipping a
 * quality vocabulary that every server then has to live with. An author who wants one writes it.
 *
 * <p>{@code nameKey} is a full translation key handed the item's own name as a {@code {item}}
 * argument, so {@code "Honed {item}"} localizes and reads correctly whatever it is stamped onto.
 * {@code qualityId} names an {@code ItemQuality} asset, the engine's own per-instance rarity channel
 * - it drives the name colour, the tooltip frame, the slot texture and a localized rarity label for
 * free, which is why this does not invent a rarity model of its own.
 */
public record StampIdentity(@Nullable String nameKey, @Nullable String qualityId) {

    /** Neither authored: the item keeps its own name and its own rarity. */
    public static final StampIdentity NONE = new StampIdentity(null, null);

    /** True when there is nothing to apply, so a caller can skip the work entirely. */
    public boolean isEmpty() {
        return blank(nameKey) && blank(qualityId);
    }

    /**
     * The identity a stamp should apply: whatever the STAMP authored, falling back per field to the
     * pool it drew from. Per field, not wholesale - a stamp naming an item without restating the
     * pool's rarity should not silently drop that rarity.
     */
    @Nonnull
    public static StampIdentity resolve(@Nullable StampSpec spec, @Nullable RollPoolAsset pool) {
        String name = firstAuthored(spec == null ? null : spec.getName(), pool == null ? null : pool.getName());
        String quality = firstAuthored(spec == null ? null : spec.getQuality(),
                pool == null ? null : pool.getQuality());
        return name == null && quality == null ? NONE : new StampIdentity(name, quality);
    }

    @Nullable
    private static String firstAuthored(@Nullable String preferred, @Nullable String fallback) {
        if (!blank(preferred)) {
            return preferred;
        }
        return blank(fallback) ? null : fallback;
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
