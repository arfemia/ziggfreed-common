package com.ziggfreed.common.track.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The XP Sources screen writes a source's rows and steps from plain word lists: a row word splits at its LAST equals
 * sign (a set key such as {@code ItemTag:Family=Sword} holds one), a word that does not read is passed over, and only
 * rows that read otherwise than without the owner's word are written, each whole.
 */
class TrackSourceFormTest {

    @Test
    void aRowWordSplitsAtItsLastEqualsSign() {
        List<TrackSourceForm.RowWord> rows = TrackSourceForm.parseRows(List.of(" Ore_Iron*=50@10 ",
                "ItemTag:Family=Sword=2", "Rock_*=3", "broken", "=5", "Ore_*=", "Ore_Gold*=x@3", "Ore_Tin*=5@-1"));
        assertEquals(3, rows.size(), "three words read: " + rows);
        assertEquals(new TrackSourceForm.RowWord("Ore_Iron*", 50.0, 10), rows.get(0));
        assertEquals(new TrackSourceForm.RowWord("ItemTag:Family=Sword", 2.0, 0), rows.get(1));
        assertEquals(new TrackSourceForm.RowWord("Rock_*", 3.0, 0), rows.get(2));
        assertEquals("Ore_Iron*=50@10", TrackSourceForm.rowWord("Ore_Iron*", SourceRow.of(50.0, null, 10)));
        assertEquals("Rock_*=2.5", TrackSourceForm.rowWord("Rock_*", SourceRow.of(2.5, null, null)));
    }

    @Test
    void onlyRowsThatDifferFromThePacksAreWrittenEachWhole() {
        Map<String, SourceRow> below = new LinkedHashMap<>();
        below.put("Ore_Iron*", SourceRow.of(50.0, null, 10));
        below.put("Trork_*", SourceRow.of(1.0, 1.5, null, 400.0, null));
        Map<String, Object> written = TrackSourceForm.changedRows(
                TrackSourceForm.parseRows(List.of("ore_iron*=50@10", "Trork_*=2")), below, below);
        assertFalse(written.containsKey("ore_iron*"), "a row as the packs have it is not written");
        assertTrue(written.containsKey("Trork_*"));
        @SuppressWarnings("unchecked")
        Map<String, Object> trork = (Map<String, Object>) written.get("Trork_*");
        assertEquals(2.0, trork.get("Xp"));
        assertEquals(1.5, trork.get("PerAmount"), "the row's other leaves are kept as they read now");
        assertEquals(400.0, trork.get("Max"));
    }

    @Test
    void stepsReadFromWords() {
        assertEquals(List.of(1.0, 1.0, 0.5, 0.0), TrackSourceForm.parseSteps(List.of("1", " 1", "0.5", "0", "-1",
                "x")));
    }
}
