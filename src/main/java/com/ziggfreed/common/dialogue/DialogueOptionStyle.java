package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The semantic look of a dialogue option row: a per-state button tint family plus
 * an optional leading glyph element id. An option's style is DERIVED from its
 * decisive action's registered kind (a consumer action declares its kind via
 * {@link DialogueActionType}); the page overwrites the shared option button's
 * three {@code .Background.Color} states and reveals the matching pre-authored
 * glyph in the row's {@code #OptIcon} gutter (the glyph TEXTURE lives in markup;
 * a {@code "../Common/..."} path set from Java renders a red X).
 *
 * <p>The tint hexes match the families in the shared {@code Common/ZigButtons.ui}
 * comment vocabulary (green = accept, blue = turn-in, steel = neutral/continue,
 * muted = farewell). The glyph element ids match the hidden children in
 * {@code Pages/ZigDialogueOptionRow.ui}.
 */
public enum DialogueOptionStyle {

    /** Positive / accept. Green, with a checkmark. */
    ACCEPT("#4aff7f", "#7affa0", "#33cc66", "#IcoAccept"),
    /** Hand-off / turn in. Blue, with a checkmark. */
    TURN_IN("#5ab0ff", "#8acbff", "#3f8fd6", "#IcoTurnIn"),
    /** Jump to another node (re-renders elsewhere). Steel, with a right chevron. */
    CONTINUE("#b6c9de", "#d6e3f0", "#97aabf", "#IcoContinue"),
    /** Open another page / nav. Steel, with a gold "open" chevron. */
    NEUTRAL("#b6c9de", "#d6e3f0", "#97aabf", "#IcoOpen"),
    /** End the dialogue. Muted, with an X. */
    FAREWELL("#6f8398", "#9fb3c8", "#5a6e82", "#IcoFarewell");

    private final String tintDefault;
    private final String tintHovered;
    private final String tintPressed;
    @Nullable
    private final String iconElementId;

    DialogueOptionStyle(@Nonnull String tintDefault, @Nonnull String tintHovered,
                        @Nonnull String tintPressed, @Nullable String iconElementId) {
        this.tintDefault = tintDefault;
        this.tintHovered = tintHovered;
        this.tintPressed = tintPressed;
        this.iconElementId = iconElementId;
    }

    @Nonnull public String tintDefault() { return tintDefault; }

    @Nonnull public String tintHovered() { return tintHovered; }

    @Nonnull public String tintPressed() { return tintPressed; }

    /** The pre-authored glyph element id in the row's {@code #OptIcon} gutter, or null for no glyph. */
    @Nullable public String iconElementId() { return iconElementId; }
}
