package com.ziggfreed.common.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * {@link StackStats#CODEC} JSON/BSON round-trip - the per-stack stat/enhancement metadata blob
 * (mirrors the MMO's {@code item.ItemStatsMetaTest} shape for its own {@code ItemStatsMeta}).
 * Exercised via {@link com.hypixel.hytale.codec.builder.BuilderCodec#decodeJson}/{@code encode}/
 * {@code decode} directly, never through {@code ItemStack}/the asset store (engine state a unit
 * JVM cannot stand up).
 */
class StackStatsTest {

    private static StackStats decode(String json) throws IOException {
        return StackStats.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void decodesEntriesFromJson() throws IOException {
        StackStats stats = decode("{\"Entries\":{\"MMO_Luck\":10,\"MMO_Defense\":5}}");
        assertEquals(Map.of("MMO_Luck", 10.0, "MMO_Defense", 5.0), stats.getEntries());
    }

    @Test
    void missingOptionalFieldsDecodeToNull() throws IOException {
        StackStats stats = decode("{}");
        assertNull(stats.getEntries());
        assertNull(stats.getStampCount());
    }

    @Test
    void encodeThenDecodeRoundTripsEntriesAndStampCount() {
        Map<String, Double> entries = new LinkedHashMap<>();
        entries.put("MMO_Luck_MINING", 5.0);
        entries.put("MMO_Bonus_Fire", 2.5);
        StackStats original = new StackStats(entries, 3);

        ExtraInfo info = new ExtraInfo();
        var bson = StackStats.CODEC.encode(original, info);
        StackStats decoded = StackStats.CODEC.decode(bson, info);

        assertEquals(original.getEntries(), decoded.getEntries());
        assertEquals(original.getStampCount(), decoded.getStampCount());
    }

    @Test
    void decodesStampCountFromJson() throws IOException {
        StackStats stats = decode("{\"Entries\":{\"MMO_Luck\":10},\"StampCount\":4}");
        assertEquals(4, stats.getStampCount());
    }

    @Test
    void entriesOfReturnsNullForNullStack() {
        assertNull(StackStats.entriesOf(null));
    }

    @Test
    void stampCountOfReturnsZeroForNullStack() {
        assertEquals(0, StackStats.stampCountOf(null));
    }

    @Test
    void merge_sumsSameStatIdsAndKeepsFirstSeenOrder() {
        Map<String, Double> base = new LinkedHashMap<>();
        base.put("MMO_Luck_MINING", 3.0);
        base.put("MMO_BonusXp_MINING", 5.0);
        Map<String, Double> added = new LinkedHashMap<>();
        added.put("MMO_Luck_MINING", 4.0);
        added.put("MMO_Bonus_Fire", 2.5);

        Map<String, Double> merged = StackStats.merge(base, added);

        assertEquals(7.0, merged.get("MMO_Luck_MINING"));
        assertEquals(5.0, merged.get("MMO_BonusXp_MINING"));
        assertEquals(2.5, merged.get("MMO_Bonus_Fire"));
        assertEquals(java.util.List.of("MMO_Luck_MINING", "MMO_BonusXp_MINING", "MMO_Bonus_Fire"),
                java.util.List.copyOf(merged.keySet()));
    }

    @Test
    void merge_emptyBaseIsJustTheAddition() {
        Map<String, Double> added = new LinkedHashMap<>();
        added.put("MMO_Luck_MINING", 3.0);

        Map<String, Double> merged = StackStats.merge(Map.of(), added);

        assertEquals(Map.of("MMO_Luck_MINING", 3.0), merged);
    }

    @Test
    void merge_ignoresNullKeysAndValues() {
        Map<String, Double> added = new LinkedHashMap<>();
        added.put("Valid", 1.0);
        added.put(null, 2.0);

        Map<String, Double> merged = StackStats.merge(Map.of(), added);

        assertEquals(1, merged.size());
        assertTrue(merged.containsKey("Valid"));
    }
}
