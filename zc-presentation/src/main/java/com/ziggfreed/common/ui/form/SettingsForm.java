package com.ziggfreed.common.ui.form;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.ui.SettingsUiUtil;

/**
 * A generic, mod-agnostic settings-form engine: one instance per form, built from an ordered
 * {@link FieldSpec} list and owning a raw {@code Map<String, String>} value cache (every
 * leaf-bearing / toggle field defaults to {@code ""}). It renders itself as APPENDED rows into a
 * consumer's scrolling container ({@link #buildRows}), refreshes just the cached values into
 * already-built rows for a partial {@code sendUpdate} ({@link #applyValues}/{@link #applyValue}),
 * and parses the cache back into a leaf map ready for a write-back like
 * {@code JsonOverrideWriter.setLeaves} ({@link #collectLeaves}).
 *
 * <p><b>Mod-agnostic by construction.</b> This engine carries no i18n keys of its own, no consumer
 * content types, and no persistence policy: a {@link FieldSpec#labelKey()} is an opaque string the
 * CONSUMER resolves through the {@code Function<String, Message>} passed to {@link #buildRows}, and
 * {@link #collectLeaves} only produces a plain {@code Map<String, Object>} the consumer writes
 * wherever it likes. Specs, labels, and persistence all stay in the consumer; this class only
 * binds / paints / parses.
 *
 * <p><b>Event wiring convention.</b> Every TEXT-ish / TRISTATE / DROPDOWN field binds a
 * {@code ValueChanged} with {@code EventData.of("Action", "field").append("Field", id).append("@Value", <selector>)}
 * (the {@code "@"}-prefixed key tells the client to read the live element value back under
 * {@code "@Value"}); a TOGGLE binds an {@code Activating} press with
 * {@code EventData.of("Action", "press").append("Field", id)} (via {@link SettingsUiUtil#bindButton}).
 * A consumer's {@code handleDataEvent} routes the {@code "field"} action through {@link #cache} and
 * the {@code "press"} action through its own toggle-flip policy - this class never flips a toggle's
 * cached value itself, it only paints whatever {@link #cache}/{@link #seedValue} last recorded.
 *
 * <p><b>{@code blankIsInherit}.</b> {@link #collectLeaves} takes one boolean governing every TEXT /
 * NUMBER / INT field the SAME way: {@code true} treats a blank field as "inherit the layer below" (a
 * null leaf, i.e. remove the override); {@code false} treats a blank NUMBER/INT as a validation error
 * (a TEXT field never errors - blank becomes an explicit empty-string leaf instead). CSV / TRISTATE /
 * DROPDOWN ignore the flag entirely; see their own per-kind blank rule on {@link #collectLeaves}.
 *
 * <p><b>Row template contract</b> (the child ids every {@code ui/form} row {@code .ui} must carry):
 * {@code #Title} is the row label for every kind except {@code NOTE} (which labels {@code #Note}
 * instead); {@code #Field} is the TEXT-ish control; {@code #Dropdown} is the TRISTATE/DROPDOWN
 * control; {@code #Toggle} is the toggle button (its own inner {@code #Label} is what
 * {@link SettingsUiUtil#setToggle} drives, so it never collides with the row's {@code #Title}).
 * The FIELD/DROPDOWN/TOGGLE templates also carry an optional wrapping {@code #Hint} sub-label
 * (hidden, zero-height, until {@link #buildRows}/{@link #applyHint} paints + shows it) - never
 * {@code HEADER}/{@code NOTE}, whose {@link FieldSpec#withHint} throws instead.
 */
public final class SettingsForm {

    private static final String TEMPLATE_FIELD = "Pages/ZigFormFieldRow.ui";
    private static final String TEMPLATE_DROPDOWN = "Pages/ZigFormDropdownRow.ui";
    private static final String TEMPLATE_TOGGLE = "Pages/ZigFormToggleRow.ui";
    private static final String TEMPLATE_HEADER = "Pages/ZigFormHeaderRow.ui";
    private static final String TEMPLATE_NOTE = "Pages/ZigFormNoteRow.ui";

    private static final String[] TRISTATE_LABELS = {"Inherit", "On", "Off"};
    private static final String[] TRISTATE_VALUES = {"inherit", "on", "off"};
    private static final String[] EMPTY_VALUES = new String[0];

    @Nonnull private final List<FieldSpec> specs;
    @Nonnull private final Map<String, FieldSpec> specsById;
    @Nonnull private final Map<String, Integer> indexById;
    @Nonnull private final Map<String, String> values = new HashMap<>();
    @Nonnull private final Message toggleOnLabel;
    @Nonnull private final Message toggleOffLabel;

