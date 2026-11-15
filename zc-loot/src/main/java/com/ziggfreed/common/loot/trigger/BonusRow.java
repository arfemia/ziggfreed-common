package com.ziggfreed.common.loot.trigger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.LootRef;

/**
 * One of the library's own rows, folded: the moment, the pattern as authored, the lower-cased id it
 * is filed under, what it hands over (an empty ref for a hole), its own percent odds, the group it
 * competes in (lower-cased, blank for the shared one) and whether the moment's yield adds to a
 * {@code Chance} it writes.
 */
public record BonusRow(@Nonnull BonusMoment moment, @Nonnull String match, @Nonnull String sourceId,
        @Nonnull LootRef loot, @Nullable FactorFormula chance, @Nonnull String group, boolean addsYield)
        implements BonusEntry {

    /** A row in the shared group whose written {@code Chance} takes the moment's yield, as every row before 3.0.0. */
    public BonusRow(@Nonnull BonusMoment moment, @Nonnull String match, @Nonnull String sourceId,
            @Nonnull LootRef loot, @Nullable FactorFormula chance) {
        this(moment, match, sourceId, loot, chance, "", true);
    }
}
