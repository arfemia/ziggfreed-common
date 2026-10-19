package com.ziggfreed.common.ui.icon;

import java.util.Locale;

import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;

import com.ziggfreed.common.i18n.Msg;

/**
 * How an item's quality reads on a card, in the game's own terms, read from the item's quality index
 * ({@code Item.getQualityIndex}) through {@code ItemQuality.getAssetMap()}, an item whose index names no quality
 * reading as the engine's default one:
 * <ul>
 *   <li>{@code rank}, the quality's {@code QualityValue}: the number that orders the tiers, so a pack's own tier sorts
 *       among the game's with no code change;</li>
 *   <li>{@code word}, the engine's word for it, its {@code LocalizationKey} ({@code server.general.qualities.Rare});
 *       null where its {@code VisibleQualityLabel} is false, as the game's own tooltip leaves it off;</li>
 *   <li>{@code colour}, its {@code TextColor} as {@code #rrggbb}, or null when it names none.</li>
 * </ul>
 * The rarity slot itself is the client's to draw (an {@code ItemSlot} with {@code ShowQualityBackground}); this is
 * only what a server must say beside it.
 */
public record ItemQualityLook(int rank, @Nullable Message word, @Nullable String colour) {

    /** The rank of an item this server does not ship: under every quality, so it sorts last. */
    public static final int UNKNOWN_RANK = Integer.MIN_VALUE;

    /**
     * The look of {@code itemId}'s quality, or null when this server ships no such item, or no item store is loaded
     * to ask (a unit JVM). Never throws.
     */
    @Nullable
    public static ItemQualityLook of(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item == null) {
                return null;
            }
            ItemQuality quality = ItemQuality.getAssetMap().getAsset(item.getQualityIndex());
            if (quality == null) {
                quality = ItemQuality.DEFAULT_ITEM_QUALITY;
            }
            String key = quality.getLocalizationKey();
            Message word = quality.isVisibleQualityLabel() && key != null && !key.isBlank() ? Msg.key(key) : null;
            return new ItemQualityLook(quality.getQualityValue(), word, hex(quality.getTextColor()));
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code itemId}'s rank, for sorting best first: its quality's {@code QualityValue}, else {@link #UNKNOWN_RANK}. */
    public static int rank(@Nullable String itemId) {
        ItemQualityLook look = of(itemId);
        return look == null ? UNKNOWN_RANK : look.rank();
    }

    @Nullable
    private static String hex(@Nullable Color colour) {
        return colour == null ? null : String.format(Locale.ROOT, "#%02x%02x%02x", colour.red & 0xff,
                colour.green & 0xff, colour.blue & 0xff);
    }
}
