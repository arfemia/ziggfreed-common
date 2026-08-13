package com.ziggfreed.common.achievement.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The folded {@link AchievementCategoryAsset} layer: how each grouping label is presented, resolved
 * {@code defaults < pack < owner} by id like every other framework config.
 *
 * <p>Presentation only. A category exists because content filed itself under that word; this says
 * where the word sits, what illustrates it, what it is called, and how its subcategories read. A
 * category no file here mentions still works, and simply gets whatever a surface draws for a group
 * nobody described.
 *
 * <p>Read it LAZILY: the layer is filled by the asset store's load event, which runs after every
 * plugin's {@code setup()}.
 */
public final class AchievementCategoryConfig extends AbstractKeyedAssetConfig<AchievementCategoryAsset> {

    private static final AchievementCategoryConfig INSTANCE = new AchievementCategoryConfig();

    @Nonnull
    public static AchievementCategoryConfig getInstance() {
        return INSTANCE;
    }

    private AchievementCategoryConfig() {
    }

    /**
     * Every folded category in READING order: by its own {@code Order} first, then by id so two
     * categories sharing a sort key (or naming none at all) still read the same way on every boot.
     */
    @Nonnull
    public List<AchievementCategoryAsset> ordered() {
        List<AchievementCategoryAsset> out = new ArrayList<>(all().values());
        out.sort(Comparator.comparingInt(AchievementCategoryAsset::orderOrLast)
                .thenComparing(a -> a.getId() == null ? "" : a.getId()));
        return out;
    }

    /** The ids of every category that named an {@code Order}, in that order. */
    @Nonnull
    public List<String> orderedIds() {
        List<String> out = new ArrayList<>();
        for (AchievementCategoryAsset asset : ordered()) {
            String id = asset.getId();
            if (id != null && !id.isEmpty() && asset.getOrder() != null) {
                out.add(id);
            }
        }
        return out;
    }

    /** The presentation for one category, or null when no file describes it. */
    @Nullable
    public AchievementCategoryAsset category(@Nullable String id) {
        return id == null || id.isBlank() ? null : resolve(id.trim().toLowerCase(Locale.ROOT));
    }
}
