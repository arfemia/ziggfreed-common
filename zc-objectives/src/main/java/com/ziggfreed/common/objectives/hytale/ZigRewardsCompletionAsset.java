package com.ziggfreed.common.objectives.hytale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.objectives.config.completion.ObjectiveCompletionAsset;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;

/**
 * A completion of one of Hytale's own Objectives that pays through CommonLib's reward kinds, as a Claim each
 * participant collects (M428):
 *
 * <pre>{@code
 * "Completions": [ { "Type": "ZigRewards",
 *                    "Rewards": [ { "Kind": "Lootable", "Params": { "Lootable": "Yourpack_Camp_Chest" } } ] } ]
 * }</pre>
 *
 * <p>{@code Rewards} is the entry shape a quest pays in (a Kind plus its Params, a row's own Requires), so every
 * kind another mod registers pays here the day it is installed. Registered at setup by
 * {@link NativeObjectivesBootstrap}; it pays through {@link ZigRewardsCompletion}.
 */
public final class ZigRewardsCompletionAsset extends ObjectiveCompletionAsset {

    /** The authored {@code "Type"} name. */
    public static final String TYPE = "ZigRewards";

    @Nonnull
    public static final BuilderCodec<ZigRewardsCompletionAsset> CODEC = BuilderCodec.builder(
                    ZigRewardsCompletionAsset.class, ZigRewardsCompletionAsset::new, ObjectiveCompletionAsset.BASE_CODEC)
            .documentation("Pays CommonLib rewards when the objective completes, as one Claim each participant "
                    + "collects on the Claims tab, online or not. Every ZigRewards and ZigTrack completion on one "
                    + "objective joins the same Claim.")
            .append(new KeyedCodec<>("Rewards", new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new),
                    false), (completion, rewards) -> completion.rewards = rewards, completion -> completion.rewards)
            .documentation("What the Claim pays, in the entry shape a quest pays in: a Kind plus its Params, and a "
                    + "row's own Requires. A row whose kind cannot be handed over later is dropped with a warning. "
                    + "Unauthored or empty, the completion pays nothing.")
            .add()
            .append(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                    (completion, key) -> completion.titleKey = key, completion -> completion.titleKey)
            .documentation("The lang key the Claim's row is titled with on the Claims tab. Unauthored, the "
                    + "objective's own title titles it when a line ships for it, else the tab's own word for a "
                    + "reward.")
            .add()
            .build();

    @Nullable
    private RewardEntryAsset[] rewards;
    @Nullable
    private String titleKey;

    private ZigRewardsCompletionAsset() {
    }

    /** The authored rows, or null when none were written. */
    @Nullable
    public RewardEntryAsset[] getRewards() {
        return rewards == null ? null : rewards.clone();
    }

    /** The authored title key, or null. */
    @Nullable
    public String getTitleKey() {
        return titleKey;
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigRewardsCompletionAsset{rewards=" + (rewards == null ? 0 : rewards.length) + ", titleKey="
                + titleKey + "} " + super.toString();
    }
}
