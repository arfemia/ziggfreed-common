package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.icon.IconSpec;

/**
 * An OPTIONAL override for one row on the shared progress-bar panel: what to call it, what to
 * draw beside it, what colour to fill it, where it sits, how long it lingers, or whether it shows
 * at all. The FILE NAME is the override's id; {@code Source} names the row it retunes.
 *
 * <p>No row needs one of these to exist. A row is created the moment the mod that owns a value
 * reports it moved ({@link HudBars#moved}, {@link HudBars#itemMoved}), and that mod supplies the
 * row's whole look along with the movement ({@link HudBarDisplay}). A file here is for the server
 * owner or pack author who wants one row different: every leaf is optional, an authored leaf wins
 * over the reporting mod's, and an unauthored leaf changes nothing. This library ships none.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/HudBars/<id>.json} (this codec IS the schema):
 * <pre>{@code
 * // Server/ZiggfreedCommon/HudBars/Wood.json
 * { "Source":   "WOOD",
 *   "Color":    "#6fbf73",
 *   "Order":    20,
 *   "LingerMs": 8000 }
 * }</pre>
 *
 * <p>{@code Source} is the row's id exactly as the owning mod moves it: the id of the thing the
 * row measures for a row that draws a fill, or {@code item:<ItemId>} for the row an item's output
 * lands on. This library never interprets it beyond matching it, case-insensitively, against the
 * id a move names. Every leaf inherits under a root
 * {@code "Parent": "<override id>"} key, and a server owner's {@code mods/ziggfreedcommon/hud-bars.json}
 * entry restates only the leaves it wants different over whatever a pack authored under that id.
 */
public final class HudBarAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, HudBarAsset>> {

    /** Where these are authored. One folder, one file per override, the file name being the id. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/HudBars";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String source;
    @Nullable private String labelKey;
    @Nullable private IconSpec icon;
    @Nullable private String color;
    @Nullable private Integer order;
    @Nullable private Long lingerMs;
    @Nullable private Boolean enabled;

    public static final AssetBuilderCodec<String, HudBarAsset> CODEC = AssetBuilderCodec.builder(
                    HudBarAsset.class,
                    HudBarAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Source", Codec.STRING, false),
                    (a, v) -> a.source = v, a -> a.source, (a, p) -> a.source = p.source)
            .documentation("Which row this file retunes, by the id the mod that owns it reports it "
                    + "under: the id of the thing the row measures, spelled exactly as that mod spells "
                    + "it and matched ignoring case, or \"item:<ItemId>\" for the row an item's output is "
                    + "counted on. Without a Source this file applies to nothing.")
            .add()
            .appendInherited(new KeyedCodec<>("LabelKey", Codec.STRING, false),
                    (a, v) -> a.labelKey = v, a -> a.labelKey, (a, p) -> a.labelKey = p.labelKey)
            .documentation("A localization key to name the row by, resolved on each player's own client "
                    + "in their language, in place of the name the owning mod gives it. A full registered "
                    + "id (\"mymod.wood.name\") passes through as written; a key written without its lang "
                    + "file's namespace is looked up under whichever loaded namespace ships it. Leave it "
                    + "out to keep the mod's own name.")
            .add()
            .appendInherited(new KeyedCodec<>("Icon", IconSpec.CODEC, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .documentation("A small picture beside the name, in place of the one the owning mod draws: an "
                    + "item's generated icon or a texture path. Leave it out to keep the mod's own.")
            .add()
            .appendInherited(new KeyedCodec<>("Color", Codec.STRING, false),
                    (a, v) -> a.color = v, a -> a.color, (a, p) -> a.color = p.color)
            .metadata(EditorSchema.defaultValue(HudBarLook.DEFAULT_COLOR))
            .documentation("The fill colour as a six-digit hex, e.g. \"#6fbf73\", in place of the colour the "
                    + "owning mod chose. A row that draws no fill ignores it. Left out, the mod's colour "
                    + "stands, and a mod that named none reads " + HudBarLook.DEFAULT_COLOR + ".")
            .add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue(HudBarLook.DEFAULT_ORDER))
            .documentation("Where this row sits when several are up at once: lower numbers draw higher in "
                    + "the stack, and rows that draw a fill always sit above rows that do not. Rows naming "
                    + "the same number sort by id. Left out, the owning mod's order stands, and a mod that "
                    + "named none reads " + HudBarLook.DEFAULT_ORDER + ", after every row that did.")
            .add()
            .appendInherited(new KeyedCodec<>("LingerMs", Codec.LONG, false),
                    (a, v) -> a.lingerMs = v, a -> a.lingerMs, (a, p) -> a.lingerMs = p.lingerMs)
            .metadata(EditorSchema.defaultValue(HudBarLook.DEFAULT_LINGER_MS))
            .documentation("How long the row stays on screen after its value last moved, in milliseconds; "
                    + "every further move starts the wait over. Left out, the owning mod's wait stands, "
                    + "and a mod that named none reads " + HudBarLook.DEFAULT_LINGER_MS + ".")
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to keep this row off the panel altogether; the way a server owner "
                    + "switches one row off from the owner layer. Unauthored reads true.")
            .add()
            .build();

    public HudBarAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The id of the row this file retunes, trimmed; null when the file names none. */
    @Nullable
    public String source() {
        return source == null || source.isBlank() ? null : source.trim();
    }

    /** The label's localization key, or null to keep the owning mod's name. */
    @Nullable
    public String labelKey() {
        return labelKey == null || labelKey.isBlank() ? null : labelKey.trim();
    }

    /** The picture beside the name, or null to keep the owning mod's. An authored group with both leaves blank reads as none. */
    @Nullable
    public IconSpec icon() {
        return icon == null || icon.isEmpty() ? null : icon;
    }

    /** The fill colour, or null to keep the owning mod's. */
    @Nullable
    public String color() {
        return color == null || color.isBlank() ? null : color.trim();
    }

    /** The stack order, or null to keep the owning mod's. */
    @Nullable
    public Integer order() {
        return order;
    }

    /** How long the row lingers after its last move, or null (unauthored or not positive) to keep the owning mod's. */
    @Nullable
    public Long lingerMs() {
        return lingerMs == null || lingerMs <= 0 ? null : lingerMs;
    }

    /** Whether the row may come up at all; true unless the file says otherwise. */
    public boolean enabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    /**
     * This file's authored leaves as a display, ready to fold over the reporting mod's
     * ({@link HudBarDisplay#over}): the label key becomes a client-resolved message, and every
     * unauthored leaf stays null so it changes nothing.
     */
    @Nonnull
    public HudBarDisplay display() {
        String key = labelKey();
        return new HudBarDisplay(key != null ? ContentKeys.tr(key) : null, icon(), color(), order(), lingerMs(),
                null, null);
    }
}
