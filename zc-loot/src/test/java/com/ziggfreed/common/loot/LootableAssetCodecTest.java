package com.ziggfreed.common.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.loot.stamp.RollPoolAsset;

/**
 * What authored loot JSON actually decodes to, and what a {@code Parent} does to it.
 *
 * <p>The inheritance cases are the ones worth pinning, because the answer is counter-intuitive and
 * the documentation promises it: {@code Rolls} REPLACES wholesale rather than appending, so a child
 * that writes any roll at all discards every one it inherited.
 */
class LootableAssetCodecTest {

    static LootableAsset decode(String json, String id, String parentId, LootableAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(LootableAsset.class, id, parentId);
        return LootableAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    static LootableAsset decodeRoot(String json, String id) throws IOException {
        return decode(json, id, null, null);
    }

    static RollPoolAsset decodePool(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(RollPoolAsset.class, id, null);
        return RollPoolAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    // ==================== the whole shape ====================

    @Test
    void everyLeafSurvivesADecode() throws Exception {
        LootableAsset table = decodeRoot("""
                {
                  "Rolls": [
                    {
                      "Trigger": "Cycle",
                      "Conditions": [ { "Factor": "mymod:quality", "Min": 2 } ],
                      "Chance": { "Base": 5, "Factors": [ { "Factor": "mymod:luck", "Weight": 0.5 } ],
                                  "Clamp": { "Max": 90 } },
                      "Ladder": { "Factors": [ { "Factor": "mymod:luck" } ],
                                  "Floors": [ { "Min": 50, "Grants": { "DropLists": ["Finds_T1"] } },
                                              { "Min": 100, "Grants": { "Items": [ { "Item": "Gem", "Count": 2 } ] },
                                                "Cue": "jackpot" } ] },
                      "Grants": {
                        "Items": [ { "Item": "Coin_Gold", "Count": 3 } ],
                        "DropLists": [ "Common" ],
                        "Commands": [ "give {player} Bread 1" ],
                        "Rewards": [ { "Kind": "currency", "Params": { "id": "token", "amount": "25" } } ]
                      },
                      "Cue": "rare_find"
                    }
                  ]
                }
                """, "forestfinds");

        assertEquals("forestfinds", table.getId());
        Roll[] rolls = table.getRolls();
        assertNotNull(rolls);
        assertEquals(1, rolls.length);

        Roll roll = rolls[0];
        assertEquals("Cycle", roll.effectiveTrigger());
        assertEquals(1, roll.getConditions().length);
        assertEquals("mymod:quality", roll.getConditions()[0].getFactor());
        assertEquals(5.0, roll.getChance().baseOrZero(), 1e-9);
        assertEquals(90.0, roll.getChance().getClamp().getMax(), 1e-9);
        assertEquals(2, roll.getLadder().getFloors().length);
        assertEquals("jackpot", roll.getLadder().getFloors()[1].getCue());
        assertEquals("rare_find", roll.getCue());

        LootGrants grants = roll.getGrants();
        assertEquals("Coin_Gold", grants.itemsOrEmpty().get(0).getItem());
        assertEquals(3, grants.itemsOrEmpty().get(0).effectiveCount());
        assertEquals("Common", grants.getDropLists()[0]);
        assertEquals(1, grants.getCommands().length);
        assertEquals("currency", grants.rewardSpecs().get(0).kind());
        assertEquals("25", grants.rewardSpecs().get(0).param("amount"));
    }

    @Test
    void theIdComesFromTheFilenameAndIsLowerCased() throws Exception {
        LootableAsset table = decodeRoot("{ \"Name\": \"Ignored Label\", \"Rolls\": [] }", "ForestFinds");
        assertEquals("forestfinds", table.getId());
    }

    @Test
    void anEmptyBodyDecodesToATableThatSimplyGrantsNothing() throws Exception {
        LootableAsset table = decodeRoot("{ }", "empty");
        assertNull(table.getRolls());
    }

    // ==================== inheritance ====================

    @Nested
    class Inheritance {

        @Test
        void aChildThatWritesNoRollsInheritsThemAll() throws Exception {
            LootableAsset parent = decodeRoot("""
                    { "Rolls": [ { "Grants": { "Items": [ { "Item": "Base_Item" } ] } } ] }
                    """, "base");

            LootableAsset child = decode("{ }", "variant", "base", parent);

            assertNotNull(child.getRolls());
            assertEquals("Base_Item", child.getRolls()[0].getGrants().itemsOrEmpty().get(0).getItem());
        }

        @Test
        void aChildThatWritesAnyRollDiscardsEveryInheritedOne() throws Exception {
            LootableAsset parent = decodeRoot("""
                    { "Rolls": [ { "Grants": { "Items": [ { "Item": "Base_Item" } ] } },
                                 { "Grants": { "Items": [ { "Item": "Second_Base" } ] } } ] }
                    """, "base");

            LootableAsset child = decode("""
                    { "Rolls": [ { "Grants": { "Items": [ { "Item": "Only_Mine" } ] } } ] }
                    """, "variant", "base", parent);

            assertEquals(1, child.getRolls().length,
                    "Rolls replaces wholesale - this is why extra rolls go inline at the consuming site");
            assertEquals("Only_Mine", child.getRolls()[0].getGrants().itemsOrEmpty().get(0).getItem());
        }
    }

    // ==================== the sibling type ====================

    @Test
    void aRollPoolDecodesItsEntries() throws Exception {
        RollPoolAsset pool = decodePool("""
                {
                  "Entries": [
                    { "Stat": "Damage", "Points": { "Min": 2, "Max": 6 }, "Weight": 3 },
                    { "Stat": "Durability", "Points": { "Min": 1 }, "Always": true }
                  ]
                }
                """, "WeaponStats");

        assertEquals("weaponstats", pool.getId());
        assertEquals(2, pool.getEntries().length);
        assertEquals("Damage", pool.getEntries()[0].getStat());
        assertEquals(6.0, pool.getEntries()[0].getPoints().effectiveMax(), 1e-9);
        assertEquals(3.0, pool.getEntries()[0].effectiveWeight(), 1e-9);
        assertTrue(pool.getEntries()[1].isAlways());
        assertEquals(1.0, pool.getEntries()[1].getPoints().effectiveMax(), 1e-9,
                "an omitted Max reads as Min, which is a fixed value");
    }
}
