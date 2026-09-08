package com.ziggfreed.common.ui.hud.bar;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.icon.IconSpec;

/**
 * One bar on the shared progress-bar HUD: what it is called, what it looks like, and which value
 * fills it. The FILE NAME is the bar's id.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/HudBars/<id>.json} (this codec IS the schema):
 * <pre>{@code
 * // Server/ZiggfreedCommon/HudBars/Mymod_Wood.json
 * { "Source":   "mymod:wood",
 *   "LabelKey": "mymod.wood.name",
 *   "Icon":     { "ItemId": "Tool_Hatchet_Crude" },
 *   "Color":    "#6fbf73",
 *   "Order":    20,
 *   "LingerMs": 5000 }
 * }</pre>
 *
 * <p><b>{@code Source} is the whole contract.</b> It is an opaque {@code namespace:local} id this
 * library never interprets: the mod that owns the namespace registers a {@link HudBarSource} under
 * it, and whenever that mod says the value moved ({@link HudBars#moved}) the panel asks that source
 * for the value's current reading and its ceiling. This file says nothing about what the value IS,
 * which is what lets a craft's progress, a work cycle's progress and a fight's health share one
 * panel with no code in common.
 *
 * <p>A bar is drawn only while its value is moving: it comes up on the first change, its first
 * line carries the label and the gain accumulated since it came up, its second line is the fill,
 * and {@code LingerMs} after the last change it goes away again. When more bars are live than the
 * panel shows, the most recently moved ones are drawn, in {@code Order}.
 *
 * <p>Every leaf is optional and inherits under a root {@code "Parent": "<bar id>"} key, so a
 * pack, or a server owner's {@code mods/ziggfreedcommon/hud-bars.json} entry, restates only the
 * leaves it wants different and keeps the rest.
 */
public final class HudBarAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, HudBarAsset>> {

    /** Where these are authored. One folder, one file per bar, the file name being the id. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/HudBars";

    /** The fill colour a bar that names none is drawn in. */
    public static final String DEFAULT_COLOR = "#7fb2e0";

    /** Where a bar that names no Order sorts: after every bar that did. */
    public static final int DEFAULT_ORDER = 1000;

    /** How long a bar stays up after its value last moved, when the file says nothing. */
    public static final long DEFAULT_LINGER_MS = 5000L;

    /** The separator between the namespace and the local part of a Source id. */
    static final char SOURCE_SEPARATOR = ':';

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
            .documentation("Which value fills this bar, as a namespaced id the mod that owns the value "
                    + "answers for, e.g. \"mymod:wood\": the part before the colon names that mod, the "
                    + "part after it is the mod's own name for the value. This panel never interprets "
                    + "it. Without a Source the bar never comes up.")
            .add()
            .appendInherited(new KeyedCodec<>("LabelKey", Codec.STRING, false),
                    (a, v) -> a.labelKey = v, a -> a.labelKey, (a, p) -> a.labelKey = p.labelKey)
            .documentation("The localization key of the bar's name, resolved on each player's own client "
                    + "in their language. A full registered id (\"mymod.wood.name\") passes through as "
                    + "written; a key written without its lang file's namespace is looked up under "
                    + "whichever loaded namespace ships it. Without one the bar shows its own id.")
            .add()
            .appendInherited(new KeyedCodec<>("Icon", IconSpec.CODEC, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .documentation("A small picture beside the name: an item's generated icon or a texture path. "
                    + "Leave it out for a name alone.")
            .add()
            .appendInherited(new KeyedCodec<>("Color", Codec.STRING, false),
                    (a, v) -> a.color = v, a -> a.color, (a, p) -> a.color = p.color)
            .metadata(EditorSchema.defaultValue(DEFAULT_COLOR))
            .documentation("The fill colour as a six-digit hex, e.g. \"#6fbf73\". Unauthored reads "
                    + DEFAULT_COLOR + ".")
            .add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue(DEFAULT_ORDER))
            .documentation("Where this bar sits when several are up at once: lower numbers draw higher "
                    + "in the stack. Bars naming the same number sort by id. Unauthored reads "
                    + DEFAULT_ORDER + ", after every bar that named one.")
            .add()
            .appendInherited(new KeyedCodec<>("LingerMs", Codec.LONG, false),
                    (a, v) -> a.lingerMs = v, a -> a.lingerMs, (a, p) -> a.lingerMs = p.lingerMs)
            .metadata(EditorSchema.defaultValue(DEFAULT_LINGER_MS))
            .documentation("How long the bar stays on screen after its value last moved, in "
                    + "milliseconds; every further move starts the wait over. Unauthored reads "
                    + DEFAULT_LINGER_MS + ".")
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to keep this bar off the panel without deleting the file; the way "
                    + "a server owner switches one bar off from the owner layer. Unauthored reads true.")
            .add()
            .build();

    public HudBarAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The full {@code namespace:local} source id, trimmed; null when the file names none. */
    @Nullable
    public String source() {
        return source == null || source.isBlank() ? null : source.trim();
    }

    /**
     * The mod the source belongs to: the part of {@link #source()} before the colon, lower-cased
     * so a registration and an authored id can never disagree by case. Null when there is no source
     * or it carries no colon.
     */
    @Nullable
    public String sourceNamespace() {
        return namespaceOf(source());
    }

    /** The owning mod's own name for the value: the part of {@link #source()} after the colon, or null. */
    @Nullable
    public String sourceLocalId() {
        return localIdOf(source());
    }

    /** The label's localization key, or null for a bar that shows its id. */
    @Nullable
    public String labelKey() {
        return labelKey == null || labelKey.isBlank() ? null : labelKey.trim();
    }

    /** The picture beside the name, or null for none. An authored group with both leaves blank reads as none. */
    @Nullable
    public IconSpec icon() {
        return icon == null || icon.isEmpty() ? null : icon;
    }

    /** The fill colour, {@value #DEFAULT_COLOR} when the file names none. */
    @Nonnull
    public String color() {
        return color == null || color.isBlank() ? DEFAULT_COLOR : color.trim();
    }

    /** The stack order, {@value #DEFAULT_ORDER} when the file names none. */
    public int order() {
        return order == null ? DEFAULT_ORDER : order;
    }

    /** How long the bar lingers after its last move; {@value #DEFAULT_LINGER_MS} when unauthored or not positive. */
    public long lingerMs() {
        return lingerMs == null || lingerMs <= 0 ? DEFAULT_LINGER_MS : lingerMs;
    }

    /** Whether the bar may come up at all; true unless the file says otherwise. */
    public boolean enabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    // ==================== the source id's two halves ====================

    /** The lower-cased namespace of a {@code namespace:local} id, or null when it has none. */
    @Nullable
    static String namespaceOf(@Nullable String sourceId) {
        if (sourceId == null) {
            return null;
        }
        int at = sourceId.indexOf(SOURCE_SEPARATOR);
        if (at <= 0) {
            return null;
        }
        return sourceId.substring(0, at).trim().toLowerCase(Locale.ROOT);
    }

    /** The local part of a {@code namespace:local} id, or null when it has no namespace or nothing after it. */
    @Nullable
    static String localIdOf(@Nullable String sourceId) {
        if (sourceId == null) {
            return null;
        }
        int at = sourceId.indexOf(SOURCE_SEPARATOR);
        if (at <= 0 || at == sourceId.length() - 1) {
            return null;
        }
        String local = sourceId.substring(at + 1).trim();
        return local.isEmpty() ? null : local;
    }
}
