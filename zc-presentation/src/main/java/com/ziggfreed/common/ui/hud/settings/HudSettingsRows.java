package com.ziggfreed.common.ui.hud.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ui.hud.HudPreferenceComponent;
import com.ziggfreed.common.ui.hud.bar.HudBarPanelAsset;
import com.ziggfreed.common.ui.hud.bar.HudBarPanelConfig;

/**
 * What the HUD settings page lists on each tab, worked out with nothing but the player's own
 * preference and the folded panels in hand: an ordered plan of rows the page then appends one by
 * one. The plan IS the control set of a tab, so what each tab offers is a fact a test reads rather
 * than a side effect of a builder the engine cannot stand up outside a client.
 *
 * <p><b>Mine</b> is one switch hiding every bar, then for each panel a header, a picker over the
 * spots measured for it (the server's own choice first) and a switch showing that panel alone. It
 * carries NO field: a player moves their own panels between the spots on offer and nothing more.
 *
 * <p><b>Server</b> is for each panel a header, its on-for-everyone switch, its spot, and every
 * inline leaf ({@link HudServerLeaf}, in its order) as a field showing what the panel's file
 * states, then the note on what Save writes.
 *
 * <p>A row id is the control's name in the one event shape ({@link HudSettingsEventData}): one of
 * the prefixes here with the panel id after the colon, or {@link #HIDE_ALL} alone. Labels and
 * hints are keys under the family's {@code settings.} family, resolved on the reader's client.
 */
final class HudSettingsRows {

    /** The Mine switch over every panel. */
    static final String HIDE_ALL = "hideAll";

    /** A Mine picker: {@code pick:<panel>}. */
    static final String PICK = "pick:";

    /** A Mine show switch: {@code show:<panel>}. */
    static final String SHOW = "show:";

    /** A Server on-for-everyone switch: {@code enabled:<panel>}. */
    static final String ENABLED = "enabled:";

    /** A Server spot picker: {@code placement:<panel>}. */
    static final String PLACEMENT = "placement:";

    /** The dropdown value meaning "no pick of my own" on Mine, and "as the shipped file says" on Server. */
    static final String NONE = "";

    /** Which template a row is appended from, and which parts of it the page paints. */
    enum Kind {
        /** A panel's name over its rows; {@code panelId} names the panel. */
        HEADER,
        /** A switch; {@code on} is its state. */
        TOGGLE,
        /** A spot picker; {@code panelId} says whose spots, {@code noneKey} words the first entry, {@code value} is the chosen id. */
        DROPDOWN,
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
     * @param noneKey  the settings key wording a dropdown's first, "no spot of my own" entry, else null
     * @param on       a toggle's state
     * @param value    a dropdown's chosen id or a field's text, else blank
     */
    record Row(@Nonnull Kind kind, @Nullable String id, @Nullable String panelId, @Nullable String labelKey,
            @Nullable String hintKey, @Nullable String noneKey, boolean on, @Nonnull String value) {

        @Nonnull
        static Row header(@Nonnull String panelId) {
            return new Row(Kind.HEADER, null, panelId, null, null, null, false, "");
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
     * The Mine tab for a player whose preference is {@code prefs} (null for none recorded): the
     * hide-all switch, then per panel in {@code panelIds} its header, its picker and its show switch.
     */
    @Nonnull
    static List<Row> mine(@Nullable HudPreferenceComponent prefs, @Nonnull List<String> panelIds) {
        List<Row> rows = new ArrayList<>();
        rows.add(Row.toggle(HIDE_ALL, "hide_all", "hide_all_hint", prefs != null && prefs.hideAll()));
        for (String id : panelIds) {
            rows.add(Row.header(id));
            String pick = prefs == null ? null : prefs.placementOf(id);
            rows.add(Row.dropdown(PICK + id, id, "spot", "spot_hint", "server_spot", pick == null ? NONE : pick));
            rows.add(Row.toggle(SHOW + id, "show_panel", null, prefs == null || !prefs.isHiddenAlone(id)));
        }
        return rows;
    }

    /**
     * The Server tab over the folded {@code panels}: per panel in {@code panelIds} its header, its
     * on-for-everyone switch, its spot and one field per inline leaf showing what its file states,
     * then the note.
     */
    @Nonnull
    static List<Row> server(@Nonnull List<String> panelIds, @Nonnull HudBarPanelConfig panels) {
        List<Row> rows = new ArrayList<>();
        for (String id : panelIds) {
            HudBarPanelAsset panel = panels.panel(id);
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
}
