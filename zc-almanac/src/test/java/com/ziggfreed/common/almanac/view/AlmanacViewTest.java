package com.ziggfreed.common.almanac.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.almanac.AlmanacCalendar;
import com.ziggfreed.common.almanac.AlmanacCalendar.SeasonState;
import com.ziggfreed.common.almanac.AlmanacFixtures;
import com.ziggfreed.common.almanac.AlmanacKeys;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.view.AlmanacView.Banner;
import com.ziggfreed.common.almanac.view.AlmanacView.Detail;
import com.ziggfreed.common.almanac.view.AlmanacView.Feat;
import com.ziggfreed.common.almanac.view.AlmanacView.Keepsake;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.StatLine;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.subject.Subject;

/** What the Almanac shows, decided away from any page: seasons, a season's detail, and the banner. */
class AlmanacViewTest {

    private static final Subject ALICE = new Subject(new UUID(0, 1), "Alice", null);

    private static AchievementEngine engine(Achievement... achievements) {
        AchievementEngine engine = AchievementEngine.builder().nativeEvents(false).build();
        engine.setAchievements(List.of(achievements));
        return engine;
    }

    private static Season live2026() {
        return new Season("test_season", "almanac.test.title", null, "Test_Icon", true, 2026);
    }

    private static Season between() {
        return new Season("test_season", "almanac.test.title", null, "Test_Icon", false, 0);
    }

    private static AlmanacEntryAsset seasonPage() throws Exception {
        return AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season");
    }

    @Test
    void onlySeasonsTheCalendarAnswersForAreListedOnNowFirstThenByOrderThenId() throws Exception {
        Map<String, AlmanacEntryAsset> pages = Map.of(
                "late", AlmanacFixtures.page("{ \"Order\": 20 }", "Late"),
                "early", AlmanacFixtures.page("{ \"Order\": 10 }", "Early"),
                "gone", AlmanacFixtures.page("{ \"Order\": 1 }", "Gone"),
                "unordered", AlmanacFixtures.page("{}", "Unordered"));
        AlmanacCalendar calendar = id -> switch (id) {
            case "late" -> SeasonState.liveIn(2026);
            case "early", "unordered" -> SeasonState.BETWEEN;
            default -> null;
        };

        List<Season> seasons = AlmanacView.seasons(pages, calendar);

        assertEquals(List.of("late", "early", "unordered"), seasons.stream().map(Season::eventId).toList(),
                "a season the calendar does not answer for is absent; the one on now leads; the rest read by "
                        + "Order, then id");
        assertTrue(seasons.get(0).live());
        assertEquals(2026, seasons.get(0).liveYear());
        assertFalse(seasons.get(1).live());
    }

    @Test
    void severalSeasonsOnAtOnceKeepTheirOrderAmongThemselves() throws Exception {
        Map<String, AlmanacEntryAsset> pages = Map.of(
                "harvest", AlmanacFixtures.page("{ \"Order\": 11 }", "Harvest"),
                "autumn", AlmanacFixtures.page("{ \"Order\": 10 }", "Autumn"),
                "spring", AlmanacFixtures.page("{ \"Order\": 1 }", "Spring"));
        AlmanacCalendar calendar = id -> "spring".equals(id) ? SeasonState.BETWEEN : SeasonState.liveIn(2026);

        assertEquals(List.of("autumn", "harvest", "spring"),
                AlmanacView.seasons(pages, calendar).stream().map(Season::eventId).toList(),
                "two seasons on now can overlap; each group reads by Order");
    }

    @Test
    void aListedSeasonCarriesThePictureItsPageNames() throws Exception {
        List<Season> seasons = AlmanacView.seasons(Map.of("test_season", seasonPage()), id -> SeasonState.BETWEEN);

        assertEquals("Test_Icon", seasons.get(0).icon(),
                "the page's Icon is the picture the Almanac paints beside the season's name");
    }

    @Test
    void aSeasonIsLiveOnlyWhileTheCalendarHasOneOnAndAnAbsentEventNeverIs() throws Exception {
        Map<String, AlmanacEntryAsset> pages = Map.of(
                "first", AlmanacFixtures.page("{ \"Order\": 10 }", "First"),
                "second", AlmanacFixtures.page("{ \"Order\": 20 }", "Second"));

        assertTrue(AlmanacView.anySeasonLive(pages,
                        id -> "second".equals(id) ? SeasonState.liveIn(2026) : SeasonState.BETWEEN),
                "one season on is enough");
        assertTrue(AlmanacView.anySeasonLive(pages, id -> "second".equals(id) ? SeasonState.liveIn(2026) : null),
                "an absent event beside a live one does not hide it");
        assertFalse(AlmanacView.anySeasonLive(pages, id -> SeasonState.BETWEEN),
                "every season between seasons: none is live");
        assertFalse(AlmanacView.anySeasonLive(pages, id -> "second".equals(id) ? null : SeasonState.BETWEEN),
                "an event the calendar does not answer for is absent, so never live");
        assertFalse(AlmanacView.anySeasonLive(Map.of(), id -> SeasonState.liveIn(2026)),
                "a live event with no page is no season");
    }

