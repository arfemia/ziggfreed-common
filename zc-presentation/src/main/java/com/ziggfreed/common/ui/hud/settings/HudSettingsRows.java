package com.ziggfreed.common.ui.hud.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.settings.TrackerRules;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.hud.panel.HudPanelAsset;
import com.ziggfreed.common.ui.hud.panel.HudPanelConfig;
import com.ziggfreed.common.ui.hud.panel.HudSpotPosition;

/**
 * What the HUD settings page lists, worked out with nothing but the folded panels in hand: an ordered
 * plan of rows the page then appends one by one. The plan IS the control set of a tab, so what each tab
 * offers is a fact a test reads rather than a side effect of a builder the engine cannot stand up outside
 * a client.
 *
 * <p><b>Server</b> is for each panel a header, its on-for-everyone switch, its spot, and every
 * inline leaf ({@link HudServerLeaf}, in its order) as a field showing what the panel's file
 * states, then the note on what Save writes. The quest tracker's section ({@link #tracker}) comes before
 * it: its on-for-everyone switch, its corner and its two offsets, kept in the player-settings record.
 *
 * <p>A row id is the control's name in the one event shape ({@link HudSettingsEventData}): one of
 * the prefixes here with the panel id after the colon. Labels and hints are keys under the family's
 * {@code settings.} family, resolved on the reader's client.
 */
final class HudSettingsRows {

    /** A Server on-for-everyone switch: {@code enabled:<panel>}. */
    static final String ENABLED = "enabled:";

    /** A Server spot picker: {@code placement:<panel>}. */
    static final String PLACEMENT = "placement:";

    /** The dropdown value meaning "as the shipped file says". */
    static final String NONE = "";

    /** The quest tracker's on-for-everyone switch. */
    static final String TRACKER_ENABLED = "tracker:enabled";

    /** The quest tracker's corner picker. */
    static final String TRACKER_CORNER = "tracker:corner";

    /** The quest tracker's offset from its corner, across. */
    static final String TRACKER_OFFSET_X = "tracker:offsetx";

    /** The quest tracker's offset from its corner, down or up. */
    static final String TRACKER_OFFSET_Y = "tracker:offsety";

    /** The corners the tracker may hang from, as its file spells them, in the order the picker lists them. */
    static final List<String> CORNERS = List.of("TopLeft", "TopCenter", "TopRight", "CenterLeft", "Center",
            "CenterRight", "BottomLeft", "BottomCenter", "BottomRight");

    /** Which template a row is appended from, and which parts of it the page paints. */
    enum Kind {
        /** A heading over its rows: a panel's name ({@code panelId}), else the words of {@code labelKey}. */
        HEADER,
        /** A switch; {@code on} is its state. */
        TOGGLE,
        /** A spot picker; {@code panelId} says whose spots, {@code noneKey} words the first entry, {@code value} is the chosen id. */
        DROPDOWN,
        /** A corner picker: {@link #CORNERS}, {@code noneKey} wording the first entry, {@code value} the chosen corner. */
        CORNER,
        /** A text field; {@code value} is what it shows. */
        FIELD,
        /** A line of explanation; {@code labelKey} is its text. */
        NOTE
    }

    /**
     * One row of the plan. The parts a kind does not use are null, false or blank.
     *
     * @param kind     which template the row is
     * @param id       the row id the control names itself by, null for a header or a note
     * @param panelId  the panel the row belongs to (a header names it, a dropdown lists its spots), else null
     * @param labelKey the settings key of the row's title, or of a note's text; null for a header,
     *                 whose title is the panel's own name
     * @param hintKey  the settings key of the line under the control, or null for none
     * @param noneKey  the settings key wording a dropdown's first, "as the shipped file says" entry, else null
     * @param on       a toggle's state
     * @param value    a dropdown's chosen id or a field's text, else blank
     */
    record Row(@Nonnull Kind kind, @Nullable String id, @Nullable String panelId, @Nullable String labelKey,
            @Nullable String hintKey, @Nullable String noneKey, boolean on, @Nonnull String value) {

        @Nonnull
        static Row header(@Nonnull String panelId) {
            return new Row(Kind.HEADER, null, panelId, null, null, null, false, "");
        }

        /** A heading worded by a settings key, over rows that are no panel's. */
        @Nonnull
        static Row heading(@Nonnull String labelKey) {
            return new Row(Kind.HEADER, null, null, labelKey, null, null, false, "");
        }

        @Nonnull
        static Row corner(@Nonnull String id, @Nonnull String labelKey, @Nullable String hintKey,
                @Nonnull String noneKey, @Nonnull String value) {
            return new Row(Kind.CORNER, id, null, labelKey, hintKey, noneKey, false, value);
        }

        @Nonnull
        static Row toggle(@Nonnull String id, @Nonnull String labelKey, @Nullable String hintKey, boolean on) {
            return new Row(Kind.TOGGLE, id, null, labelKey, hintKey, null, on, "");
        }

        @Nonnull
        static Row dropdown(@Nonnull String id, @Nonnull String panelId, @Nonnull String labelKey,
                @Nullable String hintKey, @Nonnull String noneKey, @Nonnull String value) {
            return new Row(Kind.DROPDOWN, id, panelId, labelKey, hintKey, noneKey, false, value);
        }

        @Nonnull
        static Row field(@Nonnull String id, @Nonnull String labelKey, @Nullable String hintKey,
                @Nonnull String value) {
            return new Row(Kind.FIELD, id, null, labelKey, hintKey, null, false, value);
        }

        @Nonnull
        static Row note(@Nonnull String labelKey) {
            return new Row(Kind.NOTE, null, null, labelKey, null, null, false, "");
        }
    }

