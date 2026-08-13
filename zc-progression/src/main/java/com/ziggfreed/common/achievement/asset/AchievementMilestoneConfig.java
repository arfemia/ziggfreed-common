package com.ziggfreed.common.achievement.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.achievement.AchievementMilestone;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The folded {@link AchievementMilestoneAsset} layer: the points ladder, resolved
 * {@code defaults < pack < owner} by id like every other framework config.
 *
 * <p>A milestone's identity is its {@code Threshold}, not its filename, so {@link #milestones()}
 * collapses two files naming the same number into one rung and hands the result back ascending,
 * which is the order a ladder is read and paid in.
 *
 * <p>Read it LAZILY: the layer is filled by the asset store's load event, which runs after every
 * plugin's {@code setup()}.
 */
public final class AchievementMilestoneConfig extends AbstractKeyedAssetConfig<AchievementMilestoneAsset> {

    private static final AchievementMilestoneConfig INSTANCE = new AchievementMilestoneConfig();

    @Nonnull
    public static AchievementMilestoneConfig getInstance() {
        return INSTANCE;
    }

    private AchievementMilestoneConfig() {
    }

    /**
     * Every folded milestone that names a real threshold, one per threshold, ascending.
     *
     * <p>A file naming no threshold (or naming zero) reaches nothing and is dropped rather than
     * paying out the moment a player earns their first point.
     */
    @Nonnull
    public List<AchievementMilestone> milestones() {
        Map<Integer, AchievementMilestone> byThreshold = new LinkedHashMap<>();
        for (AchievementMilestoneAsset asset : all().values()) {
            if (asset != null && asset.getThreshold() > 0) {
                byThreshold.put(asset.getThreshold(), asset.toMilestone());
            }
        }
        List<AchievementMilestone> out = new ArrayList<>(byThreshold.values());
        out.sort(Comparator.comparingInt(AchievementMilestone::threshold));
        return out;
    }

    /** The folded assets themselves, one per threshold, ascending. */
    @Nonnull
    public List<AchievementMilestoneAsset> assetsByThreshold() {
        Map<Integer, AchievementMilestoneAsset> byThreshold = new LinkedHashMap<>();
        for (AchievementMilestoneAsset asset : all().values()) {
            if (asset != null && asset.getThreshold() > 0) {
                byThreshold.put(asset.getThreshold(), asset);
            }
        }
        List<AchievementMilestoneAsset> out = new ArrayList<>(byThreshold.values());
        out.sort(Comparator.comparingInt(AchievementMilestoneAsset::getThreshold));
        return out;
    }
}
