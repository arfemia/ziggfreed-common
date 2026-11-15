package com.ziggfreed.common.objectives.authoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.achievement.asset.AchievementAssetStore;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.authoring.Pickers;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.loot.reward.RewardParam;
import com.ziggfreed.common.loot.reward.RewardParams;
import com.ziggfreed.common.objectives.title.TitleAsset;
import com.ziggfreed.common.objectives.title.TitleRewardKind;
import com.ziggfreed.common.progress.asset.ProgressEditorDataSets;

/**
 * What the achievement editor's lists offer, each read from the live stores at the moment the page paints: the
 * achievements it can open (the server content pack's own, and the ones a pack ships), the categories, the titles (the
 * {@code Title} reward kind's own pick) and the reward kinds a Claim row may name. The objective kinds and a
 * criterion's targets are the quest editor's ({@link QuestEditorPicks}).
 */
final class AchievementEditorPicks {

    private AchievementEditorPicks() {
    }

    // ---------------------------------------------------------------- the stores

    /** The achievement store the engine registered, which the server content pack writes into. */
    @Nonnull
    static AssetStore<String, AchievementAsset, DefaultAssetMap<String, AchievementAsset>> achievements() {
        return AssetRegistry.getAssetStore(AchievementAsset.class);
    }

    @Nonnull
    static AssetStore<String, AchievementCategoryAsset, DefaultAssetMap<String, AchievementCategoryAsset>> categories() {
        return AssetRegistry.getAssetStore(AchievementCategoryAsset.class);
    }

    @Nonnull
    static AssetStore<String, TitleAsset, DefaultAssetMap<String, TitleAsset>> titles() {
        return AssetRegistry.getAssetStore(TitleAsset.class);
    }

    // ---------------------------------------------------------------- what can be opened

    /** The achievements the server content pack holds, as the store spells them. */
    @Nonnull
    static List<String> serverAchievements() {
        try {
            return ServerContent.ids(achievements());
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * The achievements a pack ships that the server content pack does not replace, as the store spells them, sorted
     * without regard to case.
     */
    @Nonnull
    static List<String> shippedAchievements() {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            DefaultAssetMap<String, AchievementAsset> map = achievements().getAssetMap();
            String pack = ServerContent.packId();
            for (String key : map.getAssetMap().keySet()) {
                if (key != null && !pack.equals(map.getAssetPack(key))) {
                    out.add(key);
                }
            }
        } catch (Throwable t) {
            return List.of();
        }
        return List.copyOf(out);
    }

    /** True when the achievement the store serves as {@code id} is a pack's, not the server content pack's. */
    static boolean shipped(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            String pack = achievements().getAssetMap().getAssetPack(id);
            return pack != null && !pack.equals(ServerContent.packId());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when the store serves the server content pack's copy of {@code id} over one a pack ships, so taking the
     * copy away brings the shipped one back. (The store answers a pack's asset with the one beneath it in load order,
     * and with the served one for a pack that holds none, so the served pack is asked first.)
     */
    static boolean serverCopy(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            DefaultAssetMap<String, AchievementAsset> map = achievements().getAssetMap();
            String pack = ServerContent.packId();
            return pack.equals(map.getAssetPack(id)) && map.getAsset(pack, id) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- lists

    /** Every achievement category: each one a category file names, and each one an achievement is filed under. */
    @Nonnull
    static Pickers.Source categoryList() {
        return AchievementEditorPicks::categoryIds;
    }

    /** Every category id, lower-cased, sorted. */
    @Nonnull
    static Collection<String> categoryIds() {
        Set<String> out = new TreeSet<>();
        try {
            for (AchievementCategoryAsset category : categories().getAssetMap().getAssetMap().values()) {
                add(out, category == null ? null : category.getId());
            }
        } catch (Throwable ignored) {
            // A store that cannot be read offers the categories in use below.
        }
        try {
            for (AchievementAsset achievement : AchievementAssetStore.getInstance().assets().values()) {
                add(out, achievement.getListing() == null ? null : achievement.getListing().getCategory());
            }
        } catch (Throwable ignored) {
            // Nothing loaded yet offers nothing more.
        }
        return out;
    }

    private static void add(@Nonnull Set<String> out, @Nullable String category) {
        if (category != null && !category.isBlank()) {
            out.add(category.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** Every title, from the {@code Title} reward kind's own pick, else the title store. */
    @Nonnull
    static Pickers.Source titleList() {
        Pickers.Source picked = null;
        try {
            RewardParams described = RewardSlotPages.described(TitleRewardKind.KIND);
            if (described != null) {
                for (RewardParam param : described.params()) {
                    if (param.name().equalsIgnoreCase(AchievementDraft.TITLE_PARAM)) {
                        picked = QuestEditorPicks.of(param.pick());
                    }
                }
            }
        } catch (Throwable ignored) {
            // An undescribed kind falls back to the title store.
        }
        return picked != null ? picked : () -> {
            try {
                return new ArrayList<>(titles().getAssetMap().getAssetMap().keySet());
            } catch (Throwable t) {
                return List.of();
            }
        };
    }

    /**
     * Every reward kind a Claim row may name: the live reward kinds less the title kind, since a title lands the
     * moment the achievement is earned, never on Collect.
     */
    @Nonnull
    static Pickers.Source rewardKinds() {
        return () -> {
            List<String> out = new ArrayList<>();
            for (String kind : ProgressEditorDataSets.rewardKindIds()) {
                if (!TitleRewardKind.KIND.equalsIgnoreCase(kind)) {
                    out.add(kind);
                }
            }
            return out;
        };
    }
}
