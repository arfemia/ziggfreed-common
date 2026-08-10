package com.ziggfreed.common.ui;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * The ONE generic, mod-agnostic palette-to-selector retint primitive for custom
 * UI pages - the runtime {@code PatchStyle} recolor / texture-swap mechanism that
 * several consumers had each re-derived inline. It takes a RESOLVED hex / texture
 * path + a selector as PARAMETERS and pushes them onto an already-appended
 * element; it carries NO theme model, NO palette type, NO entitlement / gate, and
 * NO consumer policy. A consumer (the MMO's {@code ThemeRetint}, a minigame's
 * skin) owns the policy - which selectors, which palette, whether a theme is
 * gated - and delegates each leaf push to here.
 *
 * <p><b>Three shipped mechanisms, source-validated against the official Hytale
 * shared source</b> (see {@code .claude/research/hytale-ui-runtime-patchstyle-textures.md}
 * in the hyMMO repo):
 * <ul>
 *   <li><b>{@link #retintColor} (CONFIRMED):</b> a {@code .Background.Color}
 *       sub-property set that RETINTS the existing 9-slice texture in place
 *       (multiply tint; a white / neutral base tints to any vivid color). The
 *       single proven, zero-new-asset recolor lever.</li>
 *   <li><b>{@link #retintButtonStates} (CONFIRMED on an element instance):</b> the
 *       per-state {@code .Style.{Default,Hovered,Pressed}.Background.Color} push on
 *       a LIVE button element id - the technique the dialogue option rows already
 *       ship (one shared button style instance retinted per option kind).</li>
 *   <li><b>{@link #swapPatch} (type-supported; runtime texture-PATH FORM is the one
 *       in-game-verify nuance):</b> a WHOLE replacement {@code PatchStyle}
 *       ({@code TexturePath} + {@code Border} + optional tint) sent via
 *       {@code setObject} - the typed, codec-registered path
 *       ({@code PatchStyle.CODEC} is in {@code UICommandBuilder.CODEC_MAP}). The
 *       Color retint needs no such check; only a Java-sent {@code TexturePath}'s
 *       resolution form is unproven on a live client.</li>
 * </ul>
 *
 * <p>It NEVER sets a bare-String {@code .Background} (a String overwrites the whole
 * typed {@code PatchStyle} and renders the missing-texture red X); the swap always
 * sends a typed {@code PatchStyle} object, and the color forms only ever touch the
 * {@code .Background.Color} / {@code .Style.*.Background.Color} sub-properties.
 *
 * <p><b>Hex contract:</b> only a 6-digit {@code #rrggbb} string is pushed
 * ({@link #isSixDigitHex}); the runtime color parser accepts {@code #rrggbb} /
 * {@code #rrggbbaa} / {@code rgb()} for a {@code cmd.set}, and the parenthesized
 * {@code #rrggbb(alpha)} form is {@code .ui}-markup-only. Each color push is
 * SKIPPED for a null / non-hex value, leaving the element at its authored color -
 * so a partial palette paints only the slots it declares and never red-Xes.
 */
public final class UiRetint {

    private UiRetint() {
        // static primitive
    }

    /**
     * Retint an element's background in place: {@code cmd.set(selector +
     * ".Background.Color", hex)}. A no-op for a null / non-6-digit-hex value (the
     * element keeps its authored color). A {@code .set} against a missing id is a
     * harmless client-side no-op, so calling this for an element absent on the
     * current page is safe.
     */
    public static void retintColor(@Nonnull UICommandBuilder cmd, @Nonnull String selector,
            @Nullable String hex) {
        if (!isSixDigitHex(hex)) {
            return;
        }
        cmd.set(selector + ".Background.Color", hex);
    }

    /**
     * Retint a live button element's three interaction states by overwriting its
     * inline {@code .Style.{Default,Hovered,Pressed}.Background.Color}. This is the
     * CONFIRMED per-state tint the dialogue option rows ship (a single shared
     * button style instance retinted per option kind). Each state is pushed
     * independently and SKIPPED for a null / non-hex value, so passing a single
     * slot color for all three (a flat tint) or three distinct shades both work.
     *
     * <p>{@code selector} must address a LIVE button ELEMENT id (e.g.
     * {@code "#OptionsList[2] #OptionBtn"}); this is the proven form. Addressing a
     * shared {@code @StyleName} style DEFINITION by name is a DIFFERENT, unproven
     * selector grammar - see {@link #retintStyleStates}.
     */
    public static void retintButtonStates(@Nonnull UICommandBuilder cmd, @Nonnull String selector,
            @Nullable String def, @Nullable String hovered, @Nullable String pressed) {
        if (isSixDigitHex(def)) {
            cmd.set(selector + ".Style.Default.Background.Color", def);
        }
        if (isSixDigitHex(hovered)) {
            cmd.set(selector + ".Style.Hovered.Background.Color", hovered);
        }
        if (isSixDigitHex(pressed)) {
            cmd.set(selector + ".Style.Pressed.Background.Color", pressed);
        }
    }

    /**
     * Retint a shared, named {@code @StyleName} {@code TextButtonStyle} DEFINITION
     * by overwriting its three per-state {@code Default/Hovered/Pressed.Background.Color}
     * (note: NO {@code .Style.} prefix - a style definition's states are addressed
     * directly, unlike a live element instance in {@link #retintButtonStates}).
     *
     * <p><b>BEST-GUESS / in-game-verify:</b> retinting EVERY button that references
     * a shared menu style from ONE set, rather than enumerating per-page element
     * ids, requires this named-style selector form, which is NOT yet confirmed on a
     * live client (the proven form is the element-instance one above). If the form
     * does not resolve it is expected to be a harmless no-op (like any unmatched
     * selector); a consumer wanting a guaranteed effect should fall back to the
     * proven {@link #retintButtonStates} on each rendered button element id. Each
     * state is skipped for a null / non-hex value.
     *
     * @param styleName the bare style name WITH its {@code @} sigil (e.g.
     *                  {@code "@MmoAcceptBtnStyle"})
     */
    public static void retintStyleStates(@Nonnull UICommandBuilder cmd, @Nonnull String styleName,
            @Nullable String def, @Nullable String hovered, @Nullable String pressed) {
        if (isSixDigitHex(def)) {
            cmd.set(styleName + ".Default.Background.Color", def);
        }
        if (isSixDigitHex(hovered)) {
            cmd.set(styleName + ".Hovered.Background.Color", hovered);
        }
        if (isSixDigitHex(pressed)) {
            cmd.set(styleName + ".Pressed.Background.Color", pressed);
        }
    }

    /**
     * Swap the whole {@code .Background} {@code PatchStyle} on {@code selector} to a
     * bespoke texture set: a new {@code TexturePath} + a {@code Border} 9-slice
     * inset, plus an OPTIONAL tint Color (normally omitted - bespoke art is
     * pre-colored, so a multiply tint would crush it; pass null to render the art as
     * authored). Sent as a typed {@code PatchStyle} object via {@code setObject}
     * ({@code PatchStyle.CODEC} is registered in {@code UICommandBuilder.CODEC_MAP}),
     * so this is the supported typed path and NEVER a bare String. A
     * {@code setObject} against a missing id is a harmless client-side no-op.
     *
     * <p><b>In-game-verify:</b> the runtime resolution FORM for a Java-sent
     * {@code TexturePath} is the one unconfirmed nuance (the Color forms above are
     * proven). A Java-pushed path has no {@code .ui} context, so the texture must be
     * a loaded asset and the path form (root-relative vs sibling-relative) may need
     * a live-client check; if a client renders a missing-texture red X, the
     * validated fallback is a whole-{@code .ui} swap via the consumer's template
     * override. The consumer owns which form it sends.
     *
     * @param texturePath the bespoke texture path (consumer-chosen form), WITHOUT
     *                    {@code @2x} (the engine auto-resolves {@code Name.png} ->
     *                    {@code Name@2x.png})
     * @param border      the 9-slice border inset
     * @param tintHex     optional {@code #rrggbb} tint; applied only when valid
     */
    public static void swapPatch(@Nonnull UICommandBuilder cmd, @Nonnull String selector,
            @Nonnull String texturePath, int border, @Nullable String tintHex) {
        PatchStyle patch = new PatchStyle()
                .setTexturePath(Value.of(texturePath))
                .setBorder(Value.of(border));
        if (isSixDigitHex(tintHex)) {
            patch.setColor(Value.of(tintHex));
        }
        cmd.setObject(selector + ".Background", patch);
    }

    /** True only for a {@code #rrggbb} hex string (the validated retint form). */
    public static boolean isSixDigitHex(@Nullable String hex) {
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') {
            return false;
        }
        for (int i = 1; i < 7; i++) {
            char c = hex.charAt(i);
            boolean isHexDigit = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!isHexDigit) {
                return false;
            }
        }
        return true;
    }
}
