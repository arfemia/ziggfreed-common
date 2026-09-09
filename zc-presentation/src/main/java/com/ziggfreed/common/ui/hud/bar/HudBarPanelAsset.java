package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;

/**
 * A panel the bars are drawn on: whether it is on, what it is called, which spot it sits at, how
 * many bars it shows at once and how often it redraws. The FILE NAME is the panel's id, and which
 * panel a row lands on is decided by the mod reporting the movement, by calling that panel's entry
 * point on {@link HudBars}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/HudBarPanels/<id>.json}. This library ships two:
 * {@code Default.json}, the tall stack in the left column, and {@code Grid.json}, the wide block. A
 * pack or a server owner's {@code mods/ziggfreedcommon/hud-bar-panels.json} restates only the leaves
 * it wants different:
 * <pre>{@code
 * // mods/ziggfreedcommon/hud-bar-panels.json
 * { "Grid": { "Placement": "TopRight" },
 *   "Default": { "Position": { "OffsetY": 260 }, "MaxVisible": 6 } }
 * }</pre>
 *
 * <p><b>Where it sits is a {@code Placement}</b>, the id of a spot authored once at
 * {@code Server/ZiggfreedCommon/HudBarPlacements/} ({@link HudBarPlacementAsset}) that also says
 * how the rows spread there. The inline {@code Position}, {@code Columns} and {@code RowsPerColumn}
 * are optional restatements OVER that spot, for an owner who wants a nudge or a different spread
 * without authoring a spot of their own. A player may pick another offered spot for themselves in
 * the HUD settings, and their pick replaces the whole group. {@link HudBarPlacement#resolve} is the
 * one place that fold is worked out.
 */
public final class HudBarPanelAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, HudBarPanelAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/HudBarPanels";

    /** The id of the stacked panel in the left column (the file is {@code Default.json}; ids fold lower-case). */
    public static final String DEFAULT_ID = "default";

    /** The id of the wide few-columns panel (the file is {@code Grid.json}). */
    public static final String GRID_ID = "grid";

    /**
     * How often a panel repaints at most, when nothing is authored. The paint is a leading-edge
     * push with a trailing flush, so a value moving inside the window still lands at the window's
     * end rather than waiting for the next movement.
     */
    public static final long DEFAULT_REPAINT_MS = 250L;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private String labelKey;
    @Nullable private String placement;
    @Nullable private HudBarPosition position;
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
            .documentation("Whether the panel draws at all, for everyone. Set false to switch every bar "
                    + "on it off at once. Unauthored reads true. A player hides a panel for themselves "
                    + "in the HUD settings instead.")
            .add()
            .appendInherited(new KeyedCodec<>("LabelKey", Codec.STRING, false),
                    (a, v) -> a.labelKey = v, a -> a.labelKey, (a, p) -> a.labelKey = p.labelKey)
            .documentation("A localization key naming this panel in the HUD settings, resolved on each "
                    + "player's own client. Left out, the panel is listed by its id.")
            .add()
            .appendInherited(new KeyedCodec<>("Placement", Codec.STRING, false),
                    (a, v) -> a.placement = v, a -> a.placement, (a, p) -> a.placement = p.placement)
            .metadata(new UIEditor(new UIEditor.Dropdown(HudBarPlacementAsset.EDITOR_DATA_SET)))
            .documentation("The spot the panel sits at, by the id of a file under "
                    + "Server/ZiggfreedCommon/HudBarPlacements/. That file carries the corner, the "
                    + "offsets and how the rows spread there; the Position, Columns and RowsPerColumn "
                    + "below restate single leaves over it. Left out, or naming a spot that does not "
                    + "exist, the panel sits where its own layout says.")
            .add()
            .appendInherited(new KeyedCodec<>("Position", HudBarPosition.CODEC, false),
                    (a, v) -> a.position = v, a -> a.position, (a, p) -> a.position = p.position)
            .documentation("A corner preset and pixel offsets restated over the Placement's: state only "
                    + "the leaves you want different, and leave the whole group out to take the spot as "
                    + "authored.")
            .add()
            .appendInherited(new KeyedCodec<>("MaxVisible", Codec.INTEGER, false),
                    (a, v) -> a.maxVisible = v, a -> a.maxVisible, (a, p) -> a.maxVisible = p.maxVisible)
            .documentation("How many bars show at once; when more are moving, the most recently moved "
                    + "ones are drawn. Held to however many the panel's own layout declares, which is "
                    + "also what an unauthored value reads.")
            .add()
            .appendInherited(new KeyedCodec<>("Columns", Codec.INTEGER, false),
                    (a, v) -> a.columns = v, a -> a.columns, (a, p) -> a.columns = p.columns)
            .documentation("The most columns the rows spread across, restated over the Placement's. One "
                    + "stacks them in a single column; a new column opens only once RowsPerColumn is "
                    + "exceeded, and never more than the panel's own layout declares.")
            .add()
            .appendInherited(new KeyedCodec<>("RowsPerColumn", Codec.INTEGER, false),
                    (a, v) -> a.rowsPerColumn = v, a -> a.rowsPerColumn,
                    (a, p) -> a.rowsPerColumn = p.rowsPerColumn)
            .documentation("How many rows one column takes before another opens, restated over the "
                    + "Placement's. Set it high to keep a tall single column until the panel is "
                    + "genuinely busy; set it to 1 to spread rows sideways as soon as there is a second.")
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

    /** A panel with every leaf unauthored: on, named by its id, sitting where its document says, every slot its document has. */
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

    /** The label's localization key, trimmed, or null to list the panel by its id. */
    @Nullable
    public String labelKey() {
        return labelKey == null || labelKey.isBlank() ? null : labelKey.trim();
    }

    /** What the panel is called on screen: its key resolved on the client, else its id. */
    @Nonnull
    public Message label() {
        String key = labelKey();
        return key != null ? ContentKeys.tr(key) : Msg.raw(id);
    }

    /** The id of the spot the panel names, trimmed, or null when it names none. */
    @Nullable
    public String placement() {
        return placement == null || placement.isBlank() ? null : placement.trim();
    }

    /** The inline corner and offsets restated over the spot, or null when the file states none. */
    @Nullable
    public HudBarPosition authoredPosition() {
        return position == null || position.isEmpty() ? null : position;
    }

    /** The inline column cap restated over the spot, or null (unauthored or not positive). */
    @Nullable
    public Integer authoredColumns() {
        return columns == null || columns <= 0 ? null : columns;
    }

    /** The inline rows per column restated over the spot, or null (unauthored or not positive). */
    @Nullable
    public Integer authoredRowsPerColumn() {
        return rowsPerColumn == null || rowsPerColumn <= 0 ? null : rowsPerColumn;
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

    /** How often the panel redraws at most; {@value #DEFAULT_REPAINT_MS} ms when unauthored or nonsense. */
    public long repaintMs() {
        return repaintMs != null && repaintMs > 0 ? repaintMs : DEFAULT_REPAINT_MS;
    }
}
