package com.ziggfreed.common.stats.condition;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One source's crowd-control rule: the kinds it says land from anyone, a talent's included, and the kinds it says
 * land from nothing, as masks of {@link ControlKind} bits. Authored as native {@code Tags} on an NPC group or an
 * effect ({@value BossRules#LANDS_TAG} and {@value BossRules#SHRUGS_TAG}); a kind it does not name falls to the
 * layer below ({@link ControlReading#resolve}). Within one rule, a kind named in both shrugs.
 *
 * <pre>{@code
 * "Tags": { "ZigControlLands": [ "Stun", "Interrupt" ], "ZigControlShrugs": [ "Taunt" ] }
 * }</pre>
 */
public record ControlRule(int lands, int shrugs) {

    /** A rule naming nothing. */
    public static final ControlRule NONE = new ControlRule(0, 0);

    /** Does it name no kind at all? */
    public boolean isEmpty() {
        return lands == 0 && shrugs == 0;
    }

    /** Every kind it names, as a mask. */
    public int named() {
        return lands | shrugs;
    }

    /** What it says of {@code kind}: SHRUGS, LANDS, or null when it does not name it. Shrugs wins over lands. */
    @Nullable
    public ControlReading answer(@Nonnull ControlKind kind) {
        if (kind.in(shrugs)) {
            return ControlReading.SHRUGS;
        }
        return kind.in(lands) ? ControlReading.LANDS : null;
    }

    /** This rule and {@code other} as one layer: every kind either names, shrugs still winning. */
    @Nonnull
    public ControlRule with(@Nullable ControlRule other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        return isEmpty() ? other : new ControlRule(lands | other.lands, shrugs | other.shrugs);
    }

    /** The kinds that land from anyone. */
    @Nonnull
    public Set<ControlKind> landing() {
        return ControlKind.ofMask(lands);
    }

    /** The kinds that land from nothing. */
    @Nonnull
    public Set<ControlKind> shrugging() {
        return ControlKind.ofMask(shrugs);
    }

    /**
     * The rule an asset's raw native tags write ({@code AssetExtraInfo.Data.getRawTags}, its {@code Parent}'s merged
     * in): the {@value BossRules#LANDS_TAG} and {@value BossRules#SHRUGS_TAG} values, keys and values matched without
     * regard to case. Each value that names no kind goes to {@code unknown} and counts nothing. {@link #NONE} for none.
     * Pure.
     */
    @Nonnull
    public static ControlRule parse(@Nullable Map<String, String[]> rawTags, @Nonnull Consumer<String> unknown) {
        if (rawTags == null || rawTags.isEmpty()) {
            return NONE;
        }
        int lands = 0;
        int shrugs = 0;
        for (Map.Entry<String, String[]> entry : rawTags.entrySet()) {
            String key = entry.getKey();
            if (key == null || entry.getValue() == null) {
                continue;
            }
            boolean landsKey = key.equalsIgnoreCase(BossRules.LANDS_TAG);
            if (!landsKey && !key.equalsIgnoreCase(BossRules.SHRUGS_TAG)) {
                continue;
            }
            int mask = maskOf(entry.getValue(), unknown);
            if (landsKey) {
                lands |= mask;
            } else {
                shrugs |= mask;
            }
        }
        return lands == 0 && shrugs == 0 ? NONE : new ControlRule(lands, shrugs);
    }

    /**
     * Does an NPC group's raw native tags count its roles as bosses or elites ({@value BossRules#RANK_TAG} naming
     * {@code Boss} or {@code Elite}, any case)? Any other value goes to {@code unknown} and counts nothing. Pure.
     */
    public static boolean ranked(@Nullable Map<String, String[]> rawTags, @Nonnull Consumer<String> unknown) {
        if (rawTags == null || rawTags.isEmpty()) {
            return false;
        }
        boolean ranked = false;
        for (Map.Entry<String, String[]> entry : rawTags.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !entry.getKey().equalsIgnoreCase(BossRules.RANK_TAG)) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String rank = value.trim().toLowerCase(Locale.ROOT);
                if (rank.equals("boss") || rank.equals("elite")) {
                    ranked = true;
                } else {
                    unknown.accept(value.trim());
                }
            }
        }
        return ranked;
    }

    private static int maskOf(@Nonnull String[] values, @Nonnull Consumer<String> unknown) {
        int mask = 0;
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            ControlKind kind = ControlKind.parse(value);
            if (kind == null) {
                unknown.accept(value.trim());
            } else {
                mask |= kind.bit();
            }
        }
        return mask;
    }
}
