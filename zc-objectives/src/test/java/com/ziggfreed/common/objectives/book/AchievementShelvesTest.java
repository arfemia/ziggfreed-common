package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.book.AchievementShelves.Shelf;

/**
 * Where the achievement tab lists an achievement, away from the page that paints it (no test reaches
 * a page: its static logger needs the engine's log manager). The rule: what a player EARNED is listed
 * whatever its circulation says, and circulation and visibility decide only what they have not.
 */
class AchievementShelvesTest {

    private static final BooleanSupplier SEEN = () -> true;
    private static final BooleanSupplier UNSEEN = () -> false;

    @Test
    void anEarnedAchievementOutOfCirculationStaysOnItsShelf() {
        assertEquals(Shelf.BROWSE, AchievementShelves.shelfOf(false, false, true, UNSEEN),
                "an owner retiring it never takes it from whoever earned it");
        assertEquals(Shelf.FEATS, AchievementShelves.shelfOf(false, true, true, UNSEEN),
                "a closed yearly copy its player earned sits with the feats");
    }

    @Test
    void anUnearnedAchievementNeedsCirculationAndSight() {
        assertEquals(Shelf.BROWSE, AchievementShelves.shelfOf(true, false, false, SEEN));
        assertEquals(Shelf.NONE, AchievementShelves.shelfOf(false, false, false, SEEN), "out of circulation");
        assertEquals(Shelf.NONE, AchievementShelves.shelfOf(true, false, false, UNSEEN), "hidden or gated");
        assertEquals(Shelf.NONE, AchievementShelves.shelfOf(true, true, false, SEEN), "a feat shows once earned");
    }

    @Test
    void visibilityIsAskedOnlyOfWhatCouldStillBeListed() {
        BooleanSupplier mustNotBeAsked = () -> {
            throw new AssertionError("the gate walk was asked");
        };
        assertEquals(Shelf.BROWSE, AchievementShelves.shelfOf(true, false, true, mustNotBeAsked));
        assertEquals(Shelf.NONE, AchievementShelves.shelfOf(false, false, false, mustNotBeAsked));
        assertEquals(Shelf.NONE, AchievementShelves.shelfOf(true, true, false, mustNotBeAsked));
    }

    @Test
    void anEarnedAchievementCountsInItsCategoryWhateverItsCirculation() {
        assertTrue(AchievementShelves.countsInCategory(false, false, true));
        assertTrue(AchievementShelves.countsInCategory(true, false, false));
        assertFalse(AchievementShelves.countsInCategory(false, false, false));
        assertFalse(AchievementShelves.countsInCategory(true, true, true), "feats have their own section");
    }

    @Test
    void theHeaderCountsEveryEarnedAchievementThatIsNotAFeat() {
        assertTrue(AchievementShelves.countsAsEarned(false, true));
        assertFalse(AchievementShelves.countsAsEarned(true, true));
        assertFalse(AchievementShelves.countsAsEarned(false, false));
    }

    @Test
    void aCapstonesListsShowWhatWasEarnedEvenOutOfCirculation() {
        assertTrue(AchievementShelves.listsAsCapstoneChild(false, true, true), "earned: listed, hidden or not");
        assertTrue(AchievementShelves.listsAsCapstoneChild(true, false, false));
        assertFalse(AchievementShelves.listsAsCapstoneChild(true, true, false), "hidden and unearned");
        assertFalse(AchievementShelves.listsAsCapstoneChild(false, false, false));
        assertTrue(AchievementShelves.listsAsCapstone(false, true));
        assertTrue(AchievementShelves.listsAsCapstone(true, false));
        assertFalse(AchievementShelves.listsAsCapstone(false, false));
    }
}