    /**
     * @param specs          the form's fields, in render order
     * @param toggleOnLabel  the label a TOGGLE row shows in its ON state (see {@link SettingsUiUtil#setToggle})
     * @param toggleOffLabel the label a TOGGLE row shows in its OFF state
     */
    public SettingsForm(@Nonnull List<FieldSpec> specs, @Nonnull Message toggleOnLabel,
            @Nonnull Message toggleOffLabel) {
        this.specs = new ArrayList<>(specs);
        this.toggleOnLabel = toggleOnLabel;
        this.toggleOffLabel = toggleOffLabel;
        Map<String, FieldSpec> byId = new HashMap<>();
        Map<String, Integer> idxById = new HashMap<>();
        for (int i = 0; i < this.specs.size(); i++) {
            FieldSpec spec = this.specs.get(i);
            byId.put(spec.id(), spec);
            idxById.put(spec.id(), i);
            if (spec.kind() != FieldKind.HEADER && spec.kind() != FieldKind.NOTE) {
                values.put(spec.id(), "");
            }
        }
        this.specsById = byId;
        this.indexById = idxById;
    }

    // ---------------------------------------------------------------------
    // Render: append one row per spec
    // ---------------------------------------------------------------------

    /**
     * Append one row per spec (in list order) into {@code containerSel}: row {@code i}'s selector is
     * {@code containerSel + "[" + i + "]"}. Paints each row's label (resolved through {@code labels}),
     * its current cached value, and wires its live event binding.
     */
    public void buildRows(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String containerSel, @Nonnull Function<String, Message> labels) {
        for (int i = 0; i < specs.size(); i++) {
            FieldSpec spec = specs.get(i);
            cmd.append(containerSel, templateFor(spec.kind()));
            String rowSel = containerSel + "[" + i + "]";
            String labelSel = spec.kind() == FieldKind.NOTE ? rowSel + " #Note" : rowSel + " #Title";
            cmd.set(labelSel + ".TextSpans", labels.apply(spec.labelKey()));
            renderControl(cmd, events, rowSel, spec);
            if (spec.hintKey() != null) {
                String hintSel = rowSel + " #Hint";
                cmd.set(hintSel + ".TextSpans", labels.apply(spec.hintKey()));
                cmd.set(hintSel + ".Visible", true);
            }
        }
    }

    /** Build-time: paint one row's control from the cached value AND wire its live event binding. */
    private void renderControl(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String rowSel, @Nonnull FieldSpec spec) {
        switch (spec.kind()) {
            case HEADER:
            case NOTE:
                break; // label only
            case TOGGLE:
                SettingsUiUtil.setToggle(cmd, rowSel + " #Toggle", "on".equals(value(spec.id())),
                        toggleOnLabel, toggleOffLabel);
                SettingsUiUtil.bindButton(events, rowSel + " #Toggle", "press", "Field", spec.id());
                break;
            case TRISTATE: {
                String sel = rowSel + " #Dropdown";
                SettingsUiUtil.populate(cmd, sel, TRISTATE_LABELS, TRISTATE_VALUES, value(spec.id()));
                bindFieldChange(events, sel, spec.id());
                break;
            }
            case DROPDOWN: {
                String sel = rowSel + " #Dropdown";
                String[] vals = spec.values() != null ? spec.values() : EMPTY_VALUES;
                SettingsUiUtil.populate(cmd, sel, vals, vals, value(spec.id()));
                bindFieldChange(events, sel, spec.id());
                break;
            }
            default: { // TEXT, NUMBER, CHANCE, INT, CSV
                String sel = rowSel + " #Field";
                cmd.set(sel + ".Value", value(spec.id()));
                bindFieldChange(events, sel, spec.id());
                break;
            }
        }
    }

