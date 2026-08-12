package com.ziggfreed.common.loot.stamp;

import javax.annotation.Nonnull;

/**
 * ONE finished stat award: this many points of this stat, ready to be written onto an item.
 *
 * <p>The stat id is OPAQUE here. This layer rolls numbers and enforces budgets; what a stat means,
 * and what wearing it does, belongs entirely to the {@link Stamper} that writes it.
 */
public record StatRoll(@Nonnull String statId, int points) {
}
