package com.ziggfreed.common.loot.reward;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The runtime table of authored {@link RewardKindAsset}s, folded {@code defaults < pack < owner} like
 * every other keyed asset type: a pack shipping a file with an existing id replaces that kind
 * outright, and a server owner's layer wins over both.
 *
 * <p>What is in this table is not yet a reward anybody can be paid. {@link RewardKindFold} is what
 * turns these entries into registered handlers, and it runs AFTER a consumer's own Java
 * registrations so an authored kind can deliberately shadow one - see that class for the rule and
 * what a shadowed kind gives up.
 */
public final class RewardKindConfig extends AbstractKeyedAssetConfig<RewardKindAsset> {

    private static final RewardKindConfig INSTANCE = new RewardKindConfig();

    @Nonnull
    public static RewardKindConfig getInstance() {
        return INSTANCE;
    }

    private RewardKindConfig() {
    }
}
