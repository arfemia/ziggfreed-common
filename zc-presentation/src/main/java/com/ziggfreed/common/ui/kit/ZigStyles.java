package com.ziggfreed.common.ui.kit;

import java.util.function.Function;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.ziggfreed.common.ui.UiRetint;

/**
 * The kit's named state styles, swapped onto an element by reference: a row's normal and selected look, a segment
 * on and off, a tile and a complete tile, the four action buttons, the state word per tone, the gold figure and the
 * selected row's title. Each is a style {@code Common/ZigStyles.ui} defines under {@link Name#styleName()}, and
 * {@link #apply} sends it the way vanilla swaps a selected row
 * ({@code cmd.set(sel + ".Style", Value.ref("Pages/WorldEvent/WorldEventListRow.ui", "SelectedRowStyle"))},
 * {@code WorldEventPanelPage.java:88-92, 790}): no page Java pushes a colour for a row, segment, button or state word.
 *
 * <p><b>Themes.</b> A theme is another document with the same style names, chosen per viewer by
 * {@link #document(PlayerRef)} (the default {@link #DOCUMENT}; a consumer installs a chooser with
 * {@link #documents(Function)}). {@code ZigStylesDocumentTest} proves every {@link Name} exists in every theme
 * document shipped.
 *
 * <p><b>The fallback form.</b> Should the client refuse a reference (risk spike SP1), the same call sends the style's
 * leaves instead: a button's three state fills ({@code .Default/.Hovered/.Pressed.Background.Color}) and a label's
 * {@code .TextColor}, all from {@link ZigTokens}. Buttons and labels switch separately
 * ({@code BUTTONS_BY_REFERENCE}, {@code LABELS_BY_REFERENCE}), since the spike proves each on its own; the painters
 * call {@link #apply} either way and never change.
 */
public final class ZigStyles {

    /** The default theme document, rooted at {@code Common/UI/Custom/} as {@code Value.ref} takes it. */
    public static final String DOCUMENT = "Common/ZigStyles.ui";

    /** Where theme documents live, rooted at {@code Common/UI/Custom/}: each defines every {@link Name}. */
    public static final String THEMES = "Common/Themes/";

    /** A theme document's path: directly under {@link #THEMES}, a letter then letters or digits, {@code .ui}. */
    private static final Pattern THEME_PATH = Pattern.compile("Common/Themes/[A-Za-z][A-Za-z0-9]*[.]ui");

    /**
     * The kit's text-style document ({@code Common/ZigText.ui}): not themed, read by the painters to put a label back
     * to its resting style (a row title after it was selected, a finished row's faint meta).
     */
    public static final String TEXT_DOCUMENT = "Common/ZigText.ui";

    /** Whether a button style goes as a reference (spike SP1a/b), else as its state fills. */
    static final boolean BUTTONS_BY_REFERENCE = true;

    /** Whether a label style goes as a reference (spike SP1d), else as its {@code .TextColor}. */
    static final boolean LABELS_BY_REFERENCE = true;

    private static volatile Function<PlayerRef, String> chooser;

    /** What a style is applied to: a button's {@code ButtonStyle} or a label's {@code LabelStyle}. */
    enum Kind { BUTTON, LABEL }

