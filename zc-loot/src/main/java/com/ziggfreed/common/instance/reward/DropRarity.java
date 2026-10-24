package com.ziggfreed.common.instance.reward;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The rarity a reward reads as from its drop's chance ({@link InstanceReward#dropChance}), named by the game's own
 * item quality ids, so a reward chest's card takes that quality's slot, word and colour. The bands are data, one file
 * per quality ({@link DropRarityAsset}, folded by {@link DropRarityConfig}): the library ships 1 percent or less as
 * Legendary, 5 or less Epic, 20 or less Rare, 50 or less Uncommon and 100 or less Common, which a pack or the server
 * owner retunes or adds to. The one reader of them: the chest page's cards and {@link InstanceReward#merge}, which
 * keeps lines of one item apart when they read as different rarities, both ask here.
 */
public final class DropRarity {

    private DropRarity() {
    }

    /**
     * The quality id a drop of {@code chancePercent} (per chest, 0 to 100) reads as under the loaded bands, or null
     * for a reward whose chance is not known (one saved before its chance was kept, or built by hand), which keeps its
     * item's own quality, and before any band loads.
     */
    @Nullable
    public static String qualityOf(@Nullable Double chancePercent) {
        return qualityOf(chancePercent, DropRarityConfig.getInstance().bands());
    }

    /**
     * The quality id a drop of {@code chancePercent} reads as under {@code bands} (narrowest first,
     * {@link DropRarityConfig#bands}): the first band its chance is within, else the widest; null for no chance or no
     * band.
     */
    @Nullable
    static String qualityOf(@Nullable Double chancePercent, @Nonnull List<DropRarityConfig.Band> bands) {
        if (chancePercent == null || !Double.isFinite(chancePercent) || bands.isEmpty()) {
            return null;
        }
        for (DropRarityConfig.Band band : bands) {
            if (chancePercent <= band.atMostPercent()) {
                return band.quality();
            }
        }
        return bands.get(bands.size() - 1).quality();
    }
}
