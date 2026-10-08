package com.ziggfreed.common.objectives.book.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.book.BookState;
import com.ziggfreed.common.objectives.book.ObjectiveBookPage;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * What the Achievements tab shows before anything paints: which views its segment offers (Statistics only while a
 * contributed source has a section), which view a state lands on (Overview by default, and from any selection), what
 * each view shows, the milestone card and stat only with a ladder, how far a section's rows reach (Show more, and
 * always far enough for the selected row), and that the selected id rides every reopen the tab asks for.
 */
class AchievementsTabPlanTest {

    private static final String ACH = ObjectiveBookPage.TAB_ACHIEVEMENTS;

    @Nonnull
    private static BookState fresh() {
        return BookState.of(ACH);
    }

    @Nonnull
    private static BookState browsingWith(@Nonnull String selected) {
        return fresh().withView(BookState.VIEW_BROWSE).withSelected(selected);
    }

    // ==================== views ====================

    @Test
    void eachViewTabCarriesItsOwnPicture() {
        assertEquals("Deco_Map", AchievementsTab.viewPicture(BookState.VIEW_OVERVIEW), "the lay of the land (M327)");
        assertEquals("Furniture_Village_Bookcase", AchievementsTab.viewPicture(BookState.VIEW_BROWSE),
                "browse the shelves");
        assertEquals("Ingredient_Bar_Copper", AchievementsTab.viewPicture(BookState.VIEW_STATISTICS),
                "bars, a nod to a bar chart, apart from the Leaderboard's gold bar");
        assertNull(AchievementsTab.viewPicture("no-such-view"), "an unknown view draws no picture");
    }

    @Test
    void withoutContributionsTheSegmentOffersOverviewAndBrowse() {
        AchievementsTab.Plan plan = AchievementsTab.plan(fresh(), false, false);
        assertEquals(List.of(BookState.VIEW_OVERVIEW, BookState.VIEW_BROWSE), plan.views());
    }

    @Test
    void aContributedSectionAddsStatistics() {
        AchievementsTab.Plan plan = AchievementsTab.plan(fresh(), true, false);
        assertEquals(List.of(BookState.VIEW_OVERVIEW, BookState.VIEW_BROWSE, BookState.VIEW_STATISTICS),
                plan.views());
    }

    @Test
    void theDefaultViewIsOverview() {
        assertEquals(BookState.VIEW_OVERVIEW, AchievementsTab.plan(fresh(), true, true).view());
        assertEquals(BookState.VIEW_OVERVIEW, AchievementsTab.plan(fresh().withView("nonsense"), true, true).view(),
                "an unknown view reads as the default");
    }

    @Test
    void statisticsWithNoSourceFallsBackToOverview() {
        BookState onStatistics = fresh().withView(BookState.VIEW_STATISTICS);
        assertEquals(BookState.VIEW_OVERVIEW, AchievementsTab.plan(onStatistics, false, false).view());
        assertEquals(BookState.VIEW_STATISTICS, AchievementsTab.plan(onStatistics, true, false).view());
    }

    @Test
    void eachViewShowsItsOwnParts() {
        AchievementsTab.Plan overview = AchievementsTab.plan(fresh(), true, true);
        assertTrue(overview.overview());
        assertFalse(overview.split(), "the overview has no list and page");
        assertFalse(overview.filters(), "the status segments, category and sort are Browse's");
        assertTrue(overview.search(), "a search from the overview opens Browse");

        AchievementsTab.Plan browse = AchievementsTab.plan(fresh().withView(BookState.VIEW_BROWSE), true, true);
        assertTrue(browse.browse());
        assertTrue(browse.split());
        assertTrue(browse.filters());
        assertTrue(browse.search());
        assertFalse(browse.overview());

        AchievementsTab.Plan statistics = AchievementsTab.plan(fresh().withView(BookState.VIEW_STATISTICS), true, true);
        assertTrue(statistics.statistics());
        assertTrue(statistics.split(), "statistics are a list and a page too");
        assertFalse(statistics.filters());
        assertFalse(statistics.search());
    }

