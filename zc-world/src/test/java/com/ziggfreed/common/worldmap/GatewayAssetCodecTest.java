package com.ziggfreed.common.worldmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.world.WorldSelector;

/** A gateway file decodes every leaf, and an empty one reads as on, everywhere and leading nowhere. */
class GatewayAssetCodecTest {

    private static GatewayAsset decode(String json, String id) throws IOException {
        return GatewayAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(GatewayAsset.class, id, null)));
    }

    @Test
    void everyLeafDecodes() throws IOException {
        GatewayAsset gateway = decode("{ \"Enabled\": false, \"Where\": { \"Match\": [\"default\"] },"
                + " \"Into\": { \"World\": \"default\", \"GameplayConfig\": \"ForgottenTemple\" },"
                + " \"Blocks\": [\"Forgotten_Temple_Portal_Enter\"],"
                + " \"Positions\": [ { \"X\": 1, \"Y\": 2, \"Z\": 3 } ] }", "Temple");

        assertFalse(gateway.isEnabled());
        assertNotNull(gateway.getWhere());
        assertArrayEquals(new String[]{"default"}, gateway.getWhere().getMatch());
        assertEquals("default", gateway.getInto().getWorld());
        assertEquals("ForgottenTemple", gateway.getInto().getGameplayConfig());
        assertEquals(List.of("Forgotten_Temple_Portal_Enter"), gateway.blockIds());
        assertEquals(1, gateway.fixedPositions().size());
        assertEquals(2.0, gateway.fixedPositions().get(0).effectiveY());
    }

    @Test
    void anEmptyGatewayIsOnStandsEverywhereAndLeadsNowhere() throws IOException {
        GatewayAsset gateway = decode("{}", "Bare");

        assertTrue(gateway.isEnabled());
        assertTrue(gateway.standsIn("anything", null));
        assertFalse(gateway.leadsInto(List.of(WorldSelector.of(new String[]{"*"}, null, null))));
        assertTrue(gateway.blockIds().isEmpty());
        assertTrue(gateway.fixedPositions().isEmpty());
    }
}
