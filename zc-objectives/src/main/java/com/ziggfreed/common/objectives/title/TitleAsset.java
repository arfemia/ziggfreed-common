package com.ziggfreed.common.objectives.title;

import java.util.Locale;

import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * One TITLE a player can earn and show beside their name, at
 * {@code Server/ZiggfreedCommon/Titles/<Id>.json}. The FILE NAME is the title id, lower-cased; a
 * {@code Title} reward and {@code /zigtitle grant} name it.
 *
 * <pre>{@code
 * { "Order": 10,
 *   "Text": { "TitleKey": "title.example_title.name", "FlavorKey": "title.example_title.flavor" } }
 * }</pre>
 *
 * <p>{@code Enabled: false} hides a title everywhere (the picker, menus and leaderboards) without
 * taking it from anybody: a player who earned it keeps it and sees it again once it is back on. The
 * owner layer is {@code mods/ziggfreedcommon/titles.json}. {@code Text} names and describes it; with
 * nothing authored, {@code title.<id>.name} and {@code title.<id>.flavor} from any loaded lang file
 * do, and {@code title.<id>.display} (its {@code {0}} is the player's name) places it around a name.
 */
public final class TitleAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TitleAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Titles";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private ContentTextAsset text;
    @Nullable private Integer order;

    public static final AssetBuilderCodec<String, TitleAsset> CODEC = AssetBuilderCodec.builder(
                    TitleAsset.class,
                    TitleAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether the title is on offer on this server; unauthored means true. False hides "
                    + "it from the picker, menus and leaderboards without taking it from anybody: a player "
                    + "who earned it keeps it, and it shows again once it is back on.")
            .add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the title is called (TitleKey) and how it was earned (FlavorKey), as "
                    + "localization keys. Unauthored, title.<id>.name and title.<id>.flavor from any loaded "
                    + "lang file name and describe it, and a title nothing names reads as its id spelled "
                    + "out. Where it sits around a player's name is title.<id>.display, whose {0} is the "
                    + "name; without one the title follows the name after a comma.")
            .add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .documentation("Lower sorts first in the title picker; unauthored means 0, and titles that tie "
                    + "sort by id. Leave gaps (10, 20, 30) so a later title can slot between two.")
            .add()
            .build();

    public TitleAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Whether the title is on offer; unauthored means true. */
    public boolean enabled() {
        return enabled == null || enabled;
    }

    /** Its picker order; unauthored means 0. */
    public int order() {
        return order == null ? 0 : order;
    }

    /** The authored text group, or null when the file wrote none. */
    @Nullable
    public ContentTextAsset text() {
        return text;
    }
}