    /** The named styles, each with the leaves its fallback form sends. */
    public enum Name {
        ROW("ZigRowStyle", Kind.BUTTON, ZigTokens.SURFACE_ROW, ZigTokens.SURFACE_ROW_HOVER,
                ZigTokens.SURFACE_ROW_PRESSED),
        ROW_SELECTED("ZigRowSelectedStyle", Kind.BUTTON, ZigTokens.SURFACE_ROW_SELECTED,
                ZigTokens.SURFACE_ROW_SELECTED, ZigTokens.SURFACE_ROW_SELECTED),
        SEGMENT("ZigSegmentStyle", Kind.BUTTON, ZigTokens.INK_BRIGHT, ZigTokens.INK_BRIGHT, ZigTokens.INK_BRIGHT),
        SEGMENT_ON("ZigSegmentOnStyle", Kind.BUTTON, ZigTokens.SURFACE_ROW_SELECTED,
                ZigTokens.SURFACE_ROW_SELECTED, ZigTokens.SURFACE_ROW_SELECTED),
        TILE("ZigTileStyle", Kind.BUTTON, ZigTokens.INK_BRIGHT, ZigTokens.INK_BRIGHT, ZigTokens.INK_BRIGHT),
        TILE_COMPLETE("ZigTileCompleteStyle", Kind.BUTTON, ZigTokens.TONE_DONE_TEXT, ZigTokens.TONE_DONE_TEXT,
                ZigTokens.TONE_DONE_TEXT),
        BUTTON_PRIMARY("ZigButtonPrimaryStyle", Kind.BUTTON, ZigTokens.INK_BRIGHT, ZigTokens.INK_BRIGHT,
                ZigTokens.INK_BRIGHT),
        BUTTON_SECONDARY("ZigButtonSecondaryStyle", Kind.BUTTON, ZigTokens.INK_BRIGHT, ZigTokens.INK_BRIGHT,
                ZigTokens.INK_BRIGHT),
        BUTTON_COLLECT("ZigButtonCollectStyle", Kind.BUTTON, ZigTokens.ACCENT, ZigTokens.ACCENT, ZigTokens.ACCENT),
        BUTTON_DANGER("ZigButtonDangerStyle", Kind.BUTTON, ZigTokens.TONE_DANGER_TEXT, ZigTokens.TONE_DANGER_TEXT,
                ZigTokens.TONE_DANGER_TEXT),
        STATE_NEUTRAL("ZigStateStyle", Kind.LABEL, ZigTokens.INK_BODY),
        STATE_ACTIVE("ZigStateActiveStyle", Kind.LABEL, ZigTokens.TONE_ACTIVE_TEXT),
        STATE_COLLECT("ZigStateCollectStyle", Kind.LABEL, ZigTokens.TONE_COLLECT_TEXT),
        STATE_DONE("ZigStateDoneStyle", Kind.LABEL, ZigTokens.TONE_DONE_TEXT),
        STATE_LIVE("ZigStateLiveStyle", Kind.LABEL, ZigTokens.TONE_LIVE_TEXT),
        STATE_AVAILABLE("ZigStateAvailableStyle", Kind.LABEL, ZigTokens.TONE_AVAILABLE_TEXT),
        STATE_WAITING("ZigStateWaitingStyle", Kind.LABEL, ZigTokens.TONE_WAITING_TEXT),
        STATE_BLOCKED("ZigStateBlockedStyle", Kind.LABEL, ZigTokens.TONE_BLOCKED_TEXT),
        STATE_DANGER("ZigStateDangerStyle", Kind.LABEL, ZigTokens.TONE_DANGER_TEXT),
        FIGURE_ACCENT("ZigFigureAccentStyle", Kind.LABEL, ZigTokens.ACCENT),
        /**
         * A selected list row's title, white on the steel blue; the two-line list title, so a theme's must wrap to two
         * lines too, or selecting a row cuts its name back to one (M515).
         */
        ROW_TITLE_ON_SELECTED("ZigRowTitleOnSelectedStyle", Kind.LABEL, ZigTokens.INK_BRIGHT),
        /** A selected row's meta line, white on the steel blue (the muted ink does not read there). */
        ROW_META_ON_SELECTED("ZigRowMetaOnSelectedStyle", Kind.LABEL, ZigTokens.INK_BRIGHT),
        /** A selected row's trail value, white on the steel blue. */
        ROW_VALUE_ON_SELECTED("ZigRowValueOnSelectedStyle", Kind.LABEL, ZigTokens.INK_BRIGHT),
        /** A selected row's state word, white on the steel blue (no tone colour reads there). */
        STATE_ON_SELECTED("ZigStateOnSelectedStyle", Kind.LABEL, ZigTokens.INK_BRIGHT),
        /** A view tab at rest ({@link ViewTabPainter}): the faint pane, the row's hover under the cursor. */
        VIEW_TAB("ZigViewTabStyle", Kind.BUTTON, ZigTokens.SURFACE_PANE, ZigTokens.SURFACE_ROW_HOVER,
                ZigTokens.SURFACE_ROW_PRESSED),
        /** The chosen view tab, on the row fill. */
        VIEW_TAB_ON("ZigViewTabOnStyle", Kind.BUTTON, ZigTokens.SURFACE_ROW, ZigTokens.SURFACE_ROW_HOVER,
                ZigTokens.SURFACE_ROW_PRESSED),
        /** A view tab's name at rest, muted. */
        VIEW_TAB_LABEL("ZigViewTabLabelStyle", Kind.LABEL, ZigTokens.INK_MUTED),
        /** The chosen view tab's name, the rail's gold. */
        VIEW_TAB_LABEL_ON("ZigViewTabLabelOnStyle", Kind.LABEL, ZigTokens.ACCENT);

        private final String styleName;
        private final Kind kind;
        private final String[] leaves;

        Name(@Nonnull String styleName, @Nonnull Kind kind, @Nonnull String... leaves) {
            this.styleName = styleName;
            this.kind = kind;
            this.leaves = leaves;
        }

        /** The style's name in its document, without the {@code @} sigil (the form {@code Value.ref} takes). */
        @Nonnull
        public String styleName() {
            return styleName;
        }

        @Nonnull
        Kind kind() {
            return kind;
        }
    }

