package com.ziggfreed.common.instance.reward;

import javax.annotation.Nullable;

/**
 * The rarity a reward reads as from its drop's chance ({@link InstanceReward#dropChance}), named by the game's own
 * item quality ids, so a reward chest's card takes that quality's slot, word and colour: 1 percent or less is
 * Legendary, 5 or less Epic, 20 or less Rare, 50 or less Uncommon, anything above 50 (a guaranteed drop included)
 * Common. The one place the bands live: the chest page's cards and {@link InstanceReward#merge}, which keeps lines of
 * one item apart when they read as different rarities, both read it.
 */
public final class DropRarity {

    public static final String LEGENDARY = "Legendary";
    public static final String EPIC = "Epic";
    public static final String RARE = "Rare";
    public static final String UNCOMMON = "Uncommon";
    public static final String COMMON = "Common";

    private DropRarity() {
    }

    /**
     * The quality id a drop of {@code chancePercent} (per chest, 0 to 100) reads as, or null for a reward whose chance
     * is not known (one saved before its chance was kept, or built by hand), which keeps its item's own quality.
     */
    @Nullable
    public static String qualityOf(@Nullable Double chancePercent) {
        if (chancePercent == null || !Double.isFinite(chancePercent)) {
            return null;
        }
        double chance = chancePercent;
        if (chance <= 1.0) {
            return LEGENDARY;
        }
        if (chance <= 5.0) {
            return EPIC;
        }
        if (chance <= 20.0) {
            return RARE;
        }
        if (chance <= 50.0) {
            return UNCOMMON;
        }
        return COMMON;
    }
}
