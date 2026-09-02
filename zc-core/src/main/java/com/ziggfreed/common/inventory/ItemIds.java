package com.ziggfreed.common.inventory;

import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;

/**
 * Does this server actually ship an item under this id?
 *
 * <p>The obvious ways to ask do not work. {@code ItemStack#getItem()} never answers {@code null} -
 * it falls back to {@code Item.UNKNOWN}, whose texture is {@code Items/Unknown.png} - so
 * {@code ItemStack#isValid()}, which is written as {@code isEmpty() || getItem() != null}, is true
 * for every id that was ever spelled. A surface that probes either one and then draws the id paints
 * the unknown-item picture at the player instead of drawing nothing, which reads as a promise of a
 * thing that does not exist.
 *
 * <p>So the honest question is the asset map's, and it is asked here once: a caller that wants to
 * draw or name an item id checks {@link #exists} first and falls back to its own no-picture path.
 *
 * <p>Safe before assets load and in a JVM with no asset store at all - it answers false rather than
 * throwing, so a unit test and an early-boot caller both get a usable answer.
 */
public final class ItemIds {

    private ItemIds() {
    }

    /** Is {@code itemId} an item this server ships? Blank ids and unknown ids are both false. */
    public static boolean exists(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        try {
            return Item.getAssetMap().getAsset(itemId) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