    /**
     * The kit's resting text styles in {@link #TEXT_DOCUMENT}, which a painter puts back after a state style
     * replaced them. Not themed; the fallback form sends the colour alone.
     */
    enum Text {
        /** A list row's title at rest: the two-line list title its templates author (M515). */
        ROW_TITLE("ZigLedgerTitleStyle", ZigTokens.INK_STRONG),
        FAINT("ZigFaintStyle", ZigTokens.INK_FAINT);

        private final String styleName;
        private final String colour;

        Text(@Nonnull String styleName, @Nonnull String colour) {
            this.styleName = styleName;
            this.colour = colour;
        }

        @Nonnull
        String styleName() {
            return styleName;
        }
    }

    private ZigStyles() {
    }

    /**
     * The theme document for {@code viewer}: the installed chooser's answer when it names a theme document under
     * {@link #THEMES} ({@link #accept}), else {@link #DOCUMENT}. Never throws; a chooser that fails, answers blank or
     * names anything else reads as the default.
     */
    @Nonnull
    public static String document(@Nullable PlayerRef viewer) {
        Function<PlayerRef, String> current = chooser;
        if (current == null || viewer == null) {
            return DOCUMENT;
        }
        try {
            return accept(current.apply(viewer));
        } catch (RuntimeException e) {
            return DOCUMENT;
        }
    }

    /**
     * Install the theme seam: a function from a viewer to the style document their pages use, {@link #DOCUMENT} or a
     * theme document under {@link #THEMES} ({@link #themeDocument}); a theme document defines every {@link Name}
     * ({@code ZigStylesDocumentTest} holds every shipped one to that). Null removes it. Set once at setup.
     */
    public static void documents(@Nullable Function<PlayerRef, String> themeChooser) {
        chooser = themeChooser;
    }

    /**
     * The theme document named {@code themeId}: {@code Common/Themes/<themeId>.ui}, the path a theme asset's leaf
     * names and a chooser answers. Not checked here; {@link #document} reads only a well-formed one.
     */
    @Nonnull
    public static String themeDocument(@Nonnull String themeId) {
        return THEMES + themeId + ".ui";
    }

    /**
     * What a chooser's answer becomes: the default, or a theme document directly under {@link #THEMES} whose name is
     * a letter then letters or digits; anything else (a typo, another folder, a page document) reads as the default,
     * since a reference into a document the client lacks breaks the page it is sent to.
     */
    @Nonnull
    static String accept(@Nullable String chosen) {
        if (chosen == null) {
            return DOCUMENT;
        }
        return THEME_PATH.matcher(chosen).matches() ? chosen : DOCUMENT;
    }

    /** Remove the theme seam. */
    public static void resetForTests() {
        chooser = null;
    }

    /**
     * Swap the named style onto an element.
     *
     * @param styleSelector the element's style property, {@code "#X.Style"} (a {@code Button}'s {@code ButtonStyle}
     *                      or a {@code Label}'s {@code LabelStyle}, as the name's kind says)
     */
    public static void apply(@Nonnull UICommandBuilder cmd, @Nonnull String styleSelector, @Nonnull Name name,
            @Nullable PlayerRef viewer) {
        boolean byReference = name.kind() == Kind.BUTTON ? BUTTONS_BY_REFERENCE : LABELS_BY_REFERENCE;
        apply(cmd, styleSelector, name, viewer, byReference);
    }

    /** {@link #apply} in the form given: by reference, or as the style's leaves. */
    static void apply(@Nonnull UICommandBuilder cmd, @Nonnull String styleSelector, @Nonnull Name name,
            @Nullable PlayerRef viewer, boolean byReference) {
        if (byReference) {
            cmd.set(styleSelector, Value.<String>ref(document(viewer), name.styleName()));
            return;
        }
        if (name.kind() == Kind.BUTTON) {
            UiRetint.retintColor(cmd, styleSelector + ".Default", name.leaves[0]);
            UiRetint.retintColor(cmd, styleSelector + ".Hovered", name.leaves[1]);
            UiRetint.retintColor(cmd, styleSelector + ".Pressed", name.leaves[2]);
        } else {
            cmd.set(styleSelector + ".TextColor", name.leaves[0]);
        }
    }

    /** Put a label back to one of the kit's resting text styles. */
    static void applyText(@Nonnull UICommandBuilder cmd, @Nonnull String styleSelector, @Nonnull Text text) {
        applyText(cmd, styleSelector, text, LABELS_BY_REFERENCE);
    }

    static void applyText(@Nonnull UICommandBuilder cmd, @Nonnull String styleSelector, @Nonnull Text text,
            boolean byReference) {
        if (byReference) {
            cmd.set(styleSelector, Value.<String>ref(TEXT_DOCUMENT, text.styleName()));
        } else {
            cmd.set(styleSelector + ".TextColor", text.colour);
        }
    }
}
