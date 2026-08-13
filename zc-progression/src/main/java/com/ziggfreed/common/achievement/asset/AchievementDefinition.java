package com.ziggfreed.common.achievement.asset;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.progress.asset.ContentListingAsset.ChainMembership;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * A folded achievement: the {@link Achievement} the engine runs, plus everything an authoring layer
 * carries that the engine deliberately has no opinion about - what the player reads, where it is
 * listed, what illustrates it, and what has to be true before it can progress.
 *
 * <p>Splitting it this way is what keeps the engine free of presentation and free of any gate
 * vocabulary: it is handed {@link #achievement()} and nothing else, while a consumer's UI reads the
 * keys here and its gates read {@link #requires()}.
 *
 * @param id               the achievement id, lower-cased (the asset filename, folders folded in)
 * @param achievement      the engine's runnable definition
 * @param titleKey         localization key for the name, or null
 * @param flavorKey        localization key for the description, or null
 * @param displayName      a plain fallback name for one whose key is not written yet, or null
 * @param titleArgs        what fills the title key's numbered slots, in order
 * @param flavorArgs       what fills the flavor key's numbered slots, in order
 * @param category         free grouping label, or null
 * @param subcategory      a second level of grouping inside the category, or null
 * @param sortOrder        lower sorts first within a category
 * @param chains           the ladders this is a rung of, in authored order; the first is primary
 * @param icon             an item id to illustrate it with, or null
 * @param requires         what must be true first; never null, {@link GateSpec#OPEN} when open
 * @param criterionTextKeys criterion POSITION to its localization key, for the ones that carry one
 * @param meta             per-namespace extra facts, verbatim; see {@link ContentMeta}
 */
public record AchievementDefinition(@Nonnull String id, @Nonnull Achievement achievement,
                                    @Nullable String titleKey, @Nullable String flavorKey,
                                    @Nullable String displayName,
                                    @Nonnull List<String> titleArgs, @Nonnull List<String> flavorArgs,
                                    @Nullable String category,
                                    @Nullable String subcategory, int sortOrder,
                                    @Nonnull List<ChainMembership> chains, @Nullable String icon,
                                    @Nonnull GateSpec requires,
                                    @Nonnull Map<Integer, String> criterionTextKeys,
                                    @Nonnull Map<String, JsonElement> meta) {

    public AchievementDefinition {
        titleArgs = List.copyOf(titleArgs);
        flavorArgs = List.copyOf(flavorArgs);
        chains = List.copyOf(chains);
        criterionTextKeys = Map.copyOf(criterionTextKeys);
        meta = Map.copyOf(meta);
    }

    /**
     * The block one namespace authored under {@code Meta}, exactly as written, or null when this
     * achievement carries none for it.
     */
    @Nullable
    public JsonElement meta(@Nonnull String namespace) {
        return ContentMeta.block(meta, namespace);
    }

    /** The localization key for one criterion's line, by its position, or null when it authored none. */
    @Nullable
    public String criterionTextKey(int criterionIndex) {
        return criterionTextKeys.get(criterionIndex);
    }
}