    private HudSettingsRows() {
    }

    /**
     * The Server tab over the folded {@code panels}: per panel in {@code panelIds} its header, its
     * on-for-everyone switch, its spot and one field per inline leaf showing what its file states,
     * then the note.
     */
    @Nonnull
    static List<Row> server(@Nonnull List<String> panelIds, @Nonnull HudPanelConfig panels) {
        List<Row> rows = new ArrayList<>();
        for (String id : panelIds) {
            HudPanelAsset panel = panels.panel(id);
            rows.add(Row.header(id));
            rows.add(Row.toggle(ENABLED + id, "enabled", null, panel.enabled()));
            String named = panel.placement();
            rows.add(Row.dropdown(PLACEMENT + id, id, "spot", "server_spot_hint", "shipped_spot",
                    named == null ? NONE : named.toLowerCase(Locale.ROOT)));
            for (HudServerLeaf leaf : HudServerLeaf.values()) {
                rows.add(Row.field(leaf.rowId(id), leaf.labelKey(), leaf.hintKey(), leaf.shown(panel)));
            }
        }
        rows.add(Row.note("server_note"));
        return rows;
    }

    /**
     * The quest tracker's section over its folded {@code rules} (the player-settings record's {@code QuestTracker}):
     * its heading, its on-for-everyone switch, the corner it hangs from and its two offsets, each showing what the
     * record states (blank for none), then the note on what Save writes. Listed before the panels.
     */
    @Nonnull
    static List<Row> tracker(@Nonnull TrackerRules rules) {
        HudSpotPosition position = rules.position();
        List<Row> rows = new ArrayList<>();
        rows.add(Row.heading("tracker"));
        rows.add(Row.toggle(TRACKER_ENABLED, "enabled", "tracker_enabled_hint", rules.enabled()));
        rows.add(Row.corner(TRACKER_CORNER, "tracker_corner", "tracker_corner_hint", "shipped_corner",
                cornerOf(position == null ? null : position.preset())));
        rows.add(Row.field(TRACKER_OFFSET_X, "offset_x", null, textOf(position == null ? null : position.offsetX())));
        rows.add(Row.field(TRACKER_OFFSET_Y, "offset_y", null, textOf(position == null ? null : position.offsetY())));
        rows.add(Row.note("tracker_note"));
        return rows;
    }

    /** {@code preset} in the spelling {@link #CORNERS} lists it, or {@link #NONE} for none or one nothing reads. */
    @Nonnull
    static String cornerOf(@Nullable String preset) {
        HudPosition parsed = HudPosition.parse(preset, 0, 0);
        if (parsed == null) {
            return NONE;
        }
        String vertical = switch (parsed.getAnchorEdge()) {
            case TOP -> "Top";
            case CENTER -> "Center";
            case BOTTOM -> "Bottom";
        };
        String across = switch (parsed.getHorizontalEdge()) {
            case LEFT -> "Left";
            case CENTER -> "Center";
            case RIGHT -> "Right";
        };
        String corner = vertical + across;
        return "CenterCenter".equals(corner) ? "Center" : corner;
    }

    @Nonnull
    private static String textOf(@Nullable Integer value) {
        return value == null ? "" : value.toString();
    }

    /**
     * What Save writes for the tracker out of {@code draft} (row id to typed text), as the record's leaves relative
     * to its {@code QuestTracker} group: the corner and the two offsets, a blank one removing its leaf so the shipped
     * value applies again. Null when an offset will not read as a whole number; nothing is written then.
     */
    @Nullable
    static Map<String, Object> trackerLeaves(@Nonnull Map<String, String> draft) {
        Map<String, Object> leaves = new LinkedHashMap<>();
        String corner = cornerOf(draft.get(TRACKER_CORNER));
        leaves.put("Position.Preset", corner.isEmpty() ? null : corner);
        try {
            leaves.put("Position.OffsetX", wholeOrNull(draft.get(TRACKER_OFFSET_X)));
            leaves.put("Position.OffsetY", wholeOrNull(draft.get(TRACKER_OFFSET_Y)));
        } catch (NumberFormatException e) {
            return null;
        }
        return leaves;
    }

    /** Which of the two offsets in {@code draft} will not read as a whole number, X first; null when both read. */
    @Nullable
    static String unreadableOffset(@Nonnull Map<String, String> draft) {
        for (String id : List.of(TRACKER_OFFSET_X, TRACKER_OFFSET_Y)) {
            try {
                wholeOrNull(draft.get(id));
            } catch (NumberFormatException e) {
                return id.equals(TRACKER_OFFSET_X) ? "offset_x" : "offset_y";
            }
        }
        return null;
    }

    @Nullable
    private static Integer wholeOrNull(@Nullable String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.isEmpty() ? null : Integer.parseInt(trimmed);
    }
}
