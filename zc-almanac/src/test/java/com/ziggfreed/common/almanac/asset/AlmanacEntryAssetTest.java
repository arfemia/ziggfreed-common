package com.ziggfreed.common.almanac.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.AlmanacFixtures;
import com.ziggfreed.common.progress.MatchMode;

/** A season page's decode contract, and what native {@code Parent} does to its stat lines. */
class AlmanacEntryAssetTest {

    @Test
    void aPageDecodesEveryLeafAndIsKeyedByItsLowerCasedFileName() throws Exception {
        AlmanacEntryAsset page = AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season");

        assertEquals("test_season", page.getId());
        assertEquals("almanac.test.title", page.titleKey());
        assertEquals("almanac.test.flavor", page.flavorKey());
        assertEquals("Test_Icon", page.getIcon());
        assertEquals(10, page.orderOrLast());
        assertEquals("Test_Keepsake", page.getKeepsake());

        AlmanacStatAsset bombs = page.getStats().get("Bombs_Thrown");
        assertNotNull(bombs);
        assertEquals("USE_ITEM", bombs.getKind());
        assertEquals(MatchMode.PREFIX, bombs.effectiveMatchMode());
        assertEquals("Throw", bombs.getQualifier());
        assertEquals("almanac.test.bombs", bombs.getTextKey());
        assertEquals("Test_Bomb", bombs.getIcon());
        assertEquals(10, bombs.orderOrLast());
        assertFalse(bombs.isLiveOnly());
        assertTrue(page.getStats().get("Ghouls").isLiveOnly(), "an unauthored LiveOnly counts only while the season is on");
    }

    @Test
    void anEmptyPageReadsAsNothingAuthored() throws Exception {
        AlmanacEntryAsset page = AlmanacFixtures.page("{}", "Bare_Season");

        assertNull(page.titleKey());
        assertNull(page.getIcon());
        assertNull(page.getKeepsake());
        assertEquals(Integer.MAX_VALUE, page.orderOrLast(), "an unordered season sorts after every ordered one");
        assertTrue(page.getStats().isEmpty());
    }

    @Test
    void aChildRetunesOneLineByItsKeyAndKeepsTheRest() throws Exception {
        AlmanacEntryAsset parent = AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season");
        AlmanacEntryAsset child = AlmanacFixtures.page("{ \"Stats\": { \"Ghouls\": { \"Order\": 5 } } }",
                "Test_Season_Retuned", "test_season", parent);

        assertEquals(5, child.getStats().get("Ghouls").orderOrLast());
        assertEquals("KILL_ENTITY", child.getStats().get("Ghouls").getKind(), "the retuned line keeps its other leaves");
        assertNotNull(child.getStats().get("Bombs_Thrown"), "a line the child did not mention stays");
        assertEquals("Test_Keepsake", child.getKeepsake(), "a top-level leaf the child did not mention stays");
    }
}
