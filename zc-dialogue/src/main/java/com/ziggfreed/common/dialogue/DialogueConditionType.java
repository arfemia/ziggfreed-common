package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.Codec;

/**
 * A registrable dialogue condition type, symmetric with {@link DialogueActionType}:
 * the {@code Type} discriminator, the condition class, its field codec, and the
 * {@link DialogueConditionEvaluator}. A consumer registers one per domain
 * condition (quest state, requirement gate, ...) and the engine wires it into the
 * condition dispatch codec + the evaluator map.
 */
public final class DialogueConditionType<C extends DialogueCondition> {

    private final String typeId;
    private final Class<C> conditionClass;
    private final Codec<C> codec;
    private final DialogueConditionEvaluator<C> evaluator;

    private DialogueConditionType(@Nonnull String typeId, @Nonnull Class<C> conditionClass,
                                 @Nonnull Codec<C> codec, @Nonnull DialogueConditionEvaluator<C> evaluator) {
        this.typeId = typeId;
        this.conditionClass = conditionClass;
        this.codec = codec;
        this.evaluator = evaluator;
    }

    @Nonnull
    public static <C extends DialogueCondition> DialogueConditionType<C> of(
            @Nonnull String typeId, @Nonnull Class<C> conditionClass,
            @Nonnull Codec<C> codec, @Nonnull DialogueConditionEvaluator<C> evaluator) {
        return new DialogueConditionType<>(typeId, conditionClass, codec, evaluator);
    }

    @Nonnull public String typeId() { return typeId; }

    @Nonnull public Class<C> conditionClass() { return conditionClass; }

    @Nonnull public Codec<C> codec() { return codec; }

    @Nonnull public DialogueConditionEvaluator<C> evaluator() { return evaluator; }
}
