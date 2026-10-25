package com.ziggfreed.common.track.pick;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.JsonTreeCodec;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * One option a choice may offer, at {@code Server/ZiggfreedCommon/TrackPickOptions/<Owner>/<Id>.json}, shared by
 * every pick whose groups name it. The FILE NAME is the id. Its {@code Grants} are a template: each {@code {Name}} in
 * them is filled from the naming pick's {@code Vars} (a group's own over the pick's) by the library's one
 * substitution contract (zc-progression's {@code GeneratorCore}), so one option file serves every weapon skill:
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "yourmod.option.edge" },
 *   "From": 10,
 *   "Grants": [ { "Kind": "Stat", "Stat": "DamageBonus_{Cause}", "Add": "{Step}" } ] }
 * }</pre>
 *
 * <p>{@code From} and {@code To} are the levels it is offered at: a slot offers every option of its group open at
 * its level. Every leaf inherits through {@code Parent}; a server owner retunes an option in
 * {@code mods/ziggfreedcommon/track-pick-options.json}.
 */
public final class PickOptionAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, PickOptionAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackPickOptions";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean enabled;
    @Nullable protected ContentTextAsset text;
    @Nullable protected String icon;
    @Nullable protected Integer from;
    @Nullable protected Integer to;
    @Nullable protected JsonElement grants;

    public static final AssetBuilderCodec<String, PickOptionAsset> CODEC = AssetBuilderCodec.builder(
                    PickOptionAsset.class,
                    PickOptionAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether this option exists on this server; unauthored means true. False keeps it out of "
                    + "every offer, and a player who chose it keeps the choice but not its grants.").add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the option is called (TitleKey) and what it does in plain words (FlavorKey), as "
                    + "localization keys in your own lang file. Without a TitleKey it is named by its id.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture stands for the option.").add()
            .appendInherited(new KeyedCodec<>("From", Codec.INTEGER, false),
                    (a, v) -> a.from = v, a -> a.from, (a, p) -> a.from = p.from)
            .metadata(EditorSchema.defaultValue(1L))
            .documentation("The first level this option may be offered at. 1 when unauthored.").add()
            .appendInherited(new KeyedCodec<>("To", Codec.INTEGER, false),
                    (a, v) -> a.to = v, a -> a.to, (a, p) -> a.to = p.to)
            .documentation("The last level this option may be offered at. Leave it out for no end.").add()
            .appendInherited(new KeyedCodec<>("Grants", JsonTreeCodec.array(), false),
                    (a, v) -> a.grants = v, a -> a.grants, (a, p) -> a.grants = p.grants)
            .documentation("What taking this option grants, each time it is taken: grant entries with their Kind "
                    + "(Stat raises a native stat), in which {Name} is filled from the naming pick's Vars. A value "
                    + "that is exactly one {Name} takes that Var's own type, so \"Add\": \"{Step}\" reads as a "
                    + "number. A {Name} no Var fills keeps the option out of that pick, reported.").add()
            .build();

    public PickOptionAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    /** The localization key the option is named by, or null. */
    @Nullable
    public String titleKey() {
        return text == null ? null : TrackPickAsset.blankToNull(text.getTitleKey());
    }

    @Nullable
    public String icon() {
        return TrackPickAsset.blankToNull(icon);
    }

    /** The first level it is offered at, 1 or more. */
    public int from() {
        return from == null ? 1 : Math.max(1, from);
    }

    /** The last level it is offered at; {@link Integer#MAX_VALUE} for no end. */
    public int to() {
        return to == null ? Integer.MAX_VALUE : to;
    }

    /** Is it offered at {@code level}: between its {@code From} and its {@code To}? */
    public boolean openAt(int level) {
        return level >= from() && level <= to();
    }

    /** The {@code Grants} template as written, or null. */
    @Nullable
    public JsonElement grantsTemplate() {
        return grants;
    }
}
