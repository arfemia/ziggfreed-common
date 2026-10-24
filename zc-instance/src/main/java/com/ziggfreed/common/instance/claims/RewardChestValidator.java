package com.ziggfreed.common.instance.claims;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * The content audit over the folded reward chests ({@link RewardChestAsset}), filed under the {@code reward_chest}
 * domain, run once every pack has loaded (the boot audit's pass). Split the way every validator in this family is:
 * {@link #audit()} is the engine walk, {@link #audit(Collection, Predicate)} the pure core.
 *
 * <p>The codes, each a WARNING: {@link #GATED_CHEST_LOOTABLE}, a chest whose {@code Contents.Lootable} asks something
 * of the player beyond mod presence (zc-loot's {@code LootableAsset.gatesOnPlayer}). The chest rolls it for each
 * credited player as it goes up ({@link ClaimRolls}), as themselves while they are online, so one who is offline then
 * is paid nothing it covers.
 */
public final class RewardChestValidator {

    public static final String DOMAIN = "reward_chest";

    /** The label the audit's lines carry when it is logged whole (zc's boot audit). */
    public static final String LOG_LABEL = "[spoils] audit";

    public static final String GATED_CHEST_LOOTABLE = "GATED_CHEST_LOOTABLE";

    private RewardChestValidator() {
    }

    /** The engine walk over every folded reward chest. */
    @Nonnull
    public static List<Finding> audit() {
        try {
            return audit(RewardChestConfig.getInstance().all().values(), RewardChestValidator::lootableGated);
        } catch (Throwable t) {
            SafeLog.warn(SpoilsChests.LOG + "the reward chest audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The pure core.
     *
     * @param lootableGated whether a loaded loot table of this id asks something of the player beyond mod presence
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<RewardChestAsset> chests,
            @Nonnull Predicate<String> lootableGated) {
        List<Finding> out = new ArrayList<>();
        for (RewardChestAsset chest : chests) {
            String table = chest == null || chest.getId() == null ? null : chest.lootable();
            if (table != null && gated(lootableGated, table)) {
                out.add(Finding.warning(DOMAIN, GATED_CHEST_LOOTABLE, "reward chest '" + chest.getId() + "' holds the "
                        + "loot table '" + table + "' (Contents.Lootable), whose Requires asks more of a player than "
                        + "mod presence. It is asked of each credited player as the chest goes up, so one who is "
                        + "offline then is paid nothing it covers. Keep the table's Requires to mod presence, or "
                        + "gate the rows on what the moment itself reads", chest.getId()));
            }
        }
        return out;
    }

    /** {@code test}'s answer; one that throws reads as no, since cannot tell is not a finding. */
    private static boolean gated(@Nonnull Predicate<String> test, @Nonnull String table) {
        try {
            return test.test(table);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Does the loaded table of this id ask something of the player beyond mod presence? No when none is loaded. */
    private static boolean lootableGated(@Nonnull String id) {
        LootableAsset table = LootableConfig.getInstance().resolve(id);
        return table != null && table.gatesOnPlayer();
    }
}
