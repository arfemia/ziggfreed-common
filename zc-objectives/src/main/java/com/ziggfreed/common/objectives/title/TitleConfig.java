package com.ziggfreed.common.objectives.title;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of {@link TitleAsset}, keyed by title id. The one place
 * a reader asks whether a title is on offer ({@link #shown}) and in what order a player's titles
 * list ({@link #listing}), so the picker, the menus and a command agree.
 */
public final class TitleConfig extends AbstractKeyedAssetConfig<TitleAsset> {

    private static final TitleConfig INSTANCE = new TitleConfig();

    private TitleConfig() {
    }

    @Nonnull
    public static TitleConfig getInstance() {
        return INSTANCE;
    }

    /** The folded title under {@code titleId} when one exists and is on offer, else null. */
    @Nullable
    public TitleAsset shown(@Nullable String titleId) {
        if (titleId == null || titleId.isBlank()) {
            return null;
        }
        TitleAsset title = resolve(titleId.trim());
        return title != null && title.enabled() ? title : null;
    }

    /**
     * Of {@code titleIds}, the ids on offer, lower-cased, in picker order: {@code Order}, then id.
     * Unknown, switched-off and repeated ids are left out.
     */
    @Nonnull
    public List<String> listing(@Nonnull Collection<String> titleIds) {
        Set<String> seen = new LinkedHashSet<>();
        List<TitleAsset> offered = new ArrayList<>();
        for (String id : titleIds) {
            TitleAsset title = id == null ? null : shown(id);
            if (title != null && seen.add(title.getId().toLowerCase(Locale.ROOT))) {
                offered.add(title);
            }
        }
        offered.sort(Comparator.comparingInt(TitleAsset::order).thenComparing(TitleAsset::getId));
        List<String> ids = new ArrayList<>(offered.size());
        for (TitleAsset title : offered) {
            ids.add(title.getId());
        }
        return ids;
    }
}
