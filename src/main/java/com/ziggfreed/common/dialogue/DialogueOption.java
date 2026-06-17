package com.ziggfreed.common.dialogue;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One selectable line in a dialogue node: a localized label, an optional list of
 * AND-combined {@link DialogueCondition}s (option hidden while they fail;
 * re-evaluated on every render AND again on click), and an ordered list of
 * {@link DialogueAction}s. An option with no Goto/Close re-renders its node. A
 * pure data POJO; its codec is assembled by {@link DialogueEngine}.
 */
public class DialogueOption {

    @Nullable String labelKey;
    @Nullable String label;
    @Nullable DialogueCondition[] conditions;
    @Nullable DialogueAction[] actions;

    public DialogueOption() {
    }

    /** Explicit i18n key for the option label, or null (by-convention key, then raw fallback). */
    @Nullable public String getLabelKey() { return labelKey; }

    /** Deprecated raw label fallback, or null. */
    @Nullable public String getLabel() { return label; }

    @Nonnull
    public List<DialogueCondition> getConditions() {
        return conditions == null ? Collections.emptyList() : List.of(conditions);
    }

    /** True when this option authored any conditions (an empty/absent list always passes). */
    public boolean hasConditions() {
        return conditions != null && conditions.length > 0;
    }

    @Nonnull
    public List<DialogueAction> getActions() {
        return actions == null ? Collections.emptyList() : List.of(actions);
    }
}
