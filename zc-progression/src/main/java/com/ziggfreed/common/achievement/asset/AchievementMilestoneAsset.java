package com.ziggfreed.common.achievement.asset;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.achievement.AchievementMilestone;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.ContentRewardsAsset;

/**
 * A reward for reaching a running TOTAL of achievement points, rather than for any one achievement.
 * Authored at {@code Server/ZiggfreedCommon/AchievementMilestones/<name>.json}; the filename is just
 * a label, and {@code Threshold} is the milestone's real identity (it is what a player's record
 * stores, so two files naming the same threshold are the same milestone and the later one wins).
 *
 * <pre>{@code
 * {
 *   "Threshold": 500,
 *   "Rewards": {
 *     "Auto":  [ { "Kind": "Yourmod_Coin", "Params": { "Id": "coin", "Amount": "50" } } ],
 *     "Claim": [ { "Kind": "Item", "Params": { "Item": "Ingredient_Bar_Iron", "Count": "5" } } ]
 *   }
 * }
 * }</pre>
 *
 * <p><b>A reward is a registered KIND plus that kind's own parameters</b>, the same entry shape as a
 * quest's or an achievement's, so a payout written for one reads and behaves identically on the
 * next. {@code Item}, {@code Lootable}, {@code Stamped_Item}, {@code Effect} and {@code Droplist}
 * come with the framework; a kind a mod brings carries that mod's prefix. Matching is
 * case-insensitive, and which parameters a kind reads is documented by whoever registered it.
 *
 * <p><b>Two reward lists, because there are two moments.</b> {@code Auto} is paid the instant the
 * threshold is crossed, wherever the player happens to be. {@code Claim} waits on a surface for them
 * to collect it, which is what you want for anything that needs backpack room: a player with a full
 * bag keeps the reward instead of losing it on the floor.
 *
 * <p>Title and description are translation keys, so every player reads them in their own language.
 * Leave both out and a surface falls back to whatever it does by convention for a threshold.
 *
 * <p>Folders under the type root are organisational: the id comes from the FILE name alone, so
 * {@code AchievementMilestones/YourMod/Points_500.json} keys plainly off {@code Points_500}. What
 * actually decides identity is the {@code Threshold} inside it.
 */
public final class AchievementMilestoneAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, AchievementMilestoneAsset>> {

    /** The store's content path under a pack's {@code Server/}. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/AchievementMilestones";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Integer threshold;
    @Nullable protected String titleKey;
    @Nullable protected String descriptionKey;
    @Nullable protected ContentRewardsAsset rewards;

    public static final AssetBuilderCodec<String, AchievementMilestoneAsset> CODEC = AssetBuilderCodec.builder(
                    AchievementMilestoneAsset.class,
                    AchievementMilestoneAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Threshold", Codec.INTEGER, false),
                    (a, v) -> a.threshold = v, a -> a.threshold, (a, p) -> a.threshold = p.threshold)
            .documentation("The points total that reaches this milestone, and its real identity. Two files "
                    + "naming the same number are the same milestone, whatever they are called, so re-tune a rung "
                    + "by authoring its threshold rather than by matching a filename.")
            .add()
            .appendInherited(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                    (a, v) -> a.titleKey = v, a -> a.titleKey, (a, p) -> a.titleKey = p.titleKey)
            .documentation("The translation key naming this rung, so every player reads it in their own "
                    + "language. Unauthored leaves the name to whatever the surface does by convention.")
            .add()
            .appendInherited(new KeyedCodec<>("DescriptionKey", Codec.STRING, false),
                    (a, v) -> a.descriptionKey = v, a -> a.descriptionKey,
                    (a, p) -> a.descriptionKey = p.descriptionKey)
            .documentation("The translation key describing what reaching it takes. Unauthored leaves the line "
                    + "to the surface, which usually says the number itself.")
            .add()
            .appendInherited(new KeyedCodec<>("Rewards", ContentRewardsAsset.CODEC, false),
                    (a, v) -> a.rewards = v, a -> a.rewards, (a, p) -> a.rewards = p.rewards)
            .documentation("What crossing it pays, split by the two moments a payout can land in.")
            .add()
            .build();

    public AchievementMilestoneAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static AchievementMilestoneAsset of(@Nonnull String id, int threshold,
            @Nullable String titleKey, @Nullable String descriptionKey,
            @Nullable ContentRewardsAsset rewards) {
        AchievementMilestoneAsset a = new AchievementMilestoneAsset();
        a.id = id;
        a.threshold = threshold;
        a.titleKey = titleKey;
        a.descriptionKey = descriptionKey;
        a.rewards = rewards;
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    /** The points total that reaches this milestone, and its identity; 0 when the file names none. */
    public int getThreshold() {
        return threshold == null ? 0 : threshold;
    }

    @Nullable
    public String getTitleKey() {
        return titleKey == null || titleKey.isBlank() ? null : titleKey;
    }

    @Nullable
    public String getDescriptionKey() {
        return descriptionKey == null || descriptionKey.isBlank() ? null : descriptionKey;
    }

    @Nullable
    public ContentRewardsAsset getRewards() {
        return rewards;
    }

    /** Paid the instant the threshold is crossed; empty when the file authors none. */
    @Nonnull
    public List<RewardSpec> autoRewards() {
        ContentRewardsAsset r = rewards;
        return r == null ? List.of() : r.auto();
    }

    /** Paid when the player collects; empty when the file authors none. */
    @Nonnull
    public List<RewardSpec> claimRewards() {
        ContentRewardsAsset r = rewards;
        return r == null ? List.of() : r.claim();
    }

    /** This asset as the milestone the achievement engine reads. */
    @Nonnull
    public AchievementMilestone toMilestone() {
        return new AchievementMilestone(getThreshold(), autoRewards(), claimRewards());
    }
}
