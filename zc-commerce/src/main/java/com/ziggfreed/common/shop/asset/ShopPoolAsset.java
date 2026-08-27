package com.ziggfreed.common.shop.asset;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.commerce.asset.CommerceEditorDataSets;
import com.ziggfreed.common.commerce.asset.RerollAsset;
import com.ziggfreed.common.commerce.asset.RotationAsset;
import com.ziggfreed.common.commerce.asset.SelectionAsset;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.asset.ContentTextAsset;

/**
 * One ROTATING SHELF inside a storefront, at
 * {@code Server/ZiggfreedCommon/ShopPools/<ns>/<Id>.json}. The FILE NAME is the pool id.
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "ui.shop.category.featured" },
 *   "Shop": "General",
 *   "Order": 0,
 *   "Rotation": { "Period": "Daily" },
 *   "Selection": { "Type": "Weighted_Random" },
 *   "Reroll": { "Cost": { "Currencies": { "Bounty_Token": 40 } }, "MaxPerPeriod": 2 } }
 * }</pre>
 *
 * <p>A shelf shows a few of the offers that name it rather than all of them, and swaps them on a
 * schedule. Which offers are eligible is decided by each offer's own {@code Pool} group, so adding
 * something to the rotation never means editing the shelf.
 *
 * <p><b>The set is worked out from the clock, not remembered.</b> Every player sees the same shelf
 * for the same period, a restart changes nothing, and nothing has to be stored anywhere. A player's
 * own rerolls layer on top of that shared draw.
 *
 * <p><b>{@code Slots} shape the shelf</b> - one lesser, one greater, one master - where an unslotted
 * shelf simply draws from everything the pool holds and may well show three of a kind.
 *
 * <p>To retune a shelf somebody else shipped, override the file by id (a same-named file in a later
 * pack or in the owner layer), or ship your own with {@code Parent} set to theirs.
 */
public final class ShopPoolAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, ShopPoolAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/ShopPools";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private ContentTextAsset text;
    @Nullable private String shop;
    @Nullable private Integer order;
    @Nullable private RotationAsset rotation;
    @Nullable private SelectionAsset selection;
    @Nullable private PoolSlotAsset[] slots;
    @Nullable private RerollAsset reroll;
    @Nullable private Map<String, JsonElement> meta;

    public static final AssetBuilderCodec<String, ShopPoolAsset> CODEC = AssetBuilderCodec.builder(
                    ShopPoolAsset.class,
                    ShopPoolAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op: the id comes from the filename */ },
                    a -> a.id)
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether the shelf appears at all; unauthored means true. Setting false takes the "
                    + "whole rotation off the page without touching the offers that name it.")
            .add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the player reads as the shelf's heading, as localization keys.")
            .add()
            .appendInherited(new KeyedCodec<>("Shop", Codec.STRING, false),
                    (a, v) -> a.shop = v, a -> a.shop, (a, p) -> a.shop = p.shop)
            .metadata(new UIEditor(new UIEditor.Dropdown(CommerceEditorDataSets.SHOPS)))
            .documentation("The storefront this shelf sits in, by id. A shelf naming a storefront nobody authored "
                    + "never appears anywhere, which the audit says at load.")
            .add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .documentation("Lower sorts first among the shelves of one storefront; unauthored means 0.")
            .add()
            .appendInherited(new KeyedCodec<>("Rotation", RotationAsset.CODEC, false),
                    (a, v) -> a.rotation = v, a -> a.rotation, (a, p) -> a.rotation = p.rotation)
            .documentation("How often the shelf turns over. Unauthored means it never does, so the same draw "
                    + "stands for good; author a cadence for anything meant to bring players back.")
            .add()
            .appendInherited(new KeyedCodec<>("Selection", SelectionAsset.CODEC, false),
                    (a, v) -> a.selection = v, a -> a.selection, (a, p) -> a.selection = p.selection)
            .documentation("Which of the eligible offers the shelf shows. Unauthored draws seeded picks that "
                    + "honour each offer's weight.")
            .add()
            .appendInherited(new KeyedCodec<>("Slots",
                            new ArrayCodec<>(PoolSlotAsset.CODEC, PoolSlotAsset[]::new), false),
                    (a, v) -> a.slots = v, a -> a.slots, (a, p) -> a.slots = p.slots)
            .documentation("The shape of one rotation, slot by slot. Unauthored draws from everything in the "
                    + "pool without shaping it, which can show three of the same tier. This is ONE leaf: "
                    + "authoring it replaces an inherited list whole.")
            .add()
            .appendInherited(new KeyedCodec<>("Reroll", RerollAsset.CODEC, false),
                    (a, v) -> a.reroll = v, a -> a.reroll, (a, p) -> a.reroll = p.reroll)
            .documentation("What it costs a player to swap one slot for another, and how often they may. "
                    + "Unauthored means the shelf stands as drawn until it turns over.")
            .add()
            .appendInherited(new KeyedCodec<>(ContentMeta.KEY, ContentMeta.CODEC, false),
                    (a, v) -> a.meta = v, a -> a.meta, (a, p) -> a.meta = p.meta)
            .documentation(ContentMeta.DOCUMENTATION)
            .add()
            .build();

    public ShopPoolAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Does the shelf appear? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    /** The storefront this shelf sits in, lower-cased, or null when it names none. */
    @Nullable
    public String getShop() {
        return shop == null || shop.isBlank() ? null : shop.trim().toLowerCase(Locale.ROOT);
    }

    /** Where this shelf sorts among the others; 0 when unauthored. */
    public int order() {
        return order == null ? 0 : order;
    }

    @Nullable
    public RotationAsset getRotation() {
        return rotation;
    }

    @Nullable
    public SelectionAsset getSelection() {
        return selection;
    }

    /** The shape of one rotation, in authored order; empty when the shelf is unslotted. */
    @Nonnull
    public PoolSlotAsset[] slotsOrEmpty() {
        return slots == null ? new PoolSlotAsset[0] : slots;
    }

    @Nullable
    public RerollAsset getReroll() {
        return reroll;
    }

    /** The per-namespace extra facts, exactly as authored; empty when the file carried none. */
    @Nonnull
    public Map<String, JsonElement> metaOrEmpty() {
        return ContentMeta.orEmpty(meta);
    }
}
