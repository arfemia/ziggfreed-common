package com.ziggfreed.common.ui;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * Mod-agnostic binding + widget helpers for a settings / config form page: dropdown populate + bind,
 * text-field bind, button bind, a status line, an on/off toggle, and a segmented-tab active paint. The
 * domain-free twin of a consumer's own {@code UIUtil} (engine types only - no i18n keys, no content
 * enums) so a settings page in any mod reuses ONE binding layer instead of re-deriving it.
 *
 * <p><b>Rich-text discipline (do NOT regress):</b> every text-bearing helper here targets a Label's
 * {@code .TextSpans} (via {@link ZigRichButton} or a direct set), NEVER a {@code TextButton.Text} /
 * {@code .Text} String sink - a client-resolved {@link Message} only substitutes params + renders
 * {@code <color>/<b>} markup on {@code .TextSpans}. {@link #setToggle} is therefore a vanilla
 * {@code Button} + inner {@code #Label} (a {@code @ZigRichRowBtnStyle} instance), retinted per state via
 * {@link UiRetint#retintButtonStates}; {@link #setStatus} sets a Label's {@code .TextSpans}.
 */
public final class SettingsUiUtil {

    // On/off + active-tab tints (steel/green convention; a consumer may override by not using these).
    private static final String TOGGLE_ON_BG = "#1a3d2e";
    private static final String TOGGLE_ON_LABEL = "#4aff7f";
    private static final String TOGGLE_OFF_BG = "#2b3542";
    private static final String TOGGLE_OFF_LABEL = "#96a9be";
    private static final String TAB_ACTIVE_BG = "#2a4a3a";
    private static final String TAB_ACTIVE_LABEL = "#4aff7f";
    private static final String TAB_INACTIVE_BG = "#3a4658";
    private static final String TAB_INACTIVE_LABEL = "#b6c9de";
    private static final String STATUS_ERROR = "#ff6a6a";
    private static final String STATUS_OK = "#4aff7f";

    private SettingsUiUtil() {
        // static helper
    }

    // ---------------------------------------------------------------------
    // Dropdown
    // ---------------------------------------------------------------------

    /** One dropdown entry (display text + data value). */
    @Nonnull
    public static DropdownEntryInfo entry(@Nonnull String text, @Nonnull String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(text), value);
    }

    /** Set a dropdown's entries and, when non-empty, its current value. */
    public static void populate(@Nonnull UICommandBuilder cmd, @Nonnull String selector,
            @Nonnull List<DropdownEntryInfo> entries, @Nullable String value) {
        cmd.set(selector + ".Entries", entries);
        if (value != null && !value.isEmpty()) {
            cmd.set(selector + ".Value", value);
        }
    }

    /** Populate a dropdown from parallel label/value arrays. */
    public static void populate(@Nonnull UICommandBuilder cmd, @Nonnull String selector,
            @Nonnull String[] labels, @Nonnull String[] values, @Nullable String selected) {
        List<DropdownEntryInfo> entries = new ArrayList<>(labels.length);
        for (int i = 0; i < labels.length; i++) {
            entries.add(entry(labels[i], values[i]));
        }
        populate(cmd, selector, entries, selected);
    }

    /** Bind a dropdown {@code ValueChanged}: {@code Action -> action}, {@code @DropdownValue -> .Value}. */
    public static void bindDropdown(@Nonnull UIEventBuilder events, @Nonnull String selector,
            @Nonnull String action) {
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, selector,
                EventData.of("Action", action).append("@DropdownValue", selector + ".Value"), false);
    }

    // ---------------------------------------------------------------------
    // Text / number field
    // ---------------------------------------------------------------------

    /** Bind a text field {@code ValueChanged} mapping its {@code .Value} to a {@code @}-prefixed codec key. */
    public static void bindTextField(@Nonnull UIEventBuilder events, @Nonnull String selector,
            @Nonnull String codecKey) {
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, selector,
                EventData.of(codecKey, selector + ".Value"), false);
    }

    // ---------------------------------------------------------------------
    // Button
    // ---------------------------------------------------------------------

    /** Bind an {@code Activating} click with just an action name. */
    public static void bindButton(@Nonnull UIEventBuilder events, @Nonnull String selector,
            @Nonnull String action) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", action), false);
    }

    /** Bind an {@code Activating} click with an action + one extra parameter. */
    public static void bindButton(@Nonnull UIEventBuilder events, @Nonnull String selector,
            @Nonnull String action, @Nonnull String key, @Nonnull String value) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", action).append(key, value), false);
    }

    /** Bind an {@code Activating} click with an action + two extra parameters. */
    public static void bindButton(@Nonnull UIEventBuilder events, @Nonnull String selector,
            @Nonnull String action, @Nonnull String key1, @Nonnull String value1,
            @Nonnull String key2, @Nonnull String value2) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                EventData.of("Action", action).append(key1, value1).append(key2, value2), false);
    }

    /** Bind the standard {@code #BackButton -> "back"} + {@code #CloseButton -> "close"} navigation. */
    public static void bindNavigation(@Nonnull UIEventBuilder events) {
        bindButton(events, "#BackButton", "back");
        bindButton(events, "#CloseButton", "close");
    }

    // ---------------------------------------------------------------------
    // Status line + toggle + tab (TextSpans, never .Text)
    // ---------------------------------------------------------------------

    /**
     * Set a status Label's text ({@code .TextSpans}, so a client-resolved / parameterized {@link Message}
     * renders correctly) and colour (red on error, green otherwise). A null {@code msg} HIDES the label
     * (there is no empty-Message sink in a mod-agnostic layer, so clearing = hide).
     */
    public static void setStatus(@Nonnull UICommandBuilder cmd, @Nonnull String selector,
            @Nullable Message msg, boolean isError) {
        if (msg == null) {
            cmd.set(selector + ".Visible", false);
            return;
        }
        cmd.set(selector + ".TextSpans", msg);
        cmd.set(selector + ".Style.TextColor", isError ? STATUS_ERROR : STATUS_OK);
        cmd.set(selector + ".Visible", true);
    }

    /**
     * Paint an on/off toggle built as a vanilla {@code Button} + inner {@code #Label} (a
     * {@code @ZigRichRowBtnStyle} instance): the label shows {@code onLabel}/{@code offLabel} via
     * {@code .TextSpans}, the three button states retint green (ON) / steel (OFF), and the label colour
     * follows. Apply-only (the page re-renders each state), so it is safe in a full {@code build()} or a
     * partial {@code sendUpdate}.
     */
    public static void setToggle(@Nonnull UICommandBuilder cmd, @Nonnull String selector, boolean on,
            @Nonnull Message onLabel, @Nonnull Message offLabel) {
        ZigRichButton.text(cmd, selector, on ? onLabel : offLabel);
        String bg = on ? TOGGLE_ON_BG : TOGGLE_OFF_BG;
        UiRetint.retintButtonStates(cmd, selector, bg, bg, bg);
        ZigRichButton.color(cmd, selector, on ? TOGGLE_ON_LABEL : TOGGLE_OFF_LABEL);
    }

    /**
     * Paint a segmented RICH tab ({@code Button} + inner {@code #Label}) as active / inactive by
     * retinting its three per-state backgrounds (via {@link UiRetint#retintButtonStates}) + its inner
     * label colour (via {@link ZigRichButton#color}, the {@code .TextSpans} label - NOT a
     * {@code TextButton} LabelStyle). Apply-only; call for every tab each render so switching resets the
     * others. Pair with {@link ZigRichButton#text} to set each tab's label.
     */
    public static void setTabActive(@Nonnull UICommandBuilder cmd, @Nonnull String selector,
            boolean active) {
        String bg = active ? TAB_ACTIVE_BG : TAB_INACTIVE_BG;
        UiRetint.retintButtonStates(cmd, selector, bg, bg, bg);
        ZigRichButton.color(cmd, selector, active ? TAB_ACTIVE_LABEL : TAB_INACTIVE_LABEL);
    }
}
