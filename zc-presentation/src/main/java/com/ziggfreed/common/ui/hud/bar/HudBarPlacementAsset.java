package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.hud.card.HudCardLook;

/**
 * A named spot a bar panel can sit at: a corner and offsets, plus how the rows spread out there,
 * because how much room a spot has decides whether a panel there should grow sideways or down. The
 * FILE NAME is the placement's id, what a panel's {@code Placement} names, what a player picks in
 * the HUD settings and what {@code /zighud place} takes.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/HudBarPlacements/<id>.json}. This library ships
 * {@code TopLeft.json}, {@code TopRight.json} and {@code BottomLeft.json}; a pack adds a spot by
 * dropping one more file here and every picker offers it, and a server owner retunes any of them
 * from {@code mods/ziggfreedcommon/hud-bar-placements.json} by id.
 * <pre>{@code
 * // Server/ZiggfreedCommon/HudBarPlacements/AboveHotbar.json
 * { "LabelKey":      "mypack.hud.placement.above_hotbar",
 *   "Position":      { "Preset": "BottomCenter", "OffsetY": 120 },
 *   "Columns":       3,
 *   "RowsPerColumn": 1,
 *   "Panels":        ["Grid"] }
 * }</pre>
 *
 * <p>{@code Panels} is which panels the spot was measured for. Each panel's document has its own
 * column width, so a spot that clears the hotbar with 200-wide columns may not with 296-wide ones;
 * a placement that names panels is offered to those alone, and one that names none is offered to
 * every panel. A placement is never a requirement: a panel names one, and a panel that names none,
 * or names one that has gone, sits where its document says.
 *
 * <p>Three leaves shape the panel's height at the spot. {@code Gap} ({@link HudBarGap}) leaves a
 * band clear across every column after so many rows counted from the pinned edge, so a bottom-pinned
 * stack straddles the player's own bars rather than climbing into them; {@code Cutout}
 * ({@link HudBarCutout}) leaves the cells at the pinned end of ONE column empty, so the panel steps
 * around something a client draws under that column alone, that column's rows starting past the
 * cut and stopping where the uncut columns stop, the surplus going into a row above the block;
 * {@code MinHeight} is a floor on the panel's height, for a spot that sits over something a short
 * panel would otherwise leave partly showing.
 *
 * <p>{@code Color} is the look a panel wears at this spot, as the one hex every HUD card
 * understands ({@link com.ziggfreed.common.ui.hud.card.HudCardLook}: a multiply over the shipped
 * frame, eight digits carrying a transparency), restated over the shared record every card reads
 * and under the panel's own inline {@code Color}.
 */
