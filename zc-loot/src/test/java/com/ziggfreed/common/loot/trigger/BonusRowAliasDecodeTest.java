package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A bonus row's {@code When.Kind} is an alias onto the objective kind ids (zc-core's {@code match.When}), and its
 * table is zc-core's {@code MomentTable}: a row written the old way ({@code BreakBlock}, {@code KillMob},
 * {@code PickupItem}) decodes and matches exactly as before, and the same rows written by kind id
 * ({@code BREAK_BLOCK}, {@code KILL_ENTITY}, {@code PICKUP_ITEM}) answer every name the same way.
 */
class BonusRowAliasDecodeTest {

    private static final String LOOT = "\"Loot\": { \"Rolls\": [ { \"Grants\": { \"Items\": [ "
            + "{ \"Item\": \"Fixture_Gem\", \"Count\": 1 } ] } } ] }";

    /** The rows as the shipped and owner files write them: id, kind, match (null for none), hands nothing over. */
    private static final List<String[]> ROWS = List.of(
            new String[] {"a_rock_all", "BreakBlock", "Rock_*", "pays"},
            new String[] {"b_rock_exact", "BreakBlock", "Rock_Stone", "pays"},
            new String[] {"c_rock_hole", "BreakBlock", "Rock_Stone_Mossy", "hole"},
            new String[] {"d_geode", "BreakBlock", "*Geode*", "pays"},
            new String[] {"e_mob_all", "KillMob", null, "pays"},
            new String[] {"f_mob_trork", "KillMob", "Trork_*", "pays"},
            new String[] {"g_mob_trork_late", "KillMob", "trork_*", "pays"},
            new String[] {"h_pumpkin", "PickupItem", "*Pumpkin*", "pays"});

    /** What each spelling of a kind reads as, old to new. */
    private static final Map<String, String> BY_KIND_ID = Map.of(
            "BreakBlock", "BREAK_BLOCK", "KillMob", "KILL_ENTITY", "PickupItem", "PICKUP_ITEM");

    @AfterEach
    void reset() {
        BonusRowConfig.getInstance().mergePackLayer(Map.of());
        BonusRowConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    private static Map<String, BonusRowAsset> decodeAll(boolean byKindId) throws IOException {
        Map<String, BonusRowAsset> out = new LinkedHashMap<>();
        for (String[] row : ROWS) {
            String kind = byKindId ? BY_KIND_ID.get(row[1]) : row[1];
            String when = "\"When\": { \"Kind\": \"" + kind + "\"" + (row[2] == null ? "" : ", \"Match\": \""
                    + row[2] + "\"") + " }";
            String json = "{ " + when + ("hole".equals(row[3]) ? "" : ", " + LOOT) + " }";
            out.put(row[0], BonusRowAssetCodecTest.decode(row[0], json, null));
        }
        return out;
    }

    private static String idFor(BonusMoment moment, String name) {
        BonusRow row = BonusRowConfig.getInstance().bestFor(moment, name);
        return row == null ? null : row.sourceId() + (row.handsNothingOver() ? " (hole)" : "");
    }

    private static Map<String, String> answers() {
        Map<String, String> out = new LinkedHashMap<>();
        String[][] asks = {
                {"BREAK_BLOCK", "Rock_Stone"}, {"BREAK_BLOCK", "ROCK_STONE"}, {"BREAK_BLOCK", "Rock_Basalt"},
                {"BREAK_BLOCK", "Rock_Stone_Mossy"}, {"BREAK_BLOCK", "Rock_Gem_Geode"}, {"BREAK_BLOCK", "Ore_Iron"},
                {"BREAK_BLOCK", ""}, {"KILL_MOB", "Trork_Grunt"}, {"KILL_MOB", "Skeleton"},
                {"PICKUP_ITEM", "Plant_Pumpkin"}, {"PICKUP_ITEM", "Plant_Wheat"}};
        for (String[] ask : asks) {
            out.put(ask[0] + " " + ask[1], idFor(BonusMoment.valueOf(ask[0]), ask[1]));
        }
        return out;
    }

    @Test
    void theFirstSpellingsMatchAsBeforeAndTheKindIdsMatchTheSame() throws IOException {
        Map<String, BonusRowAsset> old = decodeAll(false);
        for (Map.Entry<String, BonusRowAsset> e : old.entrySet()) {
            String kind = ROWS.stream().filter(r -> r[0].equals(e.getKey())).findFirst().orElseThrow()[1];
            assertEquals(BonusMoment.parse(kind), e.getValue().moment(), e.getKey() + " reads its moment");
            assertEquals(BY_KIND_ID.get(kind), e.getValue().getWhen().kindId(), e.getKey() + " reads as its kind id");
        }
        BonusRowConfig.getInstance().mergePackLayer(old);
        Map<String, String> before = answers();

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("BREAK_BLOCK Rock_Stone", "b_rock_exact");
        expected.put("BREAK_BLOCK ROCK_STONE", "b_rock_exact");
        expected.put("BREAK_BLOCK Rock_Basalt", "a_rock_all");
        expected.put("BREAK_BLOCK Rock_Stone_Mossy", "c_rock_hole (hole)");
        expected.put("BREAK_BLOCK Rock_Gem_Geode", "a_rock_all");
        expected.put("BREAK_BLOCK Ore_Iron", null);
        expected.put("BREAK_BLOCK ", null);
        expected.put("KILL_MOB Trork_Grunt", "g_mob_trork_late");
        expected.put("KILL_MOB Skeleton", "e_mob_all");
        expected.put("PICKUP_ITEM Plant_Pumpkin", "h_pumpkin");
        expected.put("PICKUP_ITEM Plant_Wheat", null);
        assertEquals(expected, before, "an exact name first, then the longest literal core, a hole stopping a "
                + "broader pattern, and two rows with one pattern the later in fold order");

        BonusRowConfig.getInstance().mergePackLayer(decodeAll(true));
        assertEquals(before, answers(), "the same rows written by objective kind id answer every name the same");
    }
}
