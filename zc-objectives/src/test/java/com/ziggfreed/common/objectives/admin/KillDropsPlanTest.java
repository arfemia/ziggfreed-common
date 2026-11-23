package com.ziggfreed.common.objectives.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.trigger.BonusMoment;
import com.ziggfreed.common.loot.trigger.BonusRowAsset;
import com.ziggfreed.common.loot.trigger.BonusRowConfig;

/**
 * The Kill Drops screen's read of the rows: it never offers a leaf inside a row's loot (so no screen edits a command,
 * a settings permission never being a way to run one), it counts every command a row runs so the screen can say so,
 * and it lists every kill row, a switched-off one included, and nothing else.
 */
class KillDropsPlanTest {

    @AfterEach
    void reset() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of());
    }

    @Test
    void noLeafTheScreenWritesReachesARowsLootOrItsCommands() {
        for (KillDropsPlan.Setting setting : KillDropsPlan.Setting.values()) {
            String path = setting.path.toLowerCase(Locale.ROOT);
            assertFalse(path.startsWith("loot"), setting + " writes inside the row's loot: " + setting.path);
            assertFalse(path.contains("command"), setting + " writes a command: " + setting.path);
        }
    }

    @Test
    void aRowsCommandsAreCountedWhereverItsGrantsNameThem() {
        LootGrants grants = LootGrants.of(null, new String[] {"Fixture_Drops"}, new String[] {"say a", "say b"},
                new LootGrants.Reward[] {LootGrants.Reward.of("Command", Map.of("Command", "say c"))});
        LootRef loot = LootRef.of(new String[] {"Fixture_Table"},
                new Roll[] {Roll.of(null, null, null, null, grants, null)});

        KillDropsPlan.Pays pays = KillDropsPlan.paysOf(BonusRowAsset.of(BonusMoment.KILL_MOB, "*Skeleton*", null,
                loot, null));

        assertEquals(1, pays.rolls());
        assertEquals(3, pays.commands(), "two command lines and one Command reward");
        assertEquals(List.of("Fixture_Drops"), pays.dropLists());
        assertEquals(List.of("Fixture_Table"), pays.tables());
        assertTrue(KillDropsPlan.paysOf(BonusRowAsset.of(BonusMoment.KILL_MOB, "*", null, null, null)).nothing(),
                "a row with no loot is a hole that drops nothing");
    }

    @Test
    void everyKillRowIsListedASwitchedOffOneIncludedAndNoOtherMoment() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of(
                "fixture_bones", BonusRowAsset.of(BonusMoment.KILL_MOB, "*Skeleton*", null, null, null),
                "fixture_off", BonusRowAsset.of(BonusMoment.KILL_MOB, "*Trork*", null, null, false),
                "fixture_rock", BonusRowAsset.of(BonusMoment.BREAK_BLOCK, "Rock_*", null, null, null)));

        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, BonusRowAsset> row : KillDropsPlan.killRows()) {
            ids.add(row.getKey());
        }

        assertEquals(List.of("fixture_bones", "fixture_off"), ids);
    }
}
