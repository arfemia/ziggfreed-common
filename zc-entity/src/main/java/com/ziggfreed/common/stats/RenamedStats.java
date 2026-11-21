package com.ziggfreed.common.stats;

import java.util.Map;

import com.ziggfreed.common.stats.damage.TypedDamageBonuses;

/**
 * The library's stats renamed to one spelling (R925): {@code GatherSpeed_TallTree} as one word, since a tall tree is a
 * condition and no block family. Each old id is a zc-core {@link StatAliases} entry onto its new one, so a stack's saved
 * roll, a file and a consumer's content naming an old id read and write the new one, with one warning naming it. A
 * merged stat is here too: {@code Damage_WhileHeld_Tool}, an every-type damage bonus while a gathering tool is in hand,
 * is the every-type {@code DamageBonus} a grant binds {@code While} {@code ZigHolding} {@code Tool} names (R933's
 * pattern); a roll carries no condition, so an old roll counts there whatever is in hand. The old ids are written out
 * as they shipped, never built, and each points straight at today's id, since an alias is never followed twice.
 * Registered once at setup by {@code ConditionBootstrap.install}.
 */
public final class RenamedStats {

    /** Each renamed or merged library stat, old id to new. */
    static final Map<String, String> RENAMED = Map.ofEntries(
            Map.entry("GatherSpeed_Tall_Tree", "GatherSpeed_TallTree"),
            Map.entry("Damage_WhileHeld_Tool", TypedDamageBonuses.EVERY_TYPE_STAT_ID));

    private RenamedStats() {
    }

    /** The old ids onto the new. Idempotent. */
    public static void register() {
        RENAMED.forEach((retired, successor) -> StatAliases.register(retired, successor));
    }
}
