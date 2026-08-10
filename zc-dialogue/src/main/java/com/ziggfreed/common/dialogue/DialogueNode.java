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
    @Nullable DialogueCondition[] conditions;
    @Nullable DialogueOption[] options;

    public DialogueNode() {
    }

    /** Explicit i18n key for the NPC text, or null (by-convention key, then raw fallback). */
    @Nullable public String getTextKey() { return textKey; }

    /** Deprecated raw text fallback, or null. */
    @Nullable public String getText() { return text; }

    /**
     * Optional AND-combined visibility conditions on the node itself. When a
     * {@code Start} candidate resolves to this node id, the engine additionally
     * requires these to pass - so a node self-declares "only show me while X",
     * collapsing the old {@code (node x state)} duplication + {@code PruneIfEmpty}.
     * An empty/absent list always passes.
     */
    @Nonnull
    public List<DialogueCondition> getConditions() {
        return conditions == null ? Collections.emptyList() : List.of(conditions);
    }

    /** True when this node authored any visibility conditions. */
    public boolean hasConditions() {
        return conditions != null && conditions.length > 0;
    }

    @Nonnull
    public List<DialogueOption> getOptions() {
        return options == null ? Collections.emptyList() : List.of(options);
    }
}
