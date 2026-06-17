package com.ziggfreed.common.dialogue;

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
}
