package com.ziggfreed.common.instance.metadata;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How a round's {@link RoundMetadata} reads as a moment's target and qualifier: the one spelling the progression
 * producer fires a round's moments under and a stat counts a round on, so a step, a stat line and a best time read
 * from the same round never disagree. A round of mod {@code dungeon}, mode {@code hard}, preset {@code My_Crypt}
 * reads target {@code dungeon:hard}, qualifier {@code My_Crypt}, and a stat whose {@code Breakdown} is
 * {@code TargetAtQualifier} files it on the line {@code dungeon:hard@My_Crypt}.
 */
public final class RoundKeys {

    private RoundKeys() {
    }

    /**
     * {@code <modId>:<modeId>}. The colon is always written, so a round with no mode is still matched by the
     * {@code <modId>:} prefix an author writes to mean "any mode of that mod".
     */
    @Nonnull
    public static String target(@Nonnull RoundMetadata metadata) {
        String modeId = metadata.modeId();
        return metadata.modId() + ":" + (modeId == null ? "" : modeId.trim());
    }

    /** The preset id, trimmed, or null when the round named none (a qualifier is optional everywhere). */
    @Nullable
    public static String qualifier(@Nonnull RoundMetadata metadata) {
        String presetId = metadata.presetId();
        if (presetId == null) {
            return null;
        }
        String trimmed = presetId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
