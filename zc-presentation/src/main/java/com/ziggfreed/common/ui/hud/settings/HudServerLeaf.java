package com.ziggfreed.common.ui.hud.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.hud.panel.HudPanelAsset;
import com.ziggfreed.common.ui.hud.panel.HudSpotCutout;
import com.ziggfreed.common.ui.hud.panel.HudSpotGap;
import com.ziggfreed.common.ui.hud.panel.HudSpotPosition;
import com.ziggfreed.common.ui.hud.card.HudCardLook;

/**
 * One inline leaf of a bar panel the Server tab offers as a field, per panel: the row it is typed
 * in, the leaf of {@code mods/ziggfreedcommon/hud-panels.json} it writes, what it is called and
 * how its text is read. The order here is the order the tab lists them, after the panel's switch
 * and its spot: the offsets, the spread, the band, the cut, the least height, the colour.
 *
 * <p>Every field reads by ONE rule. Blank REMOVES the leaf, so the owner file stays exactly as
 * terse as an owner would have typed it and the spot's own value applies again. A value that reads
 * is written as the codec expects it: a whole number for the nine numeric leaves, and for the
 * colour the normalised hex {@link HudCardLook#parse} answers (six or eight digits, a hash
 * optional, the last two digits a transparency), the same reader every card's file goes through,
 * so the page can never accept a value the paint would then ignore. Anything else is refused
 * NAMING the field, and nothing at all is written.
 *
 * <p>Pure: no builder, no page, no file. {@link #draft} is the one decision Save makes per panel,
 * and {@link #shown} the one the tab makes when it opens; both are what the tests read.
 */
enum HudServerLeaf {

    OFFSET_X("offsetx", "Position.OffsetX", "offset_x", null, Kind.WHOLE_NUMBER),
    OFFSET_Y("offsety", "Position.OffsetY", "offset_y", null, Kind.WHOLE_NUMBER),
    COLUMNS("columns", "Columns", "columns", null, Kind.WHOLE_NUMBER),
    ROWS_PER_COLUMN("rows", "RowsPerColumn", "rows_per_column", null, Kind.WHOLE_NUMBER),
    GAP_AFTER_ROW("gaprow", "Gap.AfterRow", "gap_after_row", null, Kind.WHOLE_NUMBER),
    GAP_PIXELS("gappx", "Gap.Pixels", "gap_pixels", null, Kind.WHOLE_NUMBER),
    CUTOUT_COLUMN("cutcol", "Cutout.Column", "cutout_column", null, Kind.WHOLE_NUMBER),
    CUTOUT_ROWS("cutrows", "Cutout.Rows", "cutout_rows", null, Kind.WHOLE_NUMBER),
    MIN_HEIGHT("minheight", "MinHeight", "min_height", null, Kind.WHOLE_NUMBER),
    COLOR("color", "Color", "color", "color_hint", Kind.COLOR);

    /** How a field's text is read, and which line the page says when it will not read. */
    enum Kind {

        /** {@link Integer#parseInt}: a whole number, negative allowed, since an offset may be one. */
        WHOLE_NUMBER("invalid_number"),

        /** {@link HudCardLook#parse}: the one hex every card is coloured by. */
        COLOR("invalid_color");

        @Nonnull private final String refusalKey;

        Kind(@Nonnull String refusalKey) {
            this.refusalKey = refusalKey;
        }

        /**
         * The settings key of the toast naming a field of this kind that would not read; it takes
         * the field's label as its one argument.
         */
        @Nonnull
        String refusalKey() {
            return refusalKey;
        }
    }

    /**
     * What Save collected for one panel: every leaf as the draft has it, or the first field that
     * would not read, in which case {@code leaves} is empty and nothing is to be written.
     */
    record Draft(@Nonnull Map<String, Object> leaves, @Nullable HudServerLeaf refused) {

        /** True when every field read, so {@link #leaves} is what to write. */
        boolean accepted() {
            return refused == null;
        }
    }

