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
    @Nullable String[] includeOptions;

    /**
     * The option list once this screen's shared groups have been appended, or null while it has none
     * to append. It is kept BESIDE the authored list rather than replacing it, so the splice can
     * always start from what the file wrote: a screen carried down by {@code Parent} is the parent's
     * own object, and a splice that overwrote the authored list would both double the shared lines
     * for the child and change what the parent says.
     */
    @Nullable private transient DialogueOption[] splicedOptions;

    public DialogueNode() {
    }

    /**
     * The shared option groups this screen pulls in from the dialogue's {@code Fragments}, appended
     * after its own {@code Options}. Empty when it names none.
     */
    @Nonnull
    public List<String> getIncludeOptions() {
        return includeOptions == null ? Collections.emptyList() : List.of(includeOptions);
    }

    /**
     * A COPY of this screen carrying {@code spliced} as its option list, everything else unchanged.
     * Used once, right after decode, so the conversation that pulled the shared lines in gets its own
     * screen and the one it inherited from keeps hers.
     */
    @Nonnull
    DialogueNode withSplicedOptions(@Nullable DialogueOption[] spliced) {
        DialogueNode copy = new DialogueNode();
        copy.textKey = textKey;
        copy.text = text;
        copy.conditions = conditions;
        copy.options = options;
        copy.includeOptions = includeOptions;
        copy.splicedOptions = spliced;
        return copy;
    }

    /**
     * The options this screen's own file wrote, without any shared group appended. The splice reads
     * this rather than {@link #getOptions()} so re-splicing an inherited screen can never stack.
     */
    @Nonnull
    List<DialogueOption> getAuthoredOptions() {
        return options == null ? Collections.emptyList() : List.of(options);
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

    /** The lines shown on this screen: what it authored, plus any shared group it pulled in. */
    @Nonnull
    public List<DialogueOption> getOptions() {
        if (splicedOptions != null) {
            return List.of(splicedOptions);
        }
        return options == null ? Collections.emptyList() : List.of(options);
    }
}