    @Test
    void theMilestoneCardAndStatShowOnlyWithALadder() {
        AchievementsTab.Plan withLadder = AchievementsTab.plan(fresh(), false, true);
        assertTrue(withLadder.milestoneCard());
        assertTrue(withLadder.milestoneStat());

        AchievementsTab.Plan without = AchievementsTab.plan(fresh(), false, false);
        assertFalse(without.milestoneCard());
        assertFalse(without.milestoneStat());

        AchievementsTab.Plan browsing = AchievementsTab.plan(fresh().withView(BookState.VIEW_BROWSE), false, true);
        assertFalse(browsing.milestoneCard(), "the card lives on the overview");
        assertTrue(browsing.milestoneStat(), "the header's stat shows on every view");
    }

    // ==================== the selection ====================

    @Test
    void theSelectedIdSurvivesEveryReopen() {
        BookState start = browsingWith("ghoul_breaker_2026");
        List<BookState> reopened = List.of(
                AchievementsTab.toStatus(start, BrowseFilter.STATUS_EARNED),
                AchievementsTab.toSearch(start.withSearch("ghoul")),
                AchievementsTab.fromTile(start.withView(BookState.VIEW_OVERVIEW), "combat"),
                AchievementsTab.fromTile(start.withView(BookState.VIEW_OVERVIEW), AchievementOverview.FEATS_TILE),
                start.withView(BookState.VIEW_OVERVIEW),
                start.withView(BookState.VIEW_STATISTICS),
                start.withFilters("seasons", null, BrowseFilter.SORT_AZ, null, null),
                start.withSection("pinned", false));
        for (BookState state : reopened) {
            assertEquals("ghoul_breaker_2026", state.selectedId(), "kept through " + state);
            for (boolean statistics : List.of(false, true)) {
                assertEquals("ghoul_breaker_2026", AchievementsTab.plan(state, statistics, true).selectedId());
            }
        }
    }

    @Test
    void theOverviewIsReachableFromASelection() {
        BookState selected = browsingWith("ghoul_breaker_2026");
        BookState overview = selected.withView(BookState.VIEW_OVERVIEW);
        assertEquals(BookState.VIEW_OVERVIEW, AchievementsTab.plan(overview, false, false).view(),
                "a selection never forces the page over the overview");
        BookState back = overview.withView(BookState.VIEW_BROWSE);
        assertEquals(BookState.VIEW_BROWSE, AchievementsTab.plan(back, false, false).view());
        assertEquals("ghoul_breaker_2026", AchievementsTab.plan(back, false, false).selectedId());
    }

    @Test
    void aStripRowOpensBrowseOnItWithTheFiltersCleared() {
        BookState narrowed = fresh().withFilters("combat", BrowseFilter.STATUS_EARNED, null, "ghoul", null);
        BookState opened = AchievementsTab.toRow(narrowed, "jacks_guest_2026");
        assertEquals(BookState.VIEW_BROWSE, opened.view());
        assertEquals("jacks_guest_2026", opened.selectedId());
        assertEquals(BookState.ALL, opened.category());
        assertEquals(BookState.ALL, opened.status());
        assertEquals("", opened.search());
    }

    // ==================== the overview's ways into Browse ====================

    @Test
    void aCategoryTileOpensBrowseOnThatCategory() {
        BookState state = fresh().withFilters(null, BrowseFilter.STATUS_EARNED, null, "old", null);
        BookState next = AchievementsTab.fromTile(state, "seasons");
        assertEquals(BookState.VIEW_BROWSE, next.view());
        assertEquals("seasons", next.category());
        assertEquals(BookState.ALL, next.status(), "a tile shows the whole category");
        assertEquals("", next.search());
    }

    @Test
    void theFeatsTileOpensBrowseOnTheFeatsStatus() {
        BookState next = AchievementsTab.fromTile(fresh().withFilters("combat", null, null, null, null),
                AchievementOverview.FEATS_TILE);
        assertEquals(BookState.VIEW_BROWSE, next.view());
        assertEquals(BookState.ALL, next.category());
        assertEquals(BrowseFilter.STATUS_FEATS, next.status());
    }

    @Test
    void theHeroShowAndTheStatusSegmentsOpenBrowse() {
        BookState next = AchievementsTab.toStatus(fresh(), BrowseFilter.STATUS_WAITING);
        assertEquals(BookState.VIEW_BROWSE, next.view());
        assertEquals(BrowseFilter.STATUS_WAITING, next.status());
    }

    @Test
    void aSearchFromTheOverviewOpensBrowse() {
        BookState next = AchievementsTab.toSearch(fresh().withSearch("lantern"));
        assertEquals(BookState.VIEW_BROWSE, next.view());
        assertEquals("lantern", next.search());
    }

