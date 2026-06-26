package com.ziggfreed.common.ui;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * The generic, mod-agnostic primitive for a CLICKABLE element that must show
 * rich / parameterized localized text.
 *
 * <p><b>Why this exists.</b> Hytale's {@code TextButton.Text} is a flat CLR
 * {@code System.String} sink: the client resolves a single translation key or a
 * raw string, but it does NOT substitute {@code {0}/{1}} message params. So a
 * PARAMETERIZED / composite {@link Message} - a {@code ui.glue.prefix} glue, a
 * {@code Message.join(...)}, or a translation whose value itself contains
 * {@code {0}} (e.g. {@code "{0} Novice"}) - renders the LITERAL template
 * ({@code "{0}{1}"}) on a {@code TextButton}. Parameter substitution AND rich
 * {@code <color>/<b>/<i>} markup happen ONLY on a Label's {@code .TextSpans}.
 * (Server-side {@code MessageUtil.formatText} does substitute, which is why chat /
 * console look right; the live 0.5.6 client button sink does not.)
 *
 * <p><b>The fix that keeps click + hover + sounds.</b> Use the vanilla
 * {@code Button} ELEMENT - a clickable container with a {@code ButtonStyle} (NOT a
 * {@code TextButton}) that wraps arbitrary children, exactly like the vanilla icon
 * buttons - and nest a child {@code Label #Label} whose text is pushed onto its
 * {@code .TextSpans} through this helper. The {@code Button} carries the
 * Default/Hovered/Pressed/Disabled backgrounds + click sounds; the inner
 * {@code Label} carries the (substituting) text. The click binding stays on the
 * {@code Button} id, like any other button.
 *
 * <p><b>.ui contract</b> (the inner label id is the convention {@link #LABEL},
 * mirroring how the decorated frame standardizes {@code #Content}):
 * <pre>
 *   Button #Foo {
 *     Style: &#64;SomeButtonStyle;          // a ButtonStyle (backgrounds + Sounds), not a TextButtonStyle
 *     Label #Label { Text: ""; Style: (...) }  // the inner text label (FlexWeight: 1 to fill)
 *   }
 * </pre>
 * Then from Java: {@code ZigRichButton.text(cmd, sel + " #Foo", msg)} and bind the
 * click on {@code sel + " #Foo"} as usual.
 *
 * <p>Validated against the official Hytale shared source ({@code MessageUtil.formatText},
 * the {@code Button}/{@code ButtonStyle} element family in the vanilla
 * {@code Common.ui}) and the live client; authority + evidence in the hyMMO repo's
 * {@code .claude/research/hytale-rich-text-textspans.md}. Pairs with {@link UiRetint}:
 * a rich button built on the {@code Secondary.png}/{@code Tertiary.png} patch stays
 * retint-ready via {@link UiRetint#retintButtonStates} on its {@code #Foo} id.
 */
public final class ZigRichButton {

    /**
     * The conventional id of the inner {@code Label} inside a rich button. Standardized
     * (like the decorated frame's {@code #Content}) so this helper always knows where to
     * push the text; every rich-button {@code .ui} names its text label {@code #Label}.
     */
    public static final String LABEL = "#Label";

    /** Conventional id of the OPTIONAL leading icon slot in the {@code @ZigRichBtn} component. */
    public static final String ICON_START = "#IconStart";

    /** Conventional id of the OPTIONAL trailing icon slot in the {@code @ZigRichBtn} component. */
    public static final String ICON_END = "#IconEnd";

    private ZigRichButton() {
        // static primitive
    }

    /**
     * Push a (possibly parameterized / {@code <color>}-marked-up) localized
     * {@link Message} onto a rich button's inner label via {@code .TextSpans} - the
     * sink that actually substitutes {@code {0}/{1}} params and renders markup.
     *
     * @param cmd            the page command builder
     * @param buttonSelector the {@code Button} element selector (e.g.
     *                       {@code "#ChildList[0] #ChildBtn"}); the inner
     *                       {@link #LABEL} is appended here
     * @param text           the client-resolved message (a single key, a glue
     *                       composite, or a {@code Message.join}) - all render correctly
     *                       on a {@code .TextSpans}
     */
    public static void text(@Nonnull UICommandBuilder cmd, @Nonnull String buttonSelector,
            @Nonnull Message text) {
        cmd.set(buttonSelector + " " + LABEL + ".TextSpans", text);
    }

    /**
     * Recolor a rich button's inner label text. A no-op for a null / non-6-digit-hex
     * value (the label keeps its authored color), mirroring {@link UiRetint}'s hex
     * contract. A {@code .set} against a label that is hidden / absent on the current
     * page is a harmless client-side no-op.
     *
     * @param hex a {@code #rrggbb} color, or null to leave the authored color
     */
    public static void color(@Nonnull UICommandBuilder cmd, @Nonnull String buttonSelector,
            @Nullable String hex) {
        if (UiRetint.isSixDigitHex(hex)) {
            cmd.set(buttonSelector + " " + LABEL + ".Style.TextColor", hex);
        }
    }

    /**
     * Show a leading (start-of-button) icon - a UI glyph texture - on a {@code @ZigRichBtn}
     * component, with an optional tint. The same call with {@link #iconEnd} on the trailing
     * slot is how ONE component places an icon at the start OR the end (or both). Sets the
     * slot's {@code .Background} to a typed {@link PatchStyle} (never a bare String, which
     * red-Xes) and flips it {@code Visible}.
     *
     * <p>ONLY valid on a button that actually carries the {@link #ICON_START} slot (a
     * {@code @ZigRichBtn} instance, or a hand-authored Button that includes it) - pushing at a
     * slot the page does not contain can fault the CustomUI batch. A bare
     * {@code Button + Label} row has no icon slots; do not call this on it.
     *
     * @param texturePath the glyph texture path (consumer-chosen form), WITHOUT {@code @2x}
     * @param tintHex     optional {@code #rrggbb} tint applied to the glyph; null = as authored
     */
    public static void iconStart(@Nonnull UICommandBuilder cmd, @Nonnull String buttonSelector,
            @Nonnull String texturePath, @Nullable String tintHex) {
        setIcon(cmd, buttonSelector + " " + ICON_START, texturePath, tintHex);
    }

    /** Leading icon with no tint. See {@link #iconStart(UICommandBuilder, String, String, String)}. */
    public static void iconStart(@Nonnull UICommandBuilder cmd, @Nonnull String buttonSelector,
            @Nonnull String texturePath) {
        setIcon(cmd, buttonSelector + " " + ICON_START, texturePath, null);
    }

    /**
     * Show a trailing (end-of-button) icon on a {@code @ZigRichBtn} component, with an optional
     * tint. The end-position counterpart of {@link #iconStart}.
     */
    public static void iconEnd(@Nonnull UICommandBuilder cmd, @Nonnull String buttonSelector,
            @Nonnull String texturePath, @Nullable String tintHex) {
        setIcon(cmd, buttonSelector + " " + ICON_END, texturePath, tintHex);
    }

    /** Trailing icon with no tint. See {@link #iconEnd(UICommandBuilder, String, String, String)}. */
    public static void iconEnd(@Nonnull UICommandBuilder cmd, @Nonnull String buttonSelector,
            @Nonnull String texturePath) {
        setIcon(cmd, buttonSelector + " " + ICON_END, texturePath, null);
    }

    /** Hide both icon slots on a {@code @ZigRichBtn} (e.g. on a row that has no icon this render). */
    public static void hideIcons(@Nonnull UICommandBuilder cmd, @Nonnull String buttonSelector) {
        cmd.set(buttonSelector + " " + ICON_START + ".Visible", false);
        cmd.set(buttonSelector + " " + ICON_END + ".Visible", false);
    }

    private static void setIcon(@Nonnull UICommandBuilder cmd, @Nonnull String iconSelector,
            @Nonnull String texturePath, @Nullable String tintHex) {
        PatchStyle patch = new PatchStyle().setTexturePath(Value.of(texturePath));
        if (UiRetint.isSixDigitHex(tintHex)) {
            patch.setColor(Value.of(tintHex));
        }
        cmd.setObject(iconSelector + ".Background", patch);
        cmd.set(iconSelector + ".Visible", true);
    }
}
