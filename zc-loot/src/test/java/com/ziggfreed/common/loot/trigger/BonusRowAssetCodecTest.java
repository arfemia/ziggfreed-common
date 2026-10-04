package com.ziggfreed.common.loot.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/** What an authored bonus row decodes to, and what a Parent does to it. */
class BonusRowAssetCodecTest {

    static BonusRowAsset decode(String id, String json, BonusRowAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(BonusRowAsset.class, id, parent == null ? null : "parent");
        return BonusRowAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }

    @Test
    void everyLeafSurvivesADecode() throws IOException {
        BonusRowAsset row = decode("fixture_geode", """
                { "When": { "Kind": "BreakBlock", "Match": "*Geode*" },
                  "Chance": { "Base": 5.0 },
                  "Loot": { "Rolls": [ { "Grants": { "Items": [ { "Item": "Fixture_Gem", "Count": 1 } ] } } ] },
                  "Enabled": true }
                """, null);

        assertEquals(BonusMoment.BREAK_BLOCK, row.moment());
        assertEquals("*Geode*", row.match());
        assertEquals(5.0, row.getChance().getBase(), 1e-9);
        assertFalse(row.getLoot().isEmpty());
        assertTrue(row.isEnabled());
    }

    @Test
    void aChildNamingAParentRestatesOneLeafAndKeepsTheRest() throws IOException {
        BonusRowAsset parent = decode("parent", """
                { "When": { "Kind": "PickupItem", "Match": "*Pumpkin*" },
                  "Loot": { "Rolls": [ { "Grants": { "Commands": [ "say found" ] } } ] } }
                """, null);

        BonusRowAsset child = decode("child", "{ \"Chance\": { \"Base\": 10.0 } }", parent);

        assertEquals(BonusMoment.PICKUP_ITEM, child.moment());
        assertEquals("*Pumpkin*", child.match());
        assertEquals(10.0, child.getChance().getBase(), 1e-9);
        assertFalse(child.getLoot().isEmpty());
    }

    @Test
    void enabledDefaultsToOnAndAnUnknownMomentReadsAsNone() throws IOException {
        BonusRowAsset row = decode("fixture_odd", "{ \"When\": { \"Kind\": \"Sneeze\" } }", null);

        assertTrue(row.isEnabled());
        assertNull(row.moment());
        assertEquals("Sneeze", row.getWhen().rawKind());
        assertTrue(row.match().isEmpty());
    }

    @Test
    void theMomentIsNamedInEitherSpelling() throws IOException {
        assertEquals(BonusMoment.KILL_MOB, decode("a", "{ \"When\": { \"Kind\": \"Kill_Mob\" } }", null).moment());
        assertEquals(BonusMoment.KILL_MOB, decode("b", "{ \"When\": { \"Kind\": \"killmob\" } }", null).moment());
    }
}
