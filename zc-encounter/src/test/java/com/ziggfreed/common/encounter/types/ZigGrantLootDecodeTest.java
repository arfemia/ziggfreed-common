package com.ziggfreed.common.encounter.types;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.loot.LootRef;

/**
 * The decode a {@code ZigGrant}'s Loot goes through when its script loads. A unit JVM cannot load a
 * script through the engine's builder manager, so the route's pure part is pinned here: the decode
 * the builder runs in the asset context the engine hands a script's builders (an
 * {@code AssetExtraInfo} keyed on the file, which is how the builder manager builds it), and the
 * detached context it falls back to.
 */
class ZigGrantLootDecodeTest {

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    /** The context the engine's builder manager hands every builder of one script file. */
    private static ExtraInfo builderContext(String key) {
        return new AssetExtraInfo<>(new AssetExtraInfo.Data(null, key, null));
    }

    @Test
    void aNamedTableNeedsAnAssetContextToDecode() {
        // Why the grant used to pay nothing: the loot group's table leaf is a contained-asset codec.
        // The codec wraps the contained-asset codec's refusal, so look for it down the cause chain.
        Throwable thrown = assertThrows(Exception.class, () -> LootRef.CODEC.decodeJson(
                RawJsonReader.fromJsonString("{\"Lootables\": [\"Boss_Hoard\"]}"), new ExtraInfo()));
        boolean refusedOutsideAnAssetContext = false;
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            refusedOutsideAnAssetContext |= t instanceof UnsupportedOperationException;
        }
        assertTrue(refusedOutsideAnAssetContext, () -> String.valueOf(thrown));
    }

    @Test
    void aNamedTableDecodesInTheScriptsOwnContext() {
        LootRef loot = BuilderActionZigGrant.decodeLoot(json("{\"Lootables\": [\"Boss_Hoard\"]}"),
                builderContext("Boss_Encounter"), "Boss_Encounter");

        assertArrayEquals(new String[] {"Boss_Hoard"}, loot.getLootables());
        assertFalse(loot.isEmpty());
    }

    @Test
    void aContextThatIsNotAnAssetContextFallsBackToADetachedOne() {
        LootRef loot = BuilderActionZigGrant.decodeLoot(json("{\"Lootables\": [\"Boss_Hoard\"]}"), new ExtraInfo(),
                "Boss_Encounter");
        assertArrayEquals(new String[] {"Boss_Hoard"}, loot.getLootables());

        LootRef none = BuilderActionZigGrant.decodeLoot(json("{\"Lootables\": [\"Boss_Hoard\"]}"), null,
                "Boss_Encounter");
        assertArrayEquals(new String[] {"Boss_Hoard"}, none.getLootables());
    }

    @Test
    void inlineRollsDecodeAsBefore() {
        LootRef loot = BuilderActionZigGrant.decodeLoot(
                json("{\"Rolls\": [{\"Grants\": {\"Items\": [{\"Item\": \"Boss_Coin\"}]}}]}"),
                builderContext("Boss_Encounter"), "Boss_Encounter");

        assertNull(loot.getLootables());
        assertEquals("Boss_Coin", loot.getRolls()[0].getGrants().getItems()[0].getItem());
    }

    @Test
    void noLootDecodesToNone() {
        assertNull(BuilderActionZigGrant.decodeLoot(null, builderContext("Boss_Encounter"), "Boss_Encounter"));
        assertNull(BuilderActionZigGrant.decodeLoot(JsonNull.INSTANCE, null, "Boss_Encounter"));
    }

    @Test
    void aLootThatCannotBeReadSaysWhy() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> BuilderActionZigGrant.decodeLoot(json("{\"Rolls\": 5}"), builderContext("Boss_Encounter"),
                        "Boss_Encounter"));
        assertFalse(thrown.getMessage() == null || thrown.getMessage().isBlank(), "the warning carries a reason");
    }
}
