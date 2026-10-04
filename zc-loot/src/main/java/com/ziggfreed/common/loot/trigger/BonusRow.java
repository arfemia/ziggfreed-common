package com.ziggfreed.common.loot.trigger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.LootRef;

/**
 * One of the library's own rows, folded: the moment, the pattern as authored, the lower-cased id it
 * is filed under, what it hands over (an empty ref for a hole) and its own percent odds.
 */
public record BonusRow(@Nonnull BonusMoment moment, @Nonnull String match, @Nonnull String sourceId,
        @Nonnull LootRef loot, @Nullable FactorFormula chance) implements BonusEntry {
}
