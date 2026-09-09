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
 * A panel the bars are drawn on: whether it is on, where it sits, how many bars it shows at once and
 * how they spread out. The FILE NAME is the panel's id, and which panel a row lands on is decided by
 * the mod reporting the movement, by calling that panel's entry point on {@link HudBars}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/HudBarPanels/<id>.json}. This library ships two:
 * {@code Default.json}, the tall stack in the left column, and {@code Grid.json}, the wide
 * few-columns block in the top-right. A pack or a server owner's
 * {@code mods/ziggfreedcommon/hud-bar-panels.json} restates only the leaves it wants different:
 * <pre>{@code
 * // mods/ziggfreedcommon/hud-bar-panels.json
 * { "Default": { "Position": { "Preset": "BottomLeft", "OffsetY": 220 } } }
 * }</pre>
 *
 * <p>{@code Position} is the one knob that moves a panel, through the shared {@link HudPosition}
 * presets every HUD in this family reads, so an owner reconciles it with whatever else sits in that
 * corner without touching code. {@code Columns} and {@code RowsPerColumn} decide how a panel's rows
 * spread out as more of them move at once; each is held to what that panel's own layout declares.
 */
public final class HudBarPanelAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, HudBarPanelAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/HudBarPanels";

    /** The id of the stacked panel in the left column (the file is {@code Default.json}; ids fold lower-case). */
    public static final String DEFAULT_ID = "default";

    /** The id of the wide few-columns panel in the top-right (the file is {@code Grid.json}). */
    public static final String GRID_ID = "grid";

    /** Where the grid panel sits when nothing says otherwise: the top-right, above the quest tracker. */
    public static final HudPosition GRID_POSITION =
            new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.RIGHT, 24, 10);

    /**
     * Where the panel sits when nothing says otherwise: the left column, under the mob inspector
     * overlay a companion draws at the top-left (which ends near y 342).
     */
    public static final HudPosition DEFAULT_POSITION =
            new HudPosition(HudPosition.AnchorEdge.TOP, HudPosition.HorizontalEdge.LEFT, 16, 216);

    /** The preset name {@link #DEFAULT_POSITION} answers to. */
    static final String DEFAULT_PRESET = "TopLeft";

    /**
     * How often a panel repaints at most, when nothing is authored. The paint is a leading-edge
     * push with a trailing flush, so a value moving inside the window still lands at the window's
     * end rather than waiting for the next movement.
     */
    public static final long DEFAULT_REPAINT_MS = 250L;

    /** How many columns a panel spreads its rows across when nothing is authored: one. */
    public static final int DEFAULT_COLUMNS = 1;

    /** How many rows a column takes before the next one opens, when nothing is authored. */
    public static final int DEFAULT_ROWS_PER_COLUMN = 1;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private Position position;
    @Nullable private Integer maxVisible;
    @Nullable private Integer columns;
    @Nullable private Integer rowsPerColumn;
    @Nullable private Long repaintMs;

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
            .documentation("How many bars show at once; when more are moving, the most recently moved "
                    + "ones are drawn. Held to however many the panel's own layout declares, which is "
                    + "also what an unauthored value reads.")
            .add()
            .appendInherited(new KeyedCodec<>("Columns", Codec.INTEGER, false),
                    (a, v) -> a.columns = v, a -> a.columns, (a, p) -> a.columns = p.columns)
            .metadata(EditorSchema.defaultValue(DEFAULT_COLUMNS))
            .documentation("The most columns the rows spread across. One stacks them in a single "
                    + "column. A panel opens a new column only once RowsPerColumn is exceeded, and "
                    + "never more than its own layout declares.")
            .add()
            .appendInherited(new KeyedCodec<>("RowsPerColumn", Codec.INTEGER, false),
                    (a, v) -> a.rowsPerColumn = v, a -> a.rowsPerColumn,
                    (a, p) -> a.rowsPerColumn = p.rowsPerColumn)
            .metadata(EditorSchema.defaultValue(DEFAULT_ROWS_PER_COLUMN))
            .documentation("How many rows one column takes before another opens. Set it high to keep "
                    + "a tall single column until the panel is genuinely busy; set it to 1 to spread "
                    + "rows sideways as soon as there is a second one.")
            .add()
            .appendInherited(new KeyedCodec<>("RepaintMs", Codec.LONG, false),
                    (a, v) -> a.repaintMs = v, a -> a.repaintMs, (a, p) -> a.repaintMs = p.repaintMs)
            .metadata(EditorSchema.defaultValue(DEFAULT_REPAINT_MS))
            .documentation("How often the panel redraws at most, in milliseconds. A movement redraws "
                    + "immediately when the previous redraw is already this old, and one arriving "
                    + "sooner is drawn at the window's end instead, so the number on screen is never "
                    + "waiting on a later movement to catch up. Lower is livelier and costs more "
                    + "packets.")
            .add()
            .build();

    public HudBarPanelAsset() {
    }

    /** A panel with every leaf unauthored: on, at {@link #DEFAULT_POSITION}, every slot its document has. */
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

    /** Where the panel sits, falling back leaf by leaf to {@link #DEFAULT_POSITION}. */
    @Nonnull
    public HudPosition position() {
        return position(DEFAULT_POSITION);
    }

    /**
     * Where the panel sits: the authored preset and offsets, each leaf falling back to
     * {@code fallback} on its own, and the whole of {@code fallback} for a preset nothing
     * recognises. The fallback is the caller's because each panel sits somewhere different when
     * nothing is authored, and only the HUD drawing it knows which panel it is.
     */
    @Nonnull
    public HudPosition position(@Nonnull HudPosition fallback) {
        Position authored = position;
        if (authored == null) {
            return fallback;
        }
        int offsetX = authored.offsetX != null ? authored.offsetX : fallback.getOffsetX();
        int offsetY = authored.offsetY != null ? authored.offsetY : fallback.getOffsetY();
        if (authored.preset == null || authored.preset.isBlank()) {
            // Only the offsets were authored: keep the fallback's own corner and move it.
            return new HudPosition(fallback.getAnchorEdge(), fallback.getHorizontalEdge(), offsetX, offsetY);
        }
        HudPosition parsed = HudPosition.parse(authored.preset, offsetX, offsetY);
        return parsed != null ? parsed : fallback;
    }

    /**
     * How many bars show at once on a panel whose layout declares {@code documentSlots} of them:
     * the authored count held between 1 and that, and all of them when nothing is authored. The
     * ceiling is the caller's because it belongs to a document, not to this file: two panels drawn
     * from the same asset type declare different numbers of slots, and neither can paint one its
     * own layout does not have.
     */
    public int maxVisible(int documentSlots) {
        int declared = Math.max(1, documentSlots);
        if (maxVisible == null) {
            return declared;
        }
        return Math.max(1, Math.min(maxVisible, declared));
    }

    /**
     * The most columns the rows spread across, held between 1 and {@code documentColumns};
     * {@value #DEFAULT_COLUMNS} when unauthored. Like {@link #maxVisible}, the ceiling comes from
     * the document, which declares its columns up front.
     */
    public int columns(int documentColumns) {
        int declared = Math.max(1, documentColumns);
        int authored = columns == null ? DEFAULT_COLUMNS : columns;
        return Math.max(1, Math.min(authored, declared));
    }

    /** How many rows a column takes before another opens; {@value #DEFAULT_ROWS_PER_COLUMN} when unauthored. */
    public int rowsPerColumn() {
        if (rowsPerColumn == null) {
            return DEFAULT_ROWS_PER_COLUMN;
        }
        return Math.max(1, rowsPerColumn);
    }

    /** How often the panel redraws at most; {@value #DEFAULT_REPAINT_MS} ms when unauthored or nonsense. */
    public long repaintMs() {
        return repaintMs != null && repaintMs > 0 ? repaintMs : DEFAULT_REPAINT_MS;
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
