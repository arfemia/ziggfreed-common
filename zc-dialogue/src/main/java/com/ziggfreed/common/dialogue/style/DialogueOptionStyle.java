package com.ziggfreed.common.dialogue.style;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.type.DialogueActionType;
import com.ziggfreed.common.ui.kit.ZigTokens;

/**
 * The semantic look of a dialogue option row: a per-state button tint family plus
 * an optional leading glyph element id. An option's kind is DERIVED from its
 * decisive action's registered kind (a consumer action declares its kind via
 * {@link DialogueActionType}); the page overwrites the shared option button's
 * three {@code .Background.Color} states and reveals the matching pre-authored
 * glyph in the row's {@code #OptIcon} gutter (the glyph TEXTURE lives in markup;
 * a {@code "../Common/..."} path set from Java renders a red X). An authored
 * {@code Style} picks only the tint; the glyph always follows what the option does
 * ({@link DialogueOptionGlyphs}).
 *
 * <p>Every tint is a kit token ({@link ZigTokens}, the mirror of {@code Common/ZigTokens.ui}), multiplying the
 * game's Secondary button patch the row sits on: an accept is the done tone, a hand-in the active tone (each its text
 * colour at rest and under the cursor, its fill pressed), talking on and opening a screen the strong ink (the patch
 * near as the game draws it, the bright ink under the cursor), and goodbye the muted ink. The button's own hovered and
 * pressed patches lighten and darken it, so a tone that keeps one colour still answers the cursor. The shipped
 * {@code DialogueOptionTheme/<Kind>.json} files carry the same values. The glyph element ids match the hidden
 * children in {@code Pages/ZigDialogueOptionRow.ui}, whose tints are the same tokens.
 */
public enum DialogueOptionStyle {

    /** Positive / accept. The done tone, with a checkmark. */
    ACCEPT("accept", ZigTokens.TONE_DONE_TEXT, ZigTokens.TONE_DONE_TEXT, ZigTokens.TONE_DONE_FILL, "#IcoAccept"),
    /** Hand-off / turn in. The active tone, with a checkmark. */
    TURN_IN("turnin", ZigTokens.TONE_ACTIVE_TEXT, ZigTokens.TONE_ACTIVE_TEXT, ZigTokens.TONE_ACTIVE_FILL,
            "#IcoTurnIn"),
    /** Jump to another node (re-renders elsewhere): talking on. The strong ink, with a speech bubble. */
    CONTINUE("continue", ZigTokens.INK_STRONG, ZigTokens.INK_BRIGHT, ZigTokens.INK_BODY, "#IcoTalk"),
    /** Open another page / nav. The strong ink, with a gold "open" chevron. */
    NEUTRAL("neutral", ZigTokens.INK_STRONG, ZigTokens.INK_BRIGHT, ZigTokens.INK_BODY, "#IcoOpen"),
    /** End the dialogue. The muted ink, with an X. */
    FAREWELL("farewell", ZigTokens.INK_MUTED, ZigTokens.INK_BODY, ZigTokens.MARK_UNLIT, "#IcoFarewell");

    private final String key;
    private final String tintDefault;
    private final String tintHovered;
    private final String tintPressed;
    @Nullable
    private final String iconElementId;

    DialogueOptionStyle(@Nonnull String key, @Nonnull String tintDefault, @Nonnull String tintHovered,
                        @Nonnull String tintPressed, @Nullable String iconElementId) {
        this.key = key;
        this.tintDefault = tintDefault;
        this.tintHovered = tintHovered;
        this.tintPressed = tintPressed;
        this.iconElementId = iconElementId;
    }

    /**
     * The stable asset id this kind resolves through in {@link DialogueOptionThemeConfig}
     * (the {@code Server/ZiggfreedCommon/DialogueOptionTheme/<key>.json} filename, lower-cased).
     */
    @Nonnull public String key() { return key; }

    /** The style whose {@link #key()} equals {@code key} (case-insensitive), or null if none. */
    @Nullable
    public static DialogueOptionStyle byKey(@Nonnull String key) {
        for (DialogueOptionStyle s : values()) {
            if (s.key.equalsIgnoreCase(key)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Every {@link #key()} in declaration order: the whole vocabulary an option's {@code Style}
     * leaf accepts, which is what the editor offers as its dropdown.
     */
    @Nonnull
    public static String[] keys() {
        DialogueOptionStyle[] all = values();
        String[] keys = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            keys[i] = all[i].key;
        }
        return keys;
    }

    @Nonnull public String tintDefault() { return tintDefault; }

    @Nonnull public String tintHovered() { return tintHovered; }

    @Nonnull public String tintPressed() { return tintPressed; }

    /** The pre-authored glyph element id in the row's {@code #OptIcon} gutter, or null for no glyph. */
    @Nullable public String iconElementId() { return iconElementId; }
}