    @Nonnull private final String prefix;
    @Nonnull private final String path;
    @Nonnull private final String labelKey;
    @Nullable private final String hintKey;
    @Nonnull private final Kind kind;

    HudServerLeaf(@Nonnull String prefix, @Nonnull String path, @Nonnull String labelKey,
            @Nullable String hintKey, @Nonnull Kind kind) {
        this.prefix = prefix;
        this.path = path;
        this.labelKey = labelKey;
        this.hintKey = hintKey;
        this.kind = kind;
    }

    /** The row this leaf is typed in for {@code panelId}: how the control names itself in the one event shape. */
    @Nonnull
    String rowId(@Nonnull String panelId) {
        return prefix + ":" + panelId;
    }

    /** The dotted path of the leaf, relative to the panel's entry in the owner file. */
    @Nonnull
    String path() {
        return path;
    }

    /** The settings key of the field's label. */
    @Nonnull
    String labelKey() {
        return labelKey;
    }

    /** The settings key of the line under the field, or null when the label says it all. */
    @Nullable
    String hintKey() {
        return hintKey;
    }

    @Nonnull
    Kind kind() {
        return kind;
    }

    /**
     * What the field shows when the tab opens: the leaf as {@code panel}'s file states it, or blank
     * for none. A band or a cut leaf stated as zero shows its zero, because that zero is how an
     * owner switches the band or the cut off, and a blank would remove it on the next Save.
     */
    @Nonnull
    String shown(@Nonnull HudPanelAsset panel) {
        HudSpotPosition position = panel.authoredPosition();
        HudSpotGap gap = panel.authoredGap();
        HudSpotCutout cutout = panel.authoredCutout();
        Object value = switch (this) {
            case OFFSET_X -> position == null ? null : position.offsetX();
            case OFFSET_Y -> position == null ? null : position.offsetY();
            case COLUMNS -> panel.authoredColumns();
            case ROWS_PER_COLUMN -> panel.authoredRowsPerColumn();
            case GAP_AFTER_ROW -> gap == null ? null : gap.authoredAfterRow();
            case GAP_PIXELS -> gap == null ? null : gap.authoredPixels();
            case CUTOUT_COLUMN -> cutout == null ? null : cutout.authoredColumn();
            case CUTOUT_ROWS -> cutout == null ? null : cutout.authoredRows();
            case MIN_HEIGHT -> panel.authoredMinHeight();
            case COLOR -> panel.authoredColor();
        };
        return value == null ? "" : value.toString();
    }

    /**
     * The value {@code text} writes under this leaf: null for blank (the leaf is removed), the
     * boxed whole number or the normalised hex otherwise.
     *
     * @throws IllegalArgumentException when {@code text} is neither blank nor a value this leaf reads
     */
    @Nullable
    Object value(@Nullable String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (kind == Kind.COLOR) {
            HudCardLook look = HudCardLook.parse(trimmed);
            if (look == null) {
                throw new IllegalArgumentException("not a #rrggbb or #rrggbbaa hex: " + trimmed);
            }
            return look.hex();
        }
        return Integer.parseInt(trimmed);
    }

    /**
     * Every leaf of {@code panelId} as {@code draft} (row id to typed text) has it, in this order: a
     * blank or absent field a removal, a value written as the codec expects it. The first field
     * that will not read refuses the whole panel with NO leaves, so Save writes nothing and the
     * admin fixes the one field the refusal names.
     */
    @Nonnull
    static Draft draft(@Nonnull String panelId, @Nonnull Map<String, String> draft) {
        Map<String, Object> leaves = new LinkedHashMap<>();
        for (HudServerLeaf leaf : values()) {
            try {
                leaves.put(leaf.path, leaf.value(draft.get(leaf.rowId(panelId))));
            } catch (IllegalArgumentException e) {
                return new Draft(Map.of(), leaf);
            }
        }
        return new Draft(leaves, null);
    }
}
