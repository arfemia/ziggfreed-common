package com.ziggfreed.common.almanac.asset;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * The items a player can find this season, as a grid in a season page's {@code Sections}. Each item
 * shows its own picture once the player has obtained it once (any route into any of their inventory
 * sections, recorded for good), or while it is not {@code Hidden}; a hidden one shows a '?' until then.
 *
 * <pre>{@code
 * { "Collection": { "Text": { "TitleKey": "almanac.spring_fair.items.title" },
 *     "Items": [ { "Item": "Spring_Fair_Ribbon", "SourceKey": "almanac.spring_fair.source.stall" },
 *                { "Item": "Spring_Fair_Kite_Gold", "Hidden": true } ],
 *     "Button": { "TextKey": "almanac.spring_fair.items.stall",
 *                 "Destination": { "Type": "Shop", "Shop": "Spring_Fair_Stall" } } } }
 * }</pre>
 */
public final class AlmanacCollectionAsset {

    @Nullable private ContentTextAsset text;
    @Nullable private Slot[] items;
    @Nullable private AlmanacLinkAsset button;

    public static final BuilderCodec<AlmanacCollectionAsset> CODEC =
            BuilderCodec.builder(AlmanacCollectionAsset.class, AlmanacCollectionAsset::new)
                    .append(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false), (c, v) -> c.text = v, c -> c.text)
                    .documentation("The grid's heading (TitleKey; the library's own words when unauthored) and a "
                            + "line under it (FlavorKey).").add()
                    .append(new KeyedCodec<>("Items", new ArrayCodec<>(Slot.CODEC, Slot[]::new), false),
                            (c, v) -> c.items = v, c -> c.items)
                    .documentation("The items a player can find this season, drawn in the order written, at most "
                            + "45, nine a row. An item the server does not have is left out.").add()
                    .append(new KeyedCodec<>("Button", AlmanacLinkAsset.CODEC, false),
                            (c, v) -> c.button = v, c -> c.button)
                    .documentation("A button under the grid: its words and what it opens, in the shared "
                            + "destination vocabulary, for example a storefront: { \"Type\": \"Shop\", \"Shop\": "
                            + "\"<storefront id>\" }. A button with no words, or one opening a screen no installed "
                            + "mod registers, is left out.").add()
                    .build();

    public AlmanacCollectionAsset() {
    }

    /** The entries that name an item, in the order written (the reader caps and checks them). */
    @Nonnull
    public List<Slot> slots() {
        if (items == null) {
            return List.of();
        }
        List<Slot> out = new ArrayList<>();
        for (Slot slot : items) {
            if (slot != null && slot.item() != null) {
                out.add(slot);
            }
        }
        return List.copyOf(out);
    }

    /** The grid's heading key, or null for the library's own words. */
    @Nullable
    public String titleKey() {
        return text == null ? null : AlmanacHeroAsset.blankToNull(text.getTitleKey());
    }

    /** The key of the line under the heading, or null. */
    @Nullable
    public String flavorKey() {
        return text == null ? null : AlmanacHeroAsset.blankToNull(text.getFlavorKey());
    }

    /** The button, when it has words and somewhere this server can open; else null. */
    @Nullable
    public AlmanacLinkAsset button() {
        return button != null && button.usable() ? button : null;
    }

    /** Whether a button was written that has no words or nowhere this server can open. */
    public boolean buttonLeftOut() {
        return button != null && !button.usable();
    }

    /** One item of the grid: which item, whether it hides until obtained once, and where it comes from. */
    public static final class Slot {

        @Nullable private String item;
        @Nullable private Boolean hidden;
        @Nullable private String sourceKey;

        public static final BuilderCodec<Slot> CODEC = BuilderCodec.builder(Slot.class, Slot::new)
                .append(new KeyedCodec<>("Item", Codec.STRING, false), (s, v) -> s.item = v, s -> s.item)
                .metadata(EditorSchema.assetRef(Item.class))
                .documentation("The item, by id. It shows its own picture once the player has it, or while it "
                        + "is not Hidden.").add()
                .append(new KeyedCodec<>("Hidden", Codec.BOOLEAN, false), (s, v) -> s.hidden = v, s -> s.hidden)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True shows a '?' in its place until the player has obtained it once, then the "
                        + "item for good.").add()
                .append(new KeyedCodec<>("SourceKey", Codec.STRING, false),
                        (s, v) -> s.sourceKey = v, s -> s.sourceKey)
                .documentation("Where the item comes from, as a localization key, shown in its tooltip once it "
                        + "is shown.").add()
                .build();

        public Slot() {
        }

        /** The item id, trimmed, or null when blank. */
        @Nullable
        public String item() {
            return AlmanacHeroAsset.blankToNull(item);
        }

        /** Whether the slot hides its item until the player has obtained it once; false when unauthored. */
        public boolean hidden() {
            return hidden != null && hidden;
        }

        /** The key of the line saying where the item comes from, or null. */
        @Nullable
        public String sourceKey() {
            return AlmanacHeroAsset.blankToNull(sourceKey);
        }
    }
}
