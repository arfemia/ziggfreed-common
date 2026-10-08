package com.ziggfreed.common.almanac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.counter.CounterMap;

/** A player's "owned once" marks: one key per listed item, set for good, never a season or a season's tally. */
class AlmanacCollectionTest {

    private static AlmanacIndex index() throws Exception {
        return AlmanacIndex.of(Map.of("test_season", AlmanacFixtures.page("""
                { "Sections": [ { "Collection": { "Items": [ { "Item": "Test_Lantern" }, { "Item": "Test_Pumpkin", "Hidden": true } ] } } ] }
                """, "Test_Season")));
    }

    @Test
    void anItemIsMarkedOwnedOnceAndForGoodWithoutRegardToCase() {
        CounterMap tallies = new CounterMap();
        assertFalse(AlmanacCollection.owned(tallies, "Test_Lantern"));
        assertTrue(AlmanacCollection.markOwned(tallies, "Test_Lantern"));
        assertFalse(AlmanacCollection.markOwned(tallies, "test_lantern"), "already owned");
        assertTrue(AlmanacCollection.owned(tallies, "TEST_LANTERN"));
        assertEquals(Map.of("$owned/test_lantern", 1L), tallies.all(), "one key per item, under the reserved $owned");
    }

    @Test
    void onlyAnItemAPageListsIsMarked() throws Exception {
        CounterMap tallies = new CounterMap();
        AlmanacIndex index = index();
        assertTrue(AlmanacCollection.markIfTracked(tallies, index, "Test_Pumpkin"));
        assertFalse(AlmanacCollection.markIfTracked(tallies, index, "Rock_Stone"), "no page lists it: nothing written");
        assertFalse(AlmanacCollection.markIfTracked(tallies, AlmanacIndex.EMPTY, "Test_Lantern"));
        assertEquals(1, tallies.all().size());
    }

    @Test
    void theBagAtPageOpenMarksWhatThePlayerHoldsAndAsksNothingAlreadyOwned() {
        CounterMap tallies = new CounterMap();
        AlmanacCollection.markOwned(tallies, "Test_Lantern");
        List<String> asked = new ArrayList<>();
        int marked = AlmanacCollection.markHeld(tallies, List.of("Test_Lantern", "Test_Pumpkin", "Test_Bomb"),
                id -> { asked.add(id); return id.equals("Test_Pumpkin"); });

        assertEquals(1, marked);
        assertEquals(List.of("Test_Pumpkin", "Test_Bomb"), asked, "an owned item is never looked for again");
        assertTrue(AlmanacCollection.owned(tallies, "Test_Pumpkin"));
    }

    @Test
    void theOwnedMarksNeverReadAsASeasonOrItsTally() {
        CounterMap tallies = new CounterMap();
        AlmanacCollection.markOwned(tallies, "Test_Lantern");
        assertFalse(AlmanacKeys.usableId(AlmanacKeys.OWNED), "no page or stat can be named $owned");
        assertTrue(AlmanacKeys.seasonYears(tallies, "test_season").isEmpty());
        assertFalse(AlmanacCollection.markOwned(tallies, "Bad|Id"), "an id the save format cannot hold is refused");
    }

    @Test
    void aPagesItemsAreItsCollectionsInOrderEachOnce() throws Exception {
        AlmanacEntryAsset page = AlmanacFixtures.page("""
                { "Sections": [ { "Collection": { "Items": [ { "Item": "Test_Lantern" }, { "Item": "Test_Pumpkin" } ] } },
                                { "Collection": { "Items": [ { "Item": "test_lantern" }, { "Item": "Test_Bomb" } ] } } ] }
                """, "Test_Season");
        assertEquals(List.of("Test_Lantern", "Test_Pumpkin", "Test_Bomb"), AlmanacCollection.itemsOf(page));
        assertEquals(List.of(), AlmanacCollection.itemsOf(null));
    }
}
