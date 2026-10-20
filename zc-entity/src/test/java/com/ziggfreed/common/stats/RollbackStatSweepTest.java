package com.ziggfreed.common.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The rollback sweep's key matcher ({@link RollbackStatSweep#swept}, {@link RollbackStatSweep#sweptKeys}), the one
 * line between clearing the later line's grants and erasing a bonus this line's own consumers re-apply: every key
 * shape Skills 2.0 saves is swept, and every key shape 1.7.3, its CommonLib and the engine write is spared.
 */
class RollbackStatSweepTest {

    /** What Skills 2.0 saves on a player: track grants, points grants, the conditional fold and a counter's stacks. */
    private static final List<String> LATER_LINE = List.of(
            "zigtrack:swords:points",
            "zigtrack:swords:mmo_swords_keen_edge",
            "zigtrack:acrobatics:mmo_acrobatics_mountaineer",
            "zigtrack:@cond:DamageResist_Fire",
            "zigtrack:@counter:mmo_rage");

    /** What 1.7.3 itself writes: the MMO's keys, CommonLib 2.5.0's and the engine's own. */
    private static final List<String> THIS_LINE = List.of(
            "mmoskilltree:tree",
            "mmoskilltree:mastery",
            "mmoskilltree:mastery_loot",
            "mmoskilltree:mastery_damage",
            "mmoskilltree:residual",
            "mmoskilltree:level",
            "mmoskilltree:class",
            "mmoskilltree:swords_t3_edge",
            "mmoskilltree:boost:server",
            "ziggfreedcommon:held:0",
            "ziggfreedcommon:armor:2",
            "ziggfreedcommon:placement_health",
            "zigset:warden:0:0",
            "zc_encounter_scale",
            "zc_encounter_add_scale",
            "Effect_ADDITIVE",
            "Armor_MULTIPLICATIVE",
            "*Weapon_0",
            "*Utility_0");

    @Test
    void sweepsEveryLaterLineKeyAndSparesEveryKeyThisLineWrites() {
        for (String key : LATER_LINE) {
            assertTrue(RollbackStatSweep.swept(key), key);
        }
        for (String key : THIS_LINE) {
            assertFalse(RollbackStatSweep.swept(key), key);
        }
        assertFalse(RollbackStatSweep.swept(null));
        assertFalse(RollbackStatSweep.swept("zigtrack"));
        assertFalse(RollbackStatSweep.swept("zigtracker:swords:points"));
    }

    @Test
    void sweptKeysPicksOnlyTheLaterLineKeysIntoAListOfTheirOwn() {
        List<String> mixed = new ArrayList<>();
        for (int i = 0; i < THIS_LINE.size(); i++) {
            mixed.add(THIS_LINE.get(i));
            if (i < LATER_LINE.size()) {
                mixed.add(LATER_LINE.get(i));
            }
        }
        mixed.add(null);

        List<String> swept = RollbackStatSweep.sweptKeys(mixed);
        assertEquals(LATER_LINE, swept);

        swept.clear();
        assertEquals(THIS_LINE.size() + LATER_LINE.size() + 1, mixed.size());
        assertEquals(List.of(), RollbackStatSweep.sweptKeys(Arrays.asList("mmoskilltree:tree", null)));
    }
}