    private static void bindFieldChange(@Nonnull UIEventBuilder events, @Nonnull String selector,
            @Nonnull String fieldId) {
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, selector,
                EventData.of("Action", "field").append("Field", fieldId).append("@Value", selector + ".Value"),
                false);
    }

    @Nonnull
    private static String templateFor(@Nonnull FieldKind kind) {
        switch (kind) {
            case HEADER:
                return TEMPLATE_HEADER;
            case NOTE:
                return TEMPLATE_NOTE;
            case TOGGLE:
                return TEMPLATE_TOGGLE;
            case TRISTATE:
            case DROPDOWN:
                return TEMPLATE_DROPDOWN;
            default:
                return TEMPLATE_FIELD;
        }
    }

    // ---------------------------------------------------------------------
    // Partial refresh: values only, no append, no (re)binding
    // ---------------------------------------------------------------------

    /** Push every cached value into its ALREADY-BUILT row (no append, no rebinding); for a partial {@code sendUpdate}. */
    public void applyValues(@Nonnull UICommandBuilder cmd, @Nonnull String containerSel) {
        for (int i = 0; i < specs.size(); i++) {
            refreshControl(cmd, containerSel + "[" + i + "]", specs.get(i));
        }
    }

    /** Single-row variant of {@link #applyValues}, for refreshing just the one field that changed. */
    public void applyValue(@Nonnull UICommandBuilder cmd, @Nonnull String containerSel, @Nonnull String fieldId) {
        Integer index = indexById.get(fieldId);
        FieldSpec spec = specsById.get(fieldId);
        if (index == null || spec == null) {
            return;
        }
        refreshControl(cmd, containerSel + "[" + index + "]", spec);
    }

    /**
     * Set or hide one row's {@code #Hint} sub-label directly, OVERRIDING whatever {@link #buildRows}
     * last painted there. {@code hint == null} hides the row ({@code #Hint.Visible} false); a non-null
     * {@link Message} paints {@code #Hint.TextSpans} and shows it. A no-op for an unknown
     * {@code fieldId} or a {@code HEADER}/{@code NOTE} spec (their row templates carry no
     * {@code #Hint}). Lets a consumer compose static help text with a dynamically-built line (e.g.
     * "Inherits: 30") and re-push the result in a partial update; never called by
     * {@link #applyValues}/{@link #applyValue}, which touch only the control value.
     */
    public void applyHint(@Nonnull UICommandBuilder cmd, @Nonnull String containerSel, @Nonnull String fieldId,
            @Nullable Message hint) {
        Integer index = indexById.get(fieldId);
        FieldSpec spec = specsById.get(fieldId);
        if (index == null || spec == null || spec.kind() == FieldKind.HEADER || spec.kind() == FieldKind.NOTE) {
            return;
        }
        String hintSel = containerSel + "[" + index + "] #Hint";
        if (hint == null) {
            cmd.set(hintSel + ".Visible", false);
        } else {
            cmd.set(hintSel + ".TextSpans", hint);
            cmd.set(hintSel + ".Visible", true);
        }
    }

    /** Partial refresh: push the cached value into an ALREADY-BUILT row; no event (re)binding. */
    private void refreshControl(@Nonnull UICommandBuilder cmd, @Nonnull String rowSel, @Nonnull FieldSpec spec) {
        switch (spec.kind()) {
            case HEADER:
            case NOTE:
                break;
            case TOGGLE:
                SettingsUiUtil.setToggle(cmd, rowSel + " #Toggle", "on".equals(value(spec.id())),
                        toggleOnLabel, toggleOffLabel);
                break;
            case TRISTATE:
            case DROPDOWN:
                cmd.set(rowSel + " #Dropdown.Value", value(spec.id()));
                break;
            default: // TEXT, NUMBER, CHANCE, INT, CSV
                cmd.set(rowSel + " #Field.Value", value(spec.id()));
                break;
        }
    }

    // ---------------------------------------------------------------------
    // Value cache
    // ---------------------------------------------------------------------

    /** The {@code ValueChanged} sink. False + no-op for an unknown {@code fieldId} or a null {@code value}. */
    public boolean cache(@Nonnull String fieldId, @Nullable String value) {
        if (value == null || !specsById.containsKey(fieldId)) {
            return false;
        }
        values.put(fieldId, value);
        return true;
    }

    /** The cached raw value, or {@code ""} when unset / the id is unknown. */
    @Nonnull
    public String value(@Nonnull String fieldId) {
        String v = values.get(fieldId);
        return v != null ? v : "";
    }

    /** Replace the whole cache: every leaf-bearing/toggle spec is set from {@code values}; a missing key becomes {@code ""}. */
    public void seed(@Nonnull Map<String, String> values) {
        for (FieldSpec spec : specs) {
            if (spec.kind() == FieldKind.HEADER || spec.kind() == FieldKind.NOTE) {
                continue;
            }
            String v = values.get(spec.id());
            this.values.put(spec.id(), v != null ? v : "");
        }
    }

    /** Set one field's cached value directly (e.g. pre-seeding from a loaded config); a no-op for an unknown id. */
    public void seedValue(@Nonnull String fieldId, @Nonnull String value) {
        if (specsById.containsKey(fieldId)) {
            values.put(fieldId, value);
        }
    }

    /** The spec for {@code fieldId}, or null when unknown. */
    @Nullable
    public FieldSpec spec(@Nonnull String fieldId) {
        return specsById.get(fieldId);
    }

    // ---------------------------------------------------------------------
    // Collect: cache -> leaf map
    // ---------------------------------------------------------------------

    /**
     * Parse the cached raw values into a leaf map (spec order; a null value means "remove this
     * leaf"), or the first validation failure. Every raw value is trimmed before parsing.
     *
     * <ul>
     *   <li>TOGGLE/HEADER/NOTE - skipped (never collected).
     *   <li>TEXT - blank -&gt; ({@code blankIsInherit} ? null : {@code ""}); else the trimmed string.
     *   <li>NUMBER - blank -&gt; ({@code blankIsInherit} ? null : error); else a non-negative finite
     *       {@code double}, else error.
     *   <li>CHANCE - like NUMBER, plus a value &gt; 1.0 is an error.
     *   <li>INT - blank -&gt; ({@code blankIsInherit} ? null : error); else any-sign {@code int}, else error.
     *   <li>CSV - split on {@code ','}, trim each, drop empties; empty result -&gt; null; else an
     *       insertion-order {@code List<String>}.
     *   <li>TRISTATE - {@code "on"} -&gt; {@code Boolean.TRUE}, {@code "off"} -&gt; {@code Boolean.FALSE}, else null.
     *   <li>DROPDOWN - blank or {@code "inherit"} -&gt; null; else the raw string.
     * </ul>
     *
     * <p>Leaves are keyed by {@link FieldSpec#leafPath()}; a NUMBER leaf boxes as {@link Double}, an
     * INT leaf as {@link Integer} (the {@code JsonOverrideWriter} type-fidelity contract). The first
     * failing spec aborts collection immediately - {@link FormResult#leaves()} is empty on error.
     *
     * @param blankIsInherit whether a blank TEXT/NUMBER/INT field means "inherit" (a null leaf) rather
     *                       than a validation error (NUMBER/INT) or an explicit empty string (TEXT)
     */
    @Nonnull
    public FormResult collectLeaves(boolean blankIsInherit) {
        Map<String, Object> leaves = new LinkedHashMap<>();
        for (FieldSpec spec : specs) {
            FieldKind kind = spec.kind();
            if (kind == FieldKind.TOGGLE || kind == FieldKind.HEADER || kind == FieldKind.NOTE) {
                continue;
            }
            String raw = value(spec.id()).trim();
            String leafPath = spec.leafPath();
            switch (kind) {
                case TEXT:
                    leaves.put(leafPath, raw.isEmpty() ? (blankIsInherit ? null : "") : raw);
                    break;
                case NUMBER: {
                    if (raw.isEmpty()) {
                        if (!blankIsInherit) {
                            return FormResult.error(spec.labelKey(), "number");
                        }
                        leaves.put(leafPath, null);
                        break;
                    }
                    Double parsed = parseNonNegative(raw);
                    if (parsed == null) {
                        return FormResult.error(spec.labelKey(), "number");
                    }
                    leaves.put(leafPath, parsed);
                    break;
                }
                case CHANCE: {
                    if (raw.isEmpty()) {
                        if (!blankIsInherit) {
                            return FormResult.error(spec.labelKey(), "chance");
                        }
                        leaves.put(leafPath, null);
                        break;
                    }
                    Double parsed = parseNonNegative(raw);
                    if (parsed == null || parsed > 1.0) {
                        return FormResult.error(spec.labelKey(), "chance");
                    }
                    leaves.put(leafPath, parsed);
                    break;
                }
                case INT: {
                    if (raw.isEmpty()) {
                        if (!blankIsInherit) {
                            return FormResult.error(spec.labelKey(), "int");
                        }
                        leaves.put(leafPath, null);
                        break;
                    }
                    try {
                        leaves.put(leafPath, Integer.parseInt(raw));
                    } catch (NumberFormatException ex) {
                        return FormResult.error(spec.labelKey(), "int");
                    }
                    break;
                }
                case CSV: {
                    List<String> parts = new ArrayList<>();
                    for (String part : raw.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) {
                            parts.add(trimmed);
                        }
                    }
                    leaves.put(leafPath, parts.isEmpty() ? null : parts);
                    break;
                }
                case TRISTATE:
                    if ("on".equals(raw)) {
                        leaves.put(leafPath, Boolean.TRUE);
                    } else if ("off".equals(raw)) {
                        leaves.put(leafPath, Boolean.FALSE);
                    } else {
                        leaves.put(leafPath, null);
                    }
                    break;
                case DROPDOWN:
                    leaves.put(leafPath, raw.isEmpty() || "inherit".equals(raw) ? null : raw);
                    break;
                default:
                    break; // unreachable: TOGGLE/HEADER/NOTE filtered above
            }
        }
        return FormResult.ok(leaves);
    }

    /** Parse a non-negative finite double; null on unparseable / NaN / negative. */
    @Nullable
    private static Double parseNonNegative(@Nonnull String raw) {
        double d;
        try {
            d = Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (Double.isNaN(d) || d < 0) {
            return null;
        }
        return d;
    }
}