    // ==================== the toolbar's values ====================

    @Test
    void theSortDropdownAlwaysHoldsARealId() {
        assertEquals(BrowseFilter.SORT_DEFAULT, AchievementsTab.sortValue(fresh()));
        assertFalse(AchievementsTab.sortValue(fresh()).isBlank(), "a blank value draws an empty dropdown");
        assertEquals(BrowseFilter.SORT_DEFAULT, AchievementsTab.sortValue(fresh().withFilters(null, null, "bogus",
                null, null)));
        assertEquals(BrowseFilter.SORT_AZ, AchievementsTab.sortValue(fresh().withFilters(null, null, "az", null,
                null)));
        for (String sort : BrowseFilter.SORTS) {
            assertFalse(sort.isBlank());
            assertTrue(AchievementsTab.sortKey(sort).startsWith("book.achievements.sort."));
        }
        assertEquals(BrowseFilter.SORTS.size(), BrowseFilter.SORTS.stream().map(AchievementsTab::sortKey).distinct()
                .count(), "each sort reads its own words");
    }

    @Test
    void anUnknownStatusReadsAsAll() {
        assertEquals(BrowseFilter.ALL, AchievementsTab.statusValue(fresh().withFilters(null, "unlocked", null, null,
                null)));
        for (String status : BrowseFilter.STATUSES) {
            assertEquals(status, AchievementsTab.statusValue(fresh().withFilters(null, status, null, null, null)));
            assertTrue(AchievementsTab.statusKey(status).startsWith("book.achievements.status."));
        }
    }

    // ==================== how far a section reaches ====================

    @Test
    void aLongSectionShowsItsCapUntilAskedForMore() {
        LedgerModel model = LedgerModel.of(List.of(section("c.combat", 95)));
        Map<String, Integer> caps = new HashMap<>();
        assertEquals(LedgerSection.DEFAULT_CAP, AchievementsTab.capped(model, caps, null).sections().get(0).cap());

        caps.put("c.combat", AchievementsTab.nextCap(model.sections().get(0)));
        LedgerSection more = AchievementsTab.capped(model, caps, null).sections().get(0);
        assertEquals(2 * LedgerSection.DEFAULT_CAP, more.cap(), "Show 40 more shows the 40 it promised");

        caps.put("c.combat", AchievementsTab.nextCap(more));
        assertEquals(95, AchievementsTab.capped(model, caps, null).sections().get(0).cap(),
                "never past the last row");
    }

    @Test
    void theSelectedRowIsAlwaysShown() {
        LedgerModel model = LedgerModel.of(List.of(section("pinned", 3), section("c.combat", 95)));
        LedgerModel capped = AchievementsTab.capped(model, Map.of(), "c.combat#57");
        LedgerSection combat = capped.sections().get(1);
        assertTrue(combat.cap() > 57, "row 57 is inside the cap: " + combat.cap());
        assertEquals(LedgerSection.DEFAULT_CAP, capped.sections().get(0).cap(), "other sections keep theirs");
        assertEquals(model.firstSelectable(), capped.firstSelectable());
    }

    @Test
    void aClaimMovesNothingWhenEverySectionAndRowStaysPut() {
        LedgerModel before = LedgerModel.of(List.of(section("pinned", 2), section("c.combat", 4)));
        LedgerModel same = LedgerModel.of(List.of(section("pinned", 2), section("c.combat", 4)));
        LedgerModel moved = LedgerModel.of(List.of(section("pinned", 2), section("c.combat", 3)));
        assertEquals(AchievementsTab.structure(before), AchievementsTab.structure(same));
        assertNotEquals(AchievementsTab.structure(before), AchievementsTab.structure(moved));
    }

    @Test
    void noSelectionReadsAsNoneInThePlan() {
        assertNull(AchievementsTab.plan(fresh(), false, false).selectedId());
    }

    @Nonnull
    private static LedgerSection section(@Nonnull String id, int rows) {
        List<LedgerRow> out = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            out.add(new LedgerRow(id + "#" + i, Msg.raw("Row " + i), null, Picture.NONE, Tone.ACTIVE, null, null, null,
                    Mark.NONE, false));
        }
        return new LedgerSection(id, Msg.raw(id), out, true);
    }
}
