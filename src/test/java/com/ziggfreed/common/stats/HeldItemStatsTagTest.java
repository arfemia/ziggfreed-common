package com.ziggfreed.common.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Pure parse-core coverage for {@link HeldItemStatsTag} - good/bad/reserved entry shapes. Never
 * touches an {@link com.hypixel.hytale.server.core.inventory.ItemStack} (engine state a unit JVM
 * cannot stand up); {@link HeldItemStatsTag#entriesOf(com.hypixel.hytale.server.core.inventory.ItemStack)}'s
 * null-stack fast path is exercised directly.
 */
class HeldItemStatsTagTest {

    @Test
    void entriesOfReturnsEmptyMapForNullStack() {
        assertEquals(Map.of(), HeldItemStatsTag.entriesOf(null));
    }

    @Test
    void parse_validAdditiveEntry() {
        HeldItemStatsTag.ParsedEntry entry = HeldItemStatsTag.parseEntry("MMO_Luck:10");
        assertEquals("MMO_Luck", entry.statId);
        assertEquals(10.0, entry.amount);
    }

    @Test
    void parse_trimsWhitespace() {
        HeldItemStatsTag.ParsedEntry entry = HeldItemStatsTag.parseEntry("  MMO_Luck : 7.5  ");
        assertEquals("MMO_Luck", entry.statId);
        assertEquals(7.5, entry.amount);
    }

    @Test
    void parse_rejectsReservedMultiplicativeForm() {
        assertNull(HeldItemStatsTag.parseEntry("MMO_Luck:mult:1.5"));
    }

    @Test
    void parse_rejectsMultiplicativeFormCaseInsensitive() {
        assertNull(HeldItemStatsTag.parseEntry("MMO_Luck:MULT:2"));
    }

    @Test
    void parse_rejectsMissingColon() {
        assertNull(HeldItemStatsTag.parseEntry("MMO_Luck"));
    }

    @Test
    void parse_rejectsNonNumericAmount() {
        assertNull(HeldItemStatsTag.parseEntry("MMO_Luck:abc"));
    }

    @Test
    void parse_rejectsEmptyStatId() {
        assertNull(HeldItemStatsTag.parseEntry(":10"));
    }

    @Test
    void parse_rejectsBlankEntry() {
        assertNull(HeldItemStatsTag.parseEntry("   "));
    }

    @Test
    void parse_rejectsUnexpectedThreeSegmentForm() {
        assertNull(HeldItemStatsTag.parseEntry("MMO_Luck:5:extra"));
    }

    @Test
    void parseArray_sumsDuplicateStatIdsAndSkipsMalformed() {
        Map<String, Double> parsed = HeldItemStatsTag.parse(new String[] {
                "MMO_Luck:5", "MMO_Luck:3", "malformed", "MMO_BonusXp:2.5", null
        });
        assertEquals(8.0, parsed.get("MMO_Luck"));
        assertEquals(2.5, parsed.get("MMO_BonusXp"));
        assertEquals(2, parsed.size());
    }

    @Test
    void parseArray_emptyArrayYieldsEmptyMap() {
        assertTrue(HeldItemStatsTag.parse(new String[0]).isEmpty());
    }
}
