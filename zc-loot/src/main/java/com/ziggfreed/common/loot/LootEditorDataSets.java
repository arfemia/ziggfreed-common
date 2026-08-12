package com.ziggfreed.common.loot;

import java.util.Collection;

import javax.annotation.Nonnull;

import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.stamp.RollPoolAsset;
import com.ziggfreed.common.loot.stamp.RollPoolConfig;

/**
 * What the Asset Editor offers as a pick list when someone is writing loot: the loot tables that
 * exist, the roll pools that exist, and the reward kinds this server can actually pay out.
 *
 * <p>Each answer is read LIVE off the running tables, so a pack that ships a new table sees it in
 * the list without anything here changing. Only a dataset something actually serves is worth naming
 * on a field - an unserved one renders as an empty list, which reads to an author as "there are
 * none" rather than "nobody answered".
 */
public final class LootEditorDataSets {

    /** Every loaded loot table id. */
    public static final String LOOTABLES = LootableAsset.EDITOR_DATASET;

    /** Every loaded roll pool id. */
    public static final String ROLL_POOLS = RollPoolAsset.EDITOR_DATASET;

    /** Every reward kind something on this server pays out. */
    public static final String REWARD_KINDS = "ziggfreedcommon:reward_kinds";

    private LootEditorDataSets() {
    }

    @Nonnull
    public static Collection<String> lootableIds() {
        return LootableConfig.getInstance().all().keySet();
    }

    @Nonnull
    public static Collection<String> rollPoolIds() {
        return RollPoolConfig.getInstance().all().keySet();
    }

    @Nonnull
    public static Collection<String> rewardKindIds() {
        return RewardKinds.shared().ids();
    }
}
