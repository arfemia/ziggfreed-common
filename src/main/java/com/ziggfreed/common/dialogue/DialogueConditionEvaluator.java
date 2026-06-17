package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;

/**
 * Evaluates one registered condition type for the current player. Bound to a
 * {@link DialogueConditionType} so a decodable condition always has an evaluator
 * (they cannot drift). The engine AND-combines every condition in an option's /
 * entry's {@code Conditions} array.
 */
@FunctionalInterface
public interface DialogueConditionEvaluator<C extends DialogueCondition> {

    boolean passes(@Nonnull C condition, @Nonnull DialogueContext ctx);
}
