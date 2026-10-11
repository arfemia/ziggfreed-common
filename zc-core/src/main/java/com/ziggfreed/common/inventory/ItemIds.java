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

    /**
     * Whether this server ships {@code itemId}, or null when no items are loaded to ask (an audit run
     * before the packs load, or a JVM with no asset store). The question a content audit asks: it
     * reports an unknown id only on a definite false, so a check that could not run never invents a
     * finding. {@link #exists} answers false in that case instead. A blank id is false.
     */
    @Nullable
    public static Boolean loaded(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        try {
            var items = Item.getAssetMap();
            if (items == null || items.getAssetMap().isEmpty()) {
                return null;
            }
            return items.getAsset(itemId) != null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The item's own picture as a Common-rooted texture path (its {@code Icon}, e.g.
     * {@code Icons/ItemsGenerated/Deco_Lever.png}), for a surface that draws the picture alone in an
     * {@code AssetImage}: no item tooltip and no rarity square, at any size. Null for a blank or
     * unknown id, an item with no icon, or a JVM with no asset store.
     */
    @Nullable
    public static String iconPath(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            String icon = item == null ? null : item.getIcon();
            return icon == null || icon.isBlank() ? null : icon;
        } catch (Throwable t) {
            return null;
        }
    }
}