    @Test
    void theSeasonOpenedOnIsTheOneAskedForElseTheLiveOneElseTheFirst() {
        Season a = new Season("a", null, null, null, false, 0);
        Season b = new Season("b", null, null, null, true, 2026);

        assertEquals("a", AlmanacView.pick(List.of(a, b), "A").eventId(), "asked for, in any case");
        assertEquals("b", AlmanacView.pick(List.of(a, b), null).eventId(), "else the one that is on");
        assertEquals("b", AlmanacView.pick(List.of(a, b), "nope").eventId(), "an unknown ask falls back the same way");
        assertEquals("a", AlmanacView.pick(List.of(a), null).eventId(), "else the first");
        assertNull(AlmanacView.pick(List.of(), "a"));
    }

    @Test
    void aLiveSeasonShowsItsOwnTalliesAndEverySeasonsInLineOrder() throws Exception {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.lifetime("test_season", "bombs_thrown"), 12L);
        tallies.add(AlmanacKeys.season("test_season", 2026, "bombs_thrown"), 5L);
        tallies.add(AlmanacKeys.season("test_season", 2026, AlmanacKeys.ATTENDED), 1L);
        tallies.add(AlmanacKeys.lifetime("test_season", AlmanacKeys.ATTENDED), 2L);

        Detail detail = AlmanacView.detail(live2026(), seasonPage(), tallies, null, null);

