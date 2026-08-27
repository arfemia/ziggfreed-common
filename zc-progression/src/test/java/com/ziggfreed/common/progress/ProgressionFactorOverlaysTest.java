package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.DerivedFactorAsset;
import com.ziggfreed.common.progress.runtime.ProgressionFactors;

/**
 * The shipped naming overlays for this module's own factor ids: each decodes, targets its
 * progression factor, and carries a name - so a bare condition on either id reads as a real
 * sentence instead of the generic requirements line on any server with the library installed.
 */
class ProgressionFactorOverlaysTest {

    @Test
    void bothProgressionFactorOverlaysDecodeAndNameTheirIds() throws Exception {
        assertOverlay("Progression_Quest_Completed", ProgressionFactors.QUEST_COMPLETED);
        assertOverlay("Progression_Achievement_Earned", ProgressionFactors.ACHIEVEMENT_EARNED);
    }

    private static void assertOverlay(String file, String factorId) throws Exception {
        String path = "/Server/ZiggfreedCommon/Factors/" + file + ".json";
        String json;
        try (var in = ProgressionFactorOverlaysTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing shipped overlay: " + path);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        DerivedFactorAsset asset = DerivedFactorAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(DerivedFactorAsset.class, file, null)));
        assertTrue(asset.isOverlay(), path + " must target its factor through the Factor leaf");
        assertEquals(factorId, asset.namedFactorId(), path + " names this module's own factor id");
        assertTrue(asset.carriesNaming(), path + " must carry a name");
        assertNull(asset.getFormula(), path + " is a naming overlay, never a value definition");
    }
}
