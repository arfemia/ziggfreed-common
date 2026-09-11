package com.ziggfreed.common.entity.overhead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * {@link OverheadIndicatorAsset}'s decode contract over fixtures this test authors: every leaf
 * lands, every unauthored leaf reads as the documented default, and a file naming no picture is
 * look-less rather than half-drawn.
 */
class OverheadIndicatorAssetCodecTest {

    private static OverheadIndicatorAsset decode(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(OverheadIndicatorAsset.class, id, null);
        return OverheadIndicatorAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    @Test
    void everyAuthoredLeafLands() throws Exception {
        OverheadIndicatorAsset look = decode("""
                { "Icon": { "ItemId": "Deco_Scroll" }, "Scale": 0.75,
                  "Offset": { "X": 0.1, "Y": 0.9, "Z": -0.2 }, "Spin": false }
                """, "Quest_Available");

        assertEquals("Quest_Available", look.getId());
        assertTrue(look.hasLook());
        assertNotNull(look.getIcon());
        assertEquals("Deco_Scroll", look.getIcon().itemId());
        assertEquals(0.75f, look.effectiveScale());
        assertEquals(0.1, look.offsetX());
        assertEquals(0.9, look.offsetY());
        assertEquals(-0.2, look.offsetZ());
        assertFalse(look.spins());
    }

    @Test
    void anUnauthoredLeafReadsAsItsDocumentedDefault() throws Exception {
        OverheadIndicatorAsset look = decode("""
                { "Icon": { "TexturePath": "Items/ZiggfreedCommon/Zc_Overhead_Card.png" } }
                """, "Station_Busy");

        assertTrue(look.hasLook());
        assertNull(look.getIcon().itemId());
        assertEquals("Items/ZiggfreedCommon/Zc_Overhead_Card.png", look.getIcon().texturePath());
        assertEquals(OverheadIndicatorAsset.DEFAULT_SCALE, look.effectiveScale());
        assertEquals(0d, look.offsetX());
        assertEquals(OverheadIndicatorAsset.DEFAULT_LIFT, look.offsetY());
        assertEquals(0d, look.offsetZ());
        assertTrue(look.spins(), "an item look spins unless told not to");
    }

    @Test
    void aPartialOffsetKeepsTheLiftDefaultForTheAxisItLeftOut() throws Exception {
        OverheadIndicatorAsset look = decode("""
                { "Icon": { "ItemId": "Deco_Map" }, "Offset": { "X": 0.3 } }
                """, "Quest_In_Progress");

        assertEquals(0.3, look.offsetX());
        assertEquals(OverheadIndicatorAsset.DEFAULT_LIFT, look.offsetY(), "Y unauthored still lifts");
    }

    @Test
    void aFileNamingNoPictureIsLookLess() throws Exception {
        assertFalse(decode("{ \"Scale\": 1.0 }", "Nothing").hasLook());
        assertFalse(decode("{ \"Icon\": { } }", "Blank").hasLook());
        assertFalse(decode("{ \"Icon\": { \"ItemId\": \"  \" } }", "Spaces").hasLook());
    }

    @Test
    void aNonPositiveScaleFallsToTheDefaultSoTheEngineGuardNeverThrows() throws Exception {
        assertEquals(OverheadIndicatorAsset.DEFAULT_SCALE,
                decode("{ \"Icon\": { \"ItemId\": \"Deco_Scroll\" }, \"Scale\": 0 }", "Zero").effectiveScale());
        assertEquals(OverheadIndicatorAsset.DEFAULT_SCALE,
                decode("{ \"Icon\": { \"ItemId\": \"Deco_Scroll\" }, \"Scale\": -2 }", "Neg").effectiveScale());
    }
}