        assertEquals(2026, detail.shownYear());
        assertTrue(detail.attendedShownYear());
        assertEquals(2L, detail.seasonsAttended());
        assertEquals(List.of("bombs_thrown", "ghouls"), detail.lifetimeLines().stream().map(StatLine::statId).toList(),
                "lines read by Order, then name");
        assertEquals(12L, detail.lifetimeLines().get(0).count());
        assertEquals(5L, detail.seasonLines().get(0).count());
        assertEquals(0L, detail.seasonLines().get(1).count(), "a line nothing counted reads 0, not absent");
    }

    @Test
    void betweenSeasonsTheLastSeasonThePlayerHasTalliesForIsShown() throws Exception {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.season("test_season", 2025, "ghouls"), 3L);
        tallies.add(AlmanacKeys.season("test_season", 2026, "ghouls"), 4L);

        Detail detail = AlmanacView.detail(between(), seasonPage(), tallies, null, null);

        assertEquals(2026, detail.shownYear());
        assertFalse(detail.attendedShownYear());
        assertEquals(4L, detail.seasonLines().get(1).count());
    }

    @Test
    void aPlayerWithNoTalliesBetweenSeasonsHasNoSeasonSection() throws Exception {
        Detail detail = AlmanacView.detail(between(), seasonPage(), new CounterMap(), null, null);

        assertNull(detail.shownYear());
        assertTrue(detail.seasonLines().isEmpty());
        assertEquals(2, detail.lifetimeLines().size(), "every season's lines still show, at 0");
    }

    @Test
    void keepsakesAreEveryMintedYearThePlayerEarnedOldestFirst() throws Exception {
        Achievement y2025 = Achievement.builder("test_keepsake_2025").icon("Test_Lantern").build();
        Achievement y2026 = Achievement.builder("test_keepsake_2026").icon("Test_Lantern").build();
        Achievement y2027 = Achievement.builder("test_keepsake_2027").build();
        Achievement notAMint = Achievement.builder("test_keepsake_extra").build();
        AchievementEngine engine = engine(y2025, y2026, y2027, notAMint);
        engine.unlock(ALICE, y2026);
        engine.unlock(ALICE, y2025);
        engine.unlock(ALICE, notAMint);

        Detail detail = AlmanacView.detail(live2026(), seasonPage(), new CounterMap(), engine, ALICE);

        assertEquals(List.of(2025, 2026), detail.keepsakes().stream().map(Keepsake::year).toList());
        assertEquals("Test_Lantern", detail.keepsakes().get(0).icon());
    }

    @Test
    void theSeasonsAchievementsAreCountedAndItsEarnedFeatsListed() throws Exception {
        Achievement earnedOne = Achievement.builder("test_first_bomb").category("Seasons").subcategory("Test_Season").build();
        Achievement openOne = Achievement.builder("test_many_bombs").category("Seasons").subcategory("Test_Season").build();
        Achievement hiddenOne = Achievement.builder("test_secret").category("Seasons").subcategory("Test_Season")
                .hidden(true).build();
        Achievement feat = Achievement.builder("test_feat").category("Seasons").subcategory("Test_Season")
                .featOfStrength(true).build();
        Achievement elsewhere = Achievement.builder("test_other").category("Seasons").subcategory("Other_Season").build();
        AchievementEngine engine = engine(earnedOne, openOne, hiddenOne, feat, elsewhere);
        engine.unlock(ALICE, earnedOne);
        engine.unlock(ALICE, feat);
        engine.unlock(ALICE, elsewhere);

        Detail detail = AlmanacView.detail(live2026(), seasonPage(), new CounterMap(), engine, ALICE);

        assertEquals(1, detail.achievementsEarned());
        assertEquals(2, detail.achievementsListed(), "a hidden one not yet earned is not listed, and a feat is apart");
        assertEquals(List.of("test_feat"), detail.feats().stream().map(Feat::achievementId).toList());
    }

    @Test
    void withNoAchievementSubjectThePageStillHasEverythingElse() throws Exception {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.lifetime("test_season", "ghouls"), 7L);

        Detail detail = AlmanacView.detail(live2026(), seasonPage(), tallies, engine(), null);
        assertTrue(detail.keepsakes().isEmpty());
        assertEquals(0, detail.achievementsListed());
        assertEquals(7L, detail.lifetimeLines().get(1).count());

        Detail noPage = AlmanacView.detail(live2026(), null, tallies, null, null);
        assertTrue(noPage.lifetimeLines().isEmpty(), "a season with no page has no lines");
    }

    @Test
    void theBannerIsAbsentUntilACrossSeasonAchievementIsInCirculation() {
        Achievement hallowed = Achievement.builder("test_hallowed").category("Seasons").subcategory("Test_Season").build();
        Achievement metaOff = Achievement.builder("test_seasons_meta").category("Seasons")
                .metaChildren(List.of("test_hallowed", "test_winter")).available(false).build();
        AchievementEngine off = engine(hallowed, metaOff);
        off.unlock(ALICE, hallowed);
        assertNull(AlmanacView.banner(off, ALICE), "switched off and not earned: absent");

        Achievement metaOn = Achievement.builder("test_seasons_meta").category("Seasons")
                .metaChildren(List.of("test_hallowed", "test_winter")).build();
        AchievementEngine on = engine(hallowed, metaOn);
        on.unlock(ALICE, hallowed);
        Banner banner = AlmanacView.banner(on, ALICE);

        assertNotNull(banner);
        assertEquals("test_seasons_meta", banner.achievementId());
        assertFalse(banner.earned());
        assertEquals(1, banner.childrenEarned());
        assertEquals(2, banner.childrenTotal());
    }

    @Test
    void theBannerClimbsTheLadderRungByRungThenRestsOnTheTop() {
        Achievement keepA = Achievement.builder("keep_a").category("Seasons").subcategory("Season_A").build();
        Achievement keepB = Achievement.builder("keep_b").category("Seasons").subcategory("Season_B").build();
        Achievement keepC = Achievement.builder("keep_c").category("Seasons").subcategory("Season_C").build();
        List<Achievement.MetaGroup> seasons = List.of(
                new Achievement.MetaGroup("season_a", List.of("keep_a"), () -> true),
                new Achievement.MetaGroup("season_b", List.of("keep_b"), () -> true),
                new Achievement.MetaGroup("season_c", List.of("keep_c"), () -> true));
        Achievement two = Achievement.builder("ladder_two").category("Seasons").sortOrder(10)
                .metaGroups(seasons).metaNeeds(2).build();
        Achievement every = Achievement.builder("ladder_every").category("Seasons").sortOrder(20)
                .metaGroups(seasons).build();
        AchievementEngine engine = engine(keepA, keepB, keepC, two, every);

        Banner first = AlmanacView.banner(engine, ALICE);
        assertNotNull(first);
        assertEquals("ladder_two", first.achievementId(),
                "the lowest rung not yet earned, by its order (the id alone would put ladder_every first)");
        assertEquals(0, first.childrenEarned());
        assertEquals(2, first.childrenTotal());

        engine.unlock(ALICE, keepA);
        engine.unlock(ALICE, keepB);
        Banner second = AlmanacView.banner(engine, ALICE);
        assertEquals("ladder_every", second.achievementId(), "an earned rung hands the banner to the next");
        assertEquals(2, second.childrenEarned());
        assertEquals(3, second.childrenTotal(), "seasons, never copies");

        engine.unlock(ALICE, keepC);
        Banner last = AlmanacView.banner(engine, ALICE);
        assertEquals("ladder_every", last.achievementId(), "every rung earned: the banner rests on the highest");
        assertTrue(last.earned());
        assertEquals(3, last.childrenEarned(), "an earned rung reads full");
    }

    @Test
    void theShippedSeasonsCategoryIsTheOneTheViewReads() throws Exception {
        try (InputStream in = AlmanacView.class.getResourceAsStream(
                "/Server/ZiggfreedCommon/AchievementCategories/Seasons.json")) {
            assertNotNull(in, "the module ships the Seasons category file");
        }
        assertEquals("seasons", AlmanacView.SEASONS_CATEGORY, "a category id is its file name, lower-cased");
        assertTrue(AlmanacText.SPOKEN.contains("achievement." + "category." + AlmanacView.SEASONS_CATEGORY),
                "the category's label ships in the Almanac's own lang file");
    }
}
