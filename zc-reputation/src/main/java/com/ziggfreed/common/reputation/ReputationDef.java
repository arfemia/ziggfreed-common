package com.ziggfreed.common.reputation;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.reputation.asset.ReputationAsset;

/**
 * One reputation this server knows and has switched on: the native group's own id (the engine's spelling),
 * its starting value, and the companion file when one exists. Every companion leaf reads its default when
 * there is no file.
 */
public record ReputationDef(@Nonnull String id, int initial, @Nullable ReputationAsset companion) {

    @Nullable
    public String titleKey() {
        return companion == null ? null : companion.titleKey();
    }

    @Nullable
    public String flavorKey() {
        return companion == null ? null : companion.flavorKey();
    }

    @Nullable
    public String icon() {
        return companion == null ? null : companion.icon();
    }

    public int order() {
        return companion == null ? 0 : companion.order();
    }

    @Nullable
    public String gearStat() {
        return companion == null ? null : companion.gearStat();
    }

    @Nullable
    public Integer cap() {
        return companion == null ? null : companion.cap();
    }

    @Nullable
    public String rankNameKey(@Nonnull String rankId) {
        return companion == null ? null : companion.rankNameKey(rankId);
    }

    @Nonnull
    public List<ReputationAsset.Kill> kills() {
        return companion == null ? List.of() : companion.kills();
    }

    public int beyondEvery() {
        return companion == null ? 0 : companion.beyondEvery();
    }

    @Nonnull
    public List<RewardSpec> beyondRewards() {
        return companion == null ? List.of() : companion.beyondRewards();
    }

    /** The authored How to earn lines' keys, in order; empty when none. */
    @Nonnull
    public List<String> earnKeys() {
        return companion == null ? List.of() : companion.earnKeys();
    }
}
