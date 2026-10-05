package com.ziggfreed.common.objectives.title;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * How a {@code Title} reward READS, contributed process-wide so no reward has to say it: "Title:"
 * and the title's own name ({@link TitleText#chip}). It answers only where the generic reading found
 * nothing, so an authored {@code NameKey} still wins.
 */
public final class TitleChipReading {

    private TitleChipReading() {
    }

    /** The reading; the title bootstrap contributes it once at setup. */
    @Nonnull
    public static RewardChips.Source source() {
        return TitleChipReading::chipFor;
    }

    @Nullable
    private static RewardChip chipFor(@Nonnull RewardSpec spec) {
        if (!TitleRewardKind.KIND.equalsIgnoreCase(spec.kind())) {
            return null;
        }
        String titleId = TitleRewardKind.titleOf(spec);
        return titleId.isEmpty() ? null : RewardChip.of(null, TitleText.chip(titleId));
    }
}
