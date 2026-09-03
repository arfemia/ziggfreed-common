package com.ziggfreed.common.objectives.flair;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * How a {@code Flair} reward READS, contributed process-wide so no reward has to say it: the
 * flair's own name on the ladder {@link FlairText#nameOf} keeps ({@code flair.<id>.name} from
 * whichever loaded lang file ships it, else the id spelled out), with no picture of its own.
 *
 * <p>It exists because the kind is Java-registered: there is no kind FILE to carry a
 * {@code Presentation}, and asking every quest and achievement that pays a flair to author a
 * {@code NameKey} would put a flair's display name in every file that grants it instead of the one
 * lang entry that owns it. Contributed through {@link RewardChips#contribute}, so it answers only
 * where the generic reading found nothing: a reward's own {@code NameKey}, when one IS authored,
 * still wins, and a reward's own {@code Icon} re-points the picture on this rung too.
 */
public final class FlairChipReading {

    private FlairChipReading() {
    }

    /** The reading; the flair bootstrap contributes it once at setup. */
    @Nonnull
    public static RewardChips.Source source() {
        return FlairChipReading::chipFor;
    }

    @Nullable
    private static RewardChip chipFor(@Nonnull RewardSpec spec) {
        if (!FlairRewardKind.KIND.equalsIgnoreCase(spec.kind())) {
            return null;
        }
        // The same read the PAYOUT makes, both spellings included.
        String flairId = FlairRewardKind.flairOf(spec);
        if (flairId.isEmpty()) {
            return null;
        }
        return RewardChip.of(null, FlairText.nameOf(flairId));
    }
}
