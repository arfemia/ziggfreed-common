package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The drop-rarity bands are data (R858): every shipped band file decodes, the fold makes one ladder of the enabled
 * bands narrowest first, and a chance reads as the first band it is within, the widest above every band, and nothing
 * with no chance or no band. Untagged: nothing here builds an engine item.
 */
class DropRarityTest {

    private static final String SHIPPED = "/Server/ZiggfreedCommon/DropRarities/";
    private static final List<String> SHIPPED_QUALITIES = List.of("Legendary", "Epic", "Rare", "Uncommon", "Common");

    @AfterEach
    void clearTheFold() {
        DropRarityConfig.getInstance().mergePackLayer(Map.of());
        DropRarityConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @Test
    void theShippedBandsFoldIntoOneLadderNarrowestFirst() throws IOException {
        Map<String, DropRarityAsset> layer = new LinkedHashMap<>();
        for (String quality : List.of("Common", "Rare", "Legendary", "Uncommon", "Epic")) {
            layer.put(quality, shipped(quality));
        }
        DropRarityConfig.getInstance().mergePackLayer(layer);

        assertEquals(SHIPPED_QUALITIES, DropRarityConfig.getInstance().bands().stream()
                .map(DropRarityConfig.Band::quality).toList(), "one band per shipped file, the narrowest first");
        assertEquals(SHIPPED_QUALITIES.get(0), DropRarity.qualityOf(0.0), "the narrowest band takes the least chance");
        assertEquals(SHIPPED_QUALITIES.get(SHIPPED_QUALITIES.size() - 1), DropRarity.qualityOf(100.0),
                "a sure drop reads as the widest band");
        assertNull(DropRarity.qualityOf(null), "a reward with no chance keeps its item's own quality");
    }

    @Test
    void aChanceReadsAsTheFirstBandItIsWithinAndTheWidestAboveThemAll() {
        List<DropRarityConfig.Band> bands = List.of(new DropRarityConfig.Band("Narrow", 2.0),
                new DropRarityConfig.Band("Wide", 30.0));
        assertEquals("Narrow", DropRarity.qualityOf(2.0, bands), "a band's own bound is within it");
        assertEquals("Wide", DropRarity.qualityOf(2.5, bands));
        assertEquals("Wide", DropRarity.qualityOf(80.0, bands), "above every band reads as the widest");
        assertNull(DropRarity.qualityOf(Double.NaN, bands));
        assertNull(DropRarity.qualityOf(1.0, List.of()), "no band, no rarity");
    }

    @Test
    void aBandSwitchedOffOrWithNoBoundIsLeftOutOfTheLadder() throws IOException {
        DropRarityConfig.getInstance().mergePackLayer(Map.of(
                "Rare", decode("Rare", "{ \"AtMostPercent\": 20 }"),
                "Epic", decode("Epic", "{ \"AtMostPercent\": 5, \"Enabled\": false }"),
                "Mythic", decode("Mythic", "{ }")));

        assertEquals(List.of("Rare"), DropRarityConfig.getInstance().bands().stream()
                .map(DropRarityConfig.Band::quality).toList());
        assertEquals("Rare", DropRarity.qualityOf(3.0), "a switched-off band's drops read as the next band up");
    }

    @Nonnull
    private static DropRarityAsset shipped(@Nonnull String quality) throws IOException {
        String path = SHIPPED + quality + ".json";
        try (InputStream in = DropRarityTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing shipped band: " + path);
            return decode(quality, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Nonnull
    private static DropRarityAsset decode(@Nonnull String id, @Nonnull String json) throws IOException {
        return DropRarityAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(DropRarityAsset.class, id, null)));
    }
}
