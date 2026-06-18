package com.ziggfreed.common.dialogue;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One screen of a dialogue: localized NPC text plus the selectable
 * {@link DialogueOption}s. Node ids are MAP KEYS in the dialogue's {@code Nodes}
 * field - plain data, so lowercase ids ({@code greet}, {@code camp_talk}) are
 * legal. A pure data POJO; its codec is assembled by {@link DialogueEngine}.
 */
public class DialogueNode {

    @Nullable String textKey;
    @Nullable String text;
    @Nullable DialogueOption[] options;

    public DialogueNode() {
    }

    /** Explicit i18n key for the NPC text, or null (by-convention key, then raw fallback). */
    @Nullable public String getTextKey() { return textKey; }

    /** Deprecated raw text fallback, or null. */
    @Nullable public String getText() { return text; }

    @Nonnull
    public List<DialogueOption> getOptions() {
        return options == null ? Collections.emptyList() : List.of(options);
    }
}
