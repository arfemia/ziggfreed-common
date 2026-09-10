package com.ziggfreed.common.ui.hud.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The shared card record: its one leaf reads back normalised, a child keeps it under {@code
 * Parent}, a value that is not a hex reads as unauthored, and the fold answers the shipped look
 * until a record lands and the record's colour once one does.
 */
class HudCardAssetTest {

    static HudCardAsset card(String json, String id) throws IOException {
        return card(json, id, null);
    }

    /** A record decoded OVER {@code parent}, the way an owner entry is decoded over the pack's file. */
    static HudCardAsset card(String json, String id, HudCardAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(HudCardAsset.class, id,
                parent == null ? null : parent.getId());
        return HudCardAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    @AfterEach
    void clearFold() {
        HudCardConfig.getInstance().mergePackLayer(Map.of());
        HudCardConfig.getInstance().mergeOwnerLayer(Map.of());
        HudCardLook.resetWarnings();
    }

    @Test
    void theLeafReadsBackNormalised() throws Exception {
        assertEquals("#ffffffb8", card("{ \"Color\": \" #FFFFFFB8 \" }", "Default").color());
        assertEquals("#6fbf73", card("{ \"Color\": \"6FBF73\" }", "Default").color(), "hash optional, case folded");
    }

    @Test
    void absentAndMalformedReadAsUnauthored() throws Exception {
        assertNull(card("{ }", "Default").color(), "nothing stated");
        assertNull(card("{ \"Color\": \"\" }", "Default").color(), "a blank is nothing stated");
        assertNull(card("{ \"Color\": \"#not-a-colour\" }", "Default").color(),
                "ignored with one warning, so the layer below decides");
    }

    @Test
    void aChildKeepsTheColourItDidNotRestateAndRestatesOneItDid() throws Exception {
        HudCardAsset shipped = card("{ \"Color\": \"#112233\" }", "Default");
        assertEquals("#112233", card("{ }", "default", shipped).color(), "inherited whole");
        assertEquals("#445566", card("{ \"Color\": \"#445566\" }", "default", shipped).color(), "restated");
    }

    @Test
    void theFoldIsTheShippedLookUntilARecordLandsAndTheRecordsColourOnceItDoes() throws Exception {
        assertNull(HudCardConfig.getInstance().shared(), "no record anywhere");
        assertNull(HudCardConfig.getInstance().sharedColor());
        assertSame(HudCardLook.SHIPPED, HudCardLook.resolve(null, HudCardConfig.getInstance().sharedColor()),
                "every card at its shipped look, nothing pushed");

        HudCardConfig.getInstance().mergePackLayer(Map.of("Default", card("{ \"Color\": \"#ffffffff\" }", "Default")));
        assertEquals("#ffffffff", HudCardConfig.getInstance().sharedColor(), "the shipped file's value");
        assertNull(HudCardLook.resolve(null, HudCardConfig.getInstance().sharedColor()).cardColor(),
                "which is the identity, so still nothing pushed");

        HudCardConfig.getInstance().mergeOwnerLayer(Map.of("default",
                card("{ \"Color\": \"#ffffffb8\" }", "default", HudCardConfig.getInstance().shared())));
        assertEquals("#ffffffb8", HudCardConfig.getInstance().sharedColor(), "the owner's word over the pack's");
        assertEquals("#ffffffb8", HudCardLook.resolve(null, HudCardConfig.getInstance().sharedColor()).cardColor(),
                "a card with no leaf of its own takes it");
        assertEquals("#112233", HudCardLook.resolve("#112233", HudCardConfig.getInstance().sharedColor()).cardColor(),
                "a card with one keeps its own");
    }
}
