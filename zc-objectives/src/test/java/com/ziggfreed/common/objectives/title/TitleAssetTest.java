package com.ziggfreed.common.objectives.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * One title per file: its leaves, what a child keeps under {@code Parent}, the fold's "is this
 * title on offer" read and picker order, and the owner's last word.
 */
class TitleAssetTest {

    @TempDir
    Path ownerDir;

    static TitleAsset title(String json, String id) throws IOException {
        return title(json, id, null);
    }

    /** A title decoded OVER {@code parent}, the way an owner entry is decoded over the pack's file. */
    static TitleAsset title(String json, String id, TitleAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(TitleAsset.class, id,
                parent == null ? null : parent.getId());
        return TitleAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    @AfterEach
    void clearFold() {
        TitleConfig.getInstance().mergePackLayer(Map.of());
        TitleConfig.getInstance().mergeOwnerLayer(Map.of());
        TitleOwnerLayers.setDirectory(TitleOwnerLayers.DEFAULT_DIRECTORY);
    }

    @Test
    void anEmptyFileIsAnEnabledTitleSortedFirstUnderItsLowerCasedFileName() throws Exception {
        TitleAsset bare = title("{}", "Hallows_Eve_Hallowed");

        assertEquals("hallows_eve_hallowed", bare.getId());
        assertTrue(bare.enabled());
        assertEquals(0, bare.order());
        assertNull(bare.text());
    }

    @Test
    void theLeavesReadBack() throws Exception {
        TitleAsset t = title("{ \"Enabled\": false, \"Order\": 20, "
                + "\"Text\": { \"TitleKey\": \"ev.king\", \"FlavorKey\": \"ev.king.flavor\" } }", "Pumpkin_King");

        assertFalse(t.enabled());
        assertEquals(20, t.order());
        assertEquals("ev.king", t.text().getTitleKey());
        assertEquals("ev.king.flavor", t.text().getFlavorKey());
    }

    @Test
    void aChildKeepsWhatItDidNotRestate() throws Exception {
        TitleAsset shipped = title("{ \"Order\": 20, \"Text\": { \"TitleKey\": \"ev.king\" } }", "Pumpkin_King");
        TitleAsset owner = title("{ \"Enabled\": false }", "pumpkin_king", shipped);

        assertFalse(owner.enabled());
        assertEquals(20, owner.order());
        assertEquals("ev.king", owner.text().getTitleKey());
    }

    @Test
    void onlyAnEnabledTitleIsShownAndTheListingSortsByOrderThenId() throws Exception {
        TitleConfig titles = TitleConfig.getInstance();
        titles.mergePackLayer(Map.of(
                "Zeta", title("{ \"Order\": 10 }", "Zeta"),
                "Alpha", title("{ \"Order\": 10 }", "Alpha"),
                "First", title("{ \"Order\": 1 }", "First"),
                "Off", title("{ \"Enabled\": false }", "Off")));

        assertNotNull(titles.shown("ALPHA"));
        assertNull(titles.shown("off"), "a title switched off is absent, not locked");
        assertNull(titles.shown("never_defined"));
        assertNull(titles.shown(null));
        assertEquals(List.of("first", "alpha", "zeta"),
                titles.listing(List.of("zeta", "Off", "never_defined", "alpha", "First", "ALPHA")),
                "Order first, id on a tie; unknown, switched-off and repeated ids dropped");
        assertEquals(List.of(), titles.listing(List.of()));
    }

    @Test
    void anOwnerEntrySwitchesAShippedTitleOffAndKeepsTheRest() throws Exception {
        TitleConfig.getInstance().mergePackLayer(Map.of("Pumpkin_King",
                title("{ \"Order\": 20, \"Text\": { \"TitleKey\": \"ev.king\" } }", "Pumpkin_King")));
        Files.writeString(ownerDir.resolve(TitleOwnerLayers.TITLES_FILE),
                "{ \"$Comment\": \"server owner\", \"Pumpkin_King\": { \"Enabled\": false } }",
                StandardCharsets.UTF_8);
        TitleOwnerLayers.setDirectory(ownerDir);

        TitleOwnerLayers.reload();

        TitleAsset folded = TitleConfig.getInstance().resolve("pumpkin_king");
        assertNotNull(folded);
        assertFalse(folded.enabled());
        assertEquals(20, folded.order(), "the owner's one leaf keeps the shipped rest");
        assertNull(TitleConfig.getInstance().shown("pumpkin_king"));
    }
}