public final class HudBarPlacementAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, HudBarPlacementAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/HudBarPlacements";

    /** The Asset Editor pick list a panel's {@code Placement} leaf is chosen from: every placement id. */
    public static final String EDITOR_DATA_SET = "ziggfreedcommon:hud_bar_placements";

    /** Where a placement sits in a picker when it names no order: after every one that did. */
    public static final int DEFAULT_ORDER = 1000;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String labelKey;
    @Nullable private HudBarPosition position;
    @Nullable private Integer columns;
    @Nullable private Integer rowsPerColumn;
    @Nullable private HudBarGap gap;
    @Nullable private HudBarCutout cutout;
    @Nullable private Integer minHeight;
    @Nullable private String color;
    @Nullable private String[] panels;
    @Nullable private Integer order;
    @Nullable private Boolean enabled;

    public static final AssetBuilderCodec<String, HudBarPlacementAsset> CODEC = AssetBuilderCodec.builder(
                    HudBarPlacementAsset.class,
                    HudBarPlacementAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("LabelKey", Codec.STRING, false),
                    (a, v) -> a.labelKey = v, a -> a.labelKey, (a, p) -> a.labelKey = p.labelKey)
            .documentation("A localization key naming this spot in the HUD settings, resolved on each "
                    + "player's own client. A full registered id (\"mypack.hud.placement.corner\") passes "
                    + "through as written. Left out, the placement is listed by its id.")
            .add()
            .appendInherited(new KeyedCodec<>("Position", HudBarPosition.CODEC, false),
                    (a, v) -> a.position = v, a -> a.position, (a, p) -> a.position = p.position)
            .documentation("Where the panel hangs: a corner preset plus pixel offsets from the edges that "
                    + "preset pins. A Bottom preset grows upward as rows come up, a Right preset grows "
                    + "leftward as columns open.")
            .add()
            .appendInherited(new KeyedCodec<>("Columns", Codec.INTEGER, false),
                    (a, v) -> a.columns = v, a -> a.columns, (a, p) -> a.columns = p.columns)
            .documentation("The most columns the rows spread across at this spot, never more than the "
                    + "panel's own document declares. One stacks them in a single column. Left out, the "
                    + "panel's own setting stands.")
            .add()
            .appendInherited(new KeyedCodec<>("RowsPerColumn", Codec.INTEGER, false),
                    (a, v) -> a.rowsPerColumn = v, a -> a.rowsPerColumn,
                    (a, p) -> a.rowsPerColumn = p.rowsPerColumn)
            .documentation("How many rows one column takes before another opens. Set it to 1 to spread "
                    + "sideways as soon as there is a second row, or high to keep a tall single column "
                    + "until the panel is genuinely busy. Left out, the panel's own setting stands.")
            .add()
            .appendInherited(new KeyedCodec<>("Gap", HudBarGap.CODEC, false),
                    (a, v) -> a.gap = v, a -> a.gap, (a, p) -> a.gap = p.gap)
            .documentation("A band left clear across every column at the same height, so the rows "
                    + "straddle something already on screen: at a Bottom spot, the player's own health "
                    + "and mana bars. AfterRow counts from the spot's pinned edge (the rows up to it sit "
                    + "against that edge, the band follows, the rest continue past it) and Pixels is the "
                    + "band's height. Left out, or with either number at zero, the rows run unbroken.")
            .add()
            .appendInherited(new KeyedCodec<>("Cutout", HudBarCutout.CODEC, false),
                    (a, v) -> a.cutout = v, a -> a.cutout, (a, p) -> a.cutout = p.cutout)
            .documentation("The cells left empty at the pinned end of ONE column, so the panel steps "
                    + "around something a client draws under that column alone: at a Bottom spot, the "
                    + "utility slot and the hotbar's end under the far column. Column counts from the "
                    + "panel's own first column, the one at the spot's origin (at a Right spot that is "
                    + "the rightmost), and Rows is how many of its cells nearest the pinned edge stay "
                    + "clear. That column's rows start past the cut and stop where the columns "
                    + "without a cut stop, so it never stands taller than them: the rows it has no "
                    + "room for go into a row of their own above the block, filled from the panel's "
                    + "first column outward, and a row past the document's depth is left undrawn. "
                    + "Left out, with either number at zero, or naming a column the rows never open, "
                    + "every cell is used.")
            .add()
            .appendInherited(new KeyedCodec<>("MinHeight", Codec.INTEGER, false),
                    (a, v) -> a.minHeight = v, a -> a.minHeight, (a, p) -> a.minHeight = p.minHeight)
            .documentation("The least height the panel draws at here, in pixels, whatever its rows add "
                    + "up to: for a spot that sits over something on screen which a panel of one or two "
                    + "rows would otherwise leave partly showing. Left out, the panel is exactly as tall "
                    + "as its rows.")
            .add()
            .appendInherited(new KeyedCodec<>("Color", Codec.STRING, false),
                    (a, v) -> a.color = v, a -> a.color, (a, p) -> a.color = p.color)
            .documentation("The colour a panel's frame is drawn in at this spot, as a hex that MULTIPLIES "
                    + "the shipped frame: #ffffff is exactly the shipped look, a darker hex darkens it, a "
                    + "hue tints it, and eight digits carry a transparency in the last two (#ffffffb8 is "
                    + "about 72 percent). The panel's own Color, if it states one, wins over this. Left "
                    + "out, the panel takes the look every HUD card shares, "
                    + "Server/ZiggfreedCommon/HudCards/Default.json; a value that is not a #rrggbb or "
                    + "#rrggbbaa hex is ignored with one line in the log.")
            .add()
            .appendInherited(new KeyedCodec<>("Panels", Codec.STRING_ARRAY, false),
                    (a, v) -> a.panels = v, a -> a.panels, (a, p) -> a.panels = p.panels)
            .documentation("The panel ids this spot was measured for: \"Default\" is the tall ledger in "
                    + "the left column, \"Grid\" the wide block. Only those panels offer it. Left out, "
                    + "every panel does.")
            .add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue(DEFAULT_ORDER))
            .documentation("Where this spot is listed in a picker: lower numbers first, equal numbers by "
                    + "id. Left out reads " + DEFAULT_ORDER + ", after every spot that named one.")
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to take this spot out of every picker; a panel or a player still "
                    + "naming it falls back as if it had gone. Unauthored reads true.")
            .add()
            .build();

    public HudBarPlacementAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The label's localization key, trimmed, or null to list the placement by its id. */
    @Nullable
    public String labelKey() {
        return labelKey == null || labelKey.isBlank() ? null : labelKey.trim();
    }

    /** What the placement is called on screen: its key resolved on the client, else its id. */
    @Nonnull
    public Message label() {
        String key = labelKey();
        return key != null ? ContentKeys.tr(key) : Msg.raw(id);
    }

    /** The authored corner and offsets, or null when the file states none. */
    @Nullable
    public HudBarPosition position() {
        return position == null || position.isEmpty() ? null : position;
    }

    /** The authored column cap, or null (unauthored or not positive) to keep the panel's own. */
    @Nullable
    public Integer columns() {
        return columns == null || columns <= 0 ? null : columns;
    }

    /** The authored rows per column, or null (unauthored or not positive) to keep the panel's own. */
    @Nullable
    public Integer rowsPerColumn() {
        return rowsPerColumn == null || rowsPerColumn <= 0 ? null : rowsPerColumn;
    }

    /** The authored band, or null when the file states no leaf of it; the paint decides whether it applies. */
    @Nullable
    public HudBarGap gap() {
        return gap == null || gap.isEmpty() ? null : gap;
    }

    /** The authored cut, or null when the file states no leaf of it; the paint decides whether it applies. */
    @Nullable
    public HudBarCutout cutout() {
        return cutout == null || cutout.isEmpty() ? null : cutout;
    }

    /** The authored floor on the panel's height, or null (unauthored or not positive) for none. */
    @Nullable
    public Integer minHeight() {
        return minHeight == null || minHeight <= 0 ? null : minHeight;
    }

    /**
     * The authored frame colour, normalised, or null when the file states none; a value that is not
     * a hex warns once, naming this file and the value, and reads as none.
     */
    @Nullable
    public String color() {
        return HudCardLook.authored(color, "Server/" + TYPE_ROOT + "/" + id + ".json (or its owner entry)");
    }

    /** Whether this spot is offered to the panel {@code panelId}: it names it, or names no panel at all. */
    public boolean fits(@Nullable String panelId) {
        if (panelId == null || panelId.isBlank()) {
            return false;
        }
        if (panels == null || panels.length == 0) {
            return true;
        }
        for (String panel : panels) {
            if (panel != null && panel.trim().equalsIgnoreCase(panelId.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Where this spot is listed among the others; {@value #DEFAULT_ORDER} when unauthored. */
    public int order() {
        return order == null ? DEFAULT_ORDER : order;
    }

    /** Whether the spot is offered at all; true unless the file says otherwise. */
    public boolean enabled() {
        return !Boolean.FALSE.equals(enabled);
    }
}
