package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.progress.asset.ObjectiveKindAsset;

/**
 * {@code LOOT_RECEIVED} means something in any game (an item a reward handed over), so every
 * registry starts with it, and its shipped file says what its target names and how it looks.
 */
class LootReceivedKindTest {

    @Test
    void lootReceivedIsABuiltInItemTargetedKind() {
        ObjectiveKindRegistry kinds = new ObjectiveKindRegistry();

        assertTrue(ObjectiveKindRegistry.isBuiltIn("LOOT_RECEIVED"));
        assertTrue(kinds.isRegistered("loot_received"), "matched without regard to case");
        assertTrue(kinds.isProducible("LOOT_RECEIVED"), "the library's own producer fires it");
        ObjectiveKind kind = kinds.kind("LOOT_RECEIVED");
        assertTrue(kind.targetsItem(), "its target is the item a reward handed over");
        assertFalse(kind.valueBased(), "each payout adds its count");
    }

    @Test
    void theShippedKindFileDecodesAsAnItemKindWithAPicture() throws IOException {
        String json;
        try (var in = LootReceivedKindTest.class.getResourceAsStream(
                "/Server/ZiggfreedCommon/ObjectiveKinds/Loot_Received.json")) {
            assertNotNull(in, "the kind ships its file");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ObjectiveKindAsset asset = ObjectiveKindAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ObjectiveKindAsset.class, "Loot_Received", null)));

        assertEquals(Boolean.TRUE, asset.getTargetNames().getItem());
        assertEquals(Boolean.FALSE, asset.getValueBased());
        assertEquals(Boolean.TRUE, asset.getProducible());
        assertNotNull(asset.getPresentation().getIcon(), "a listed step is never blank");
    }
}
