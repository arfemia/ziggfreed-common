package com.ziggfreed.common.progress.asset;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The folded layer of one category type, resolved {@code defaults < pack < owner} by id like every
 * other framework config, with the reads every listing makes of it: the categories in reading order,
 * and one category by id in any casing.
 *
 * <p>Presentation only. A category exists because content filed itself under that word; a file here
 * says where the word sits, what illustrates it, what it is called and its accent. A category no file
 * mentions still works, and gets whatever a surface draws for a group nobody described.
 *
 * <p>Read it LAZILY: the layer is filled by the asset store's load event, which runs after every
 * plugin's {@code setup()}.
 *
 * @param <T> the category type this config folds
 */
public abstract class CategoryPresentationConfig<T extends CategoryPresentationAsset>
        extends AbstractKeyedAssetConfig<T> {

    protected CategoryPresentationConfig() {
    }

    /**
     * Every folded category in READING order: by its own {@code Order} first, then by id so two
     * categories sharing a sort key (or naming none at all) still read the same way on every boot.
     */
    @Nonnull
    public List<T> ordered() {
        List<T> out = new ArrayList<>(all().values());
        out.sort(Comparator.comparingInt((T a) -> a.orderOrLast())
                .thenComparing(a -> a.getId() == null ? "" : a.getId()));
        return out;
    }

    /** The ids of every category that named an {@code Order}, in that order. */
    @Nonnull
    public List<String> orderedIds() {
        List<String> out = new ArrayList<>();
        for (T asset : ordered()) {
            String id = asset.getId();
            if (id != null && !id.isEmpty() && asset.getOrder() != null) {
                out.add(id);
            }
        }
        return out;
    }

    /** The presentation for one category, or null when no file describes it. */
    @Nullable
    public T category(@Nullable String id) {
        return id == null || id.isBlank() ? null : resolve(id.trim().toLowerCase(Locale.ROOT));
    }
}
