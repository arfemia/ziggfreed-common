package com.ziggfreed.common.almanac.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.AlmanacBootstrap;
import com.ziggfreed.common.almanac.AlmanacFixtures;
import com.ziggfreed.common.ui.page.PageParts;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The season page on the page kit's part registry: its six parts registered in the order that picks an entry's
 * drawn part, and a {@code Sections} entry naming a group that is no part of the page skipped with one warning.
 */
class AlmanacPartsTest {

    @Test
    void theSixPartsRegisterInTheOrderThatPicksTheDrawnPart() {
        AlmanacBootstrap.registerParts();
        assertEquals(AlmanacSectionAsset.PARTS, PageParts.of(AlmanacSectionAsset.PAGE).names());
    }

    @Test
    void anEntryNamingNoPartOfThePageIsSkippedWithOneWarning() throws Exception {
        AlmanacEntryAsset page = AlmanacFixtures.page("""
                { "Sections": [ { "Picks": {} }, { "Tallies": {}, "Road": { "Steps": 3 } } ] }
                """, "Test_Season");

        assertNull(page.sections().get(0).part(), "a group no part is named for draws nothing");
        assertEquals("Tallies", page.sections().get(1).part(), "a part beside an unknown group still draws");
        List<Finding> findings = page.findings();
        assertEquals(List.of(AlmanacEntryAsset.FINDING_SECTION_UNKNOWN_PART, AlmanacEntryAsset.FINDING_SECTION_UNKNOWN_PART),
                findings.stream().map(Finding::code).toList(), "one warning per entry, never also SECTION_EMPTY");
        assertTrue(findings.stream().allMatch(f -> f.severity() == Severity.WARNING), findings.toString());
        assertTrue(findings.get(0).message().contains("'Picks'"), findings.get(0).message());
    }
}
