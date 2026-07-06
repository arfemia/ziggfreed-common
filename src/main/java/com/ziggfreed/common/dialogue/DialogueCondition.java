package com.ziggfreed.common.dialogue;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One visibility/eligibility condition on a dialogue OPTION or entry candidate,
 * authored as a {@code Type}-discriminated JSON object inside a {@code Conditions}
 * array (symmetric with {@link DialogueAction}). Conditions in the array are
 * AND-combined. Like actions, the dispatch codec is owned per-{@link DialogueEngine}
 * (pre-seeded with the generic conditions below, extended by the consumer via
 * {@link DialogueConditionType}), so a consumer adds a domain condition (quest
 * state, requirement gate, ...) without the engine knowing the domain.
 *
 * <p>Authored shape: {@code "Conditions": [ {"Type":"Flag","Flag":"met_elder"},
 * {"Type":"QuestState","Quest":"intro","State":"ACTIVE"} ]}. Each condition is
 * evaluated by its registered {@link DialogueConditionEvaluator}; the generic
 * {@code Flag}/{@code NotFlag} read dialogue-local memory through the context's
 * flag store.
 */
public abstract class DialogueCondition {

    /** Passes only when the per-player dialogue flag IS set. */
    public static final class Flag extends DialogueCondition {
        public static final BuilderCodec<Flag> CODEC = BuilderCodec.builder(Flag.class, Flag::new)
                .append(new KeyedCodec<>("Flag", Codec.STRING, false),
                        (c, v) -> c.flag = v, c -> c.flag).add()
                .build();

        @Nullable protected String flag;

        @Nullable public String getFlag() { return flag; }
    }

    /** Passes only when the per-player dialogue flag is NOT set. */
    public static final class NotFlag extends DialogueCondition {
        public static final BuilderCodec<NotFlag> CODEC = BuilderCodec.builder(NotFlag.class, NotFlag::new)
                .append(new KeyedCodec<>("Flag", Codec.STRING, false),
                        (c, v) -> c.flag = v, c -> c.flag).add()
                .build();

        @Nullable protected String flag;

        @Nullable public String getFlag() { return flag; }
    }

    /**
     * A boolean combinator over child conditions. Its child-list codec is the
     * per-{@link DialogueEngine} {@code conditionsArray}, so (unlike the leaf
     * conditions above) it has NO static {@code CODEC} - the engine builds and
     * registers {@code AllOf}/{@code AnyOf}/{@code Not} in {@code Builder.build()}
     * once that array exists, and evaluates them by delegating each child back
     * through {@link DialogueEngine#conditionsPass}. Authored shape:
     * {@code {"Type":"AnyOf","Any":[ {"Type":"QuestState",...}, ... ]}}.
     */
    public abstract static class Combinator extends DialogueCondition {
        @Nullable protected DialogueCondition[] children;

        @Nonnull
        public List<DialogueCondition> getChildren() {
            return children == null ? Collections.emptyList() : List.of(children);
        }
    }

    /** Passes when EVERY child condition passes (an empty list passes). Key: {@code All}. */
    public static final class AllOf extends Combinator {
    }

    /** Passes when AT LEAST ONE child condition passes (an empty list FAILS). Key: {@code Any}. */
    public static final class AnyOf extends Combinator {
    }

    /** Passes when the child conditions do NOT all pass (AND then negate). Key: {@code Of}. */
    public static final class Not extends Combinator {
    }
}
