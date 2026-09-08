package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.ui.hud.HudPosition;

/**
 * The panel the bars are drawn on: whether it is on, where it sits, how many bars it shows at once.
 * The FILE NAME is the panel's id, and the panel every bar is drawn on is the one called
 * {@value #DEFAULT_ID}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/HudBarPanels/Default.json}. This library ships that
 * file with the panel on, at the top-left under the other left-column overlays, showing four bars;
 * a pack or a server owner's {@code mods/ziggfreedcommon/hud-bar-panels.json} restates only the
 * leaves it wants different:
 * <pre>{@code
 * // mods/ziggfreedcommon/hud-bar-panels.json
 * { "Default": { "Position": { "Preset": "BottomLeft", "OffsetY": 220 } } }
 * }</pre>
 *
 * <p>{@code Position} is the one knob that moves the panel, through the shared {@link HudPosition}
 * presets every HUD in this family reads, so an owner reconciles it with whatever else sits in that
 * corner without touching code.
 */
public final class HudBarPanelAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, HudBarPanelAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/HudBarPanels";

    /** The id of the one panel the bars are drawn on (the file is {@code Default.json}; ids fold lower-case). */
    public static final String DEFAULT_ID = "default";

    /**
     * Where the panel sits when nothing says otherwise: the left column, under the mob inspector
     * overlay a companion draws at the top-left (which ends near y 342).
     */
    public static final HudPosition DEFAULT_POSITION =
            new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.LEFT, 16, 216);

    /** The preset name {@link #DEFAULT_POSITION} answers to. */
    static final String DEFAULT_PRESET = "TopLeft";

    /** The most bars the document can draw at once; MaxVisible is held to it. */
    public static final int MAX_SLOTS = 4;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private Position position;
    @Nullable private Integer maxVisible;

    public static final AssetBuilderCodec<String, HudBarPanelAsset> CODEC = AssetBuilderCodec.builder(
                    HudBarPanelAsset.class,
                    HudBarPanelAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether the panel draws at all. Set false to switch every bar off at once. "
                    + "Unauthored reads true.")
            .add()
            .appendInherited(new KeyedCodec<>("Position", Position.CODEC, false),
                    (a, v) -> a.position = v, a -> a.position, (a, p) -> a.position = p.position)
            .documentation("Where the panel sits on screen: a corner preset plus pixel offsets from the "
                    + "edges that preset pins. Unauthored puts it at the top-left, under the left-column "
                    + "overlays other mods draw there.")
            .add()
            .appendInherited(new KeyedCodec<>("MaxVisible", Codec.INTEGER, false),
                    (a, v) -> a.maxVisible = v, a -> a.maxVisible, (a, p) -> a.maxVisible = p.maxVisible)
            .metadata(EditorSchema.defaultValue(MAX_SLOTS))
            .documentation("How many bars show at once, from 1 to " + MAX_SLOTS + "; when more are moving, "
                    + "the most recently moved ones are drawn. Unauthored reads " + MAX_SLOTS + ".")
            .add()
            .build();

    public HudBarPanelAsset() {
    }

    /** A panel with every leaf unauthored: on, at {@link #DEFAULT_POSITION}, {@value #MAX_SLOTS} bars. */
    @Nonnull
    public static HudBarPanelAsset defaults() {
        HudBarPanelAsset asset = new HudBarPanelAsset();
        asset.id = DEFAULT_ID;
        return asset;
    }

    @Override
    public String getId() {
        return id;
    }

    /** Whether the panel draws; true unless the file says otherwise. */
    public boolean enabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    /**
     * Where the panel sits: the authored preset and offsets, each leaf falling back to
     * {@link #DEFAULT_POSITION} on its own, and the whole default for a preset nothing recognises.
     */
    @Nonnull
    public HudPosition position() {
        Position authored = position;
        if (authored == null) {
            return DEFAULT_POSITION;
        }
        int offsetX = authored.offsetX != null ? authored.offsetX : DEFAULT_POSITION.getOffsetX();
        int offsetY = authored.offsetY != null ? authored.offsetY : DEFAULT_POSITION.getOffsetY();
        String preset = authored.preset == null || authored.preset.isBlank() ? DEFAULT_PRESET : authored.preset;
        HudPosition parsed = HudPosition.parse(preset, offsetX, offsetY);
        return parsed != null ? parsed : DEFAULT_POSITION;
    }

    /** How many bars show at once, held between 1 and {@value #MAX_SLOTS}; {@value #MAX_SLOTS} when unauthored. */
    public int maxVisible() {
        if (maxVisible == null) {
            return MAX_SLOTS;
        }
        return Math.max(1, Math.min(maxVisible, MAX_SLOTS));
    }

    /** The corner preset and the two offsets, one group so they read and inherit together. */
    public static final class Position {

        @Nullable protected String preset;
        @Nullable protected Integer offsetX;
        @Nullable protected Integer offsetY;

        public static final BuilderCodec<Position> CODEC = BuilderCodec.builder(Position.class, Position::new)
                .appendInherited(new KeyedCodec<>("Preset", Codec.STRING, false),
                        (o, v) -> o.preset = v, o -> o.preset, (o, p) -> o.preset = p.preset)
                .metadata(EditorSchema.oneOf("TopLeft", "TopCenter", "TopRight", "CenterLeft", "Center",
                        "CenterRight", "BottomLeft", "BottomCenter", "BottomRight"))
                .metadata(EditorSchema.defaultValue(DEFAULT_PRESET))
                .documentation("Which corner or edge the panel hangs from. Unauthored reads TopLeft.")
                .add()
                .appendInherited(new KeyedCodec<>("OffsetX", Codec.INTEGER, false),
                        (o, v) -> o.offsetX = v, o -> o.offsetX, (o, p) -> o.offsetX = p.offsetX)
                .metadata(EditorSchema.defaultValue(DEFAULT_POSITION.getOffsetX()))
                .documentation("Pixels in from the pinned left or right edge; for a centred preset, a "
                        + "nudge off centre. Unauthored reads " + DEFAULT_POSITION.getOffsetX() + ".")
                .add()
                .appendInherited(new KeyedCodec<>("OffsetY", Codec.INTEGER, false),
                        (o, v) -> o.offsetY = v, o -> o.offsetY, (o, p) -> o.offsetY = p.offsetY)
                .metadata(EditorSchema.defaultValue(DEFAULT_POSITION.getOffsetY()))
                .documentation("Pixels down from a top preset, up from a bottom one. Unauthored reads "
                        + DEFAULT_POSITION.getOffsetY() + ", which clears the mob inspector overlay.")
                .add()
                .build();

        public Position() {
        }
    }
}
