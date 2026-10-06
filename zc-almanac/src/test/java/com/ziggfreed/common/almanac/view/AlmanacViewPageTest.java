package com.ziggfreed.common.almanac.view;

import static com.ziggfreed.common.almanac.FixedCalendar.TEST_SEASON;
import static com.ziggfreed.common.almanac.FixedCalendar.UTC;
import static com.ziggfreed.common.almanac.FixedCalendar.autumn;
import static com.ziggfreed.common.almanac.FixedCalendar.betweenAfter;
import static com.ziggfreed.common.almanac.FixedCalendar.history;
import static com.ziggfreed.common.almanac.FixedCalendar.liveIn;
import static com.ziggfreed.common.almanac.FixedCalendar.noon;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.almanac.AlmanacCalendar.Dates;
import com.ziggfreed.common.almanac.AlmanacCalendar.SeasonState;
import com.ziggfreed.common.almanac.AlmanacFixtures;
import com.ziggfreed.common.almanac.AlmanacKeys;
import com.ziggfreed.common.almanac.FixedCalendar;
import com.ziggfreed.common.almanac.ServerTallies;
import com.ziggfreed.common.almanac.asset.AlmanacEntryAsset;
import com.ziggfreed.common.almanac.page.AlmanacDestinations;
import com.ziggfreed.common.almanac.view.AlmanacView.Hero;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroGlow;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroGradient;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroItem;
import com.ziggfreed.common.almanac.view.AlmanacView.MonthMarks;
import com.ziggfreed.common.almanac.view.AlmanacView.Scope;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonAchievements;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonLink;
import com.ziggfreed.common.almanac.view.AlmanacView.Tally;
import com.ziggfreed.common.almanac.view.AlmanacView.Timing;
import com.ziggfreed.common.almanac.view.AlmanacView.YearChip;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * A season's page as the redesigned Almanac reads it, decided away from any page: when the season runs
 * and how long is left, the years a player can read, the scope a page opens on, the tiles with their
 * all-seasons and server lines, the hero, the achievements and links, the record card and the year at a
 * glance.
 */
class AlmanacViewPageTest {

    private static final Subject ALICE = new Subject(new UUID(0, 1), "Alice", null);

    @AfterEach
    void reset() {
        Destinations.clearForTests();
    }

    private static Season live(int year) {
        return new Season(TEST_SEASON, "almanac.test.title", null, "Test_Icon", true, year);
    }

    private static Season between() {
        return new Season(TEST_SEASON, "almanac.test.title", null, "Test_Icon", false, 0);
    }

    private static AlmanacEntryAsset seasonPage() throws Exception {
        return AlmanacFixtures.page(AlmanacFixtures.SEASON_PAGE, "Test_Season");
    }

    private static AchievementEngine engine(Achievement... achievements) {
        AchievementEngine engine = AchievementEngine.builder().nativeEvents(false).build();
        engine.setAchievements(List.of(achievements));
        return engine;
    }

    private static ServerTallies server() {
        ServerTallies server = new ServerTallies((task, delayMs) -> task.run());
        server.init(null);
        return server;
    }

    // ---- timing ----

    @Test
    void daysLeftCountTheLastDay() {
        Dates dates = liveIn(2026, 2026);

        Timing early = AlmanacView.timing(live(2026), dates, noon("2026-10-07"));
        assertTrue(early.live());
        assertEquals(28, early.daysLeft(), "October 7 to November 3 is 28 days, both ends in");
        assertFalse(early.lastDay());
        assertEquals(MonthDay.of(10, 1), early.windowStart());
        assertEquals(MonthDay.of(11, 3), early.windowEnd());
        assertEquals(LocalDate.of(2027, 10, 1), early.nextStart());
        assertNull(early.daysUntil(), "no countdown to a return while it runs");
        assertFalse(early.soon());
        assertFalse(early.startsLater());

        assertEquals(2, AlmanacView.timing(live(2026), dates, noon("2026-11-02")).daysLeft());
        Timing last = AlmanacView.timing(live(2026), dates, noon("2026-11-03"));
        assertEquals(1, last.daysLeft());
        assertTrue(last.lastDay());
    }

    @Test
    void daysAreCountedInTheEventsOwnClock() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Occurrence run = FixedCalendar.run(TEST_SEASON, 2026, "2026-10-01", "2026-11-03", newYork);
        Dates dates = new Dates(run, null, List.of(run), 2026, newYork);

        Timing timing = AlmanacView.timing(live(2026), dates, Instant.parse("2026-11-04T01:30:00Z").toEpochMilli());

        assertTrue(timing.lastDay(), "half past one in the morning in UTC is still November 3 in New York");
        assertEquals(1, timing.daysLeft());
    }

    @Test
    void betweenSeasonsTheNextRunIsCountedDownAndSoonWithinFourteenDays() {
        Dates dates = betweenAfter(2026, 2026);

        Timing timing = AlmanacView.timing(between(), dates, noon("2027-09-08"));
        assertFalse(timing.live());
        assertNull(timing.daysLeft());
        assertEquals(23, timing.daysUntil());
        assertFalse(timing.soon());
        assertEquals(LocalDate.of(2027, 10, 1), timing.nextStart());
        assertEquals(MonthDay.of(10, 1), timing.windowStart());
        assertEquals(MonthDay.of(11, 3), timing.windowEnd());

        assertTrue(AlmanacView.timing(between(), dates, noon("2027-09-17")).soon(), "14 days out is soon");
        assertFalse(AlmanacView.timing(between(), dates, noon("2027-09-16")).soon(), "15 days out is not");
    }

    @Test
    void aFirstRunStillAheadStartsLater() {
        Dates dates = new Dates(null, autumn(2026), List.of(), 2026, UTC);

        Timing timing = AlmanacView.timing(between(), dates, noon("2026-09-08"));

        assertTrue(timing.startsLater());
        assertEquals(23, timing.daysUntil());
    }

    @Test
    void aRunForcedOnOutsideItsDatesIsLiveWithNoCountdown() {
        Timing timing = AlmanacView.timing(live(2026), liveIn(2026, 2026), noon("2026-06-01"));

        assertTrue(timing.live());
        assertNull(timing.daysLeft(), "a forced run has no last day to count to");
        assertFalse(timing.lastDay());
        assertEquals(MonthDay.of(10, 1), timing.windowStart(), "it keeps its window's dates");
    }

    @Test
    void aSeasonWithNoNextRunHasNoCountdownButKeepsItsWindow() {
        Dates dates = new Dates(null, null, history(2026, 2026), 2026, UTC);

        Timing timing = AlmanacView.timing(between(), dates, noon("2026-11-20"));

        assertNull(timing.daysUntil());
        assertNull(timing.nextStart());
        assertEquals(MonthDay.of(10, 1), timing.windowStart(), "the last run still says when it runs");
    }

    @Test
    void aCalendarThatKnowsNoDatesStillSaysWhetherTheSeasonIsOn() {
        Timing timing = AlmanacView.timing(live(2026), id -> SeasonState.liveIn(2026), noon("2026-10-07"));

        assertTrue(timing.live());
        assertNull(timing.daysLeft());
        assertNull(timing.windowStart());
        assertNull(timing.nextStart());
    }

    @Test
    void aWindowCrossingTheNewYearCountsItsOwnDays() {
        Occurrence run = FixedCalendar.run("winter", 2026, "2026-12-20", "2027-01-05", UTC);
        Dates dates = new Dates(run, null, List.of(run), 2026, UTC);
        Season winter = new Season("winter", null, null, null, true, 2026);

        Timing timing = AlmanacView.timing(winter, dates, noon("2027-01-03"));

        assertEquals(3, timing.daysLeft());
        assertEquals(MonthDay.of(12, 20), timing.windowStart());
        assertEquals(MonthDay.of(1, 5), timing.windowEnd());
    }

    // ---- years and scope ----

    @Test
    void theYearListUnitesTheCalendarsRunsAndThePlayersTallies() {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.season(TEST_SEASON, 2025, "ghouls"), 3L);
        Dates dates = liveIn(2027, 2026);
        Timing timing = AlmanacView.timing(live(2027), dates, noon("2027-10-07"));

        List<YearChip> years = AlmanacView.years(live(2027), timing, dates, tallies, Set.of(2026));

        assertEquals(List.of(2025, 2026, 2027), years.stream().map(YearChip::year).toList(), "oldest first");
        assertTrue(years.get(0).tookPart(), "a year the player has tallies for is a year they took part");
        assertFalse(years.get(1).tookPart(), "a year that ran without the player shows no check");
        assertTrue(years.get(1).keepsakeEarned());
        assertTrue(years.get(2).live());
        assertFalse(years.get(0).live());
    }

    @Test
    void attendanceAloneMarksAYearTakenPart() {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.season(TEST_SEASON, 2026, AlmanacKeys.ATTENDED), 1L);
        Dates dates = liveIn(2026, 2026);
        Timing timing = AlmanacView.timing(live(2026), dates, noon("2026-10-07"));

        assertTrue(AlmanacView.years(live(2026), timing, dates, tallies, Set.of()).get(0).tookPart());
    }

    @Test
    void aFirstRunStillAheadHasNoYearsAndOpensOnEverySeason() {
        Dates dates = new Dates(null, autumn(2026), List.of(), 2026, UTC);
        Timing timing = AlmanacView.timing(between(), dates, noon("2026-09-08"));

        List<YearChip> years = AlmanacView.years(between(), timing, dates, new CounterMap(), Set.of());

        assertTrue(years.isEmpty());
        assertEquals(Scope.EVERY, AlmanacView.scope(null, between(), timing, years));
        assertEquals(Scope.EVERY, AlmanacView.scope(new Scope(2026), between(), timing, years),
                "a year that has not run cannot be chosen");
    }

    @Test
    void theDefaultScopeIsTheLiveYearElseTheLastYearTakenPartElseEverySeason() {
        Dates liveDates = liveIn(2027, 2026);
        Timing liveTiming = AlmanacView.timing(live(2027), liveDates, noon("2027-10-07"));
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.season(TEST_SEASON, 2026, "ghouls"), 3L);
        List<YearChip> liveYears = AlmanacView.years(live(2027), liveTiming, liveDates, tallies, Set.of());
        assertEquals(new Scope(2027), AlmanacView.scope(null, live(2027), liveTiming, liveYears));

        Dates betweenDates = betweenAfter(2027, 2026);
        Timing betweenTiming = AlmanacView.timing(between(), betweenDates, noon("2028-01-10"));
        List<YearChip> betweenYears = AlmanacView.years(between(), betweenTiming, betweenDates, tallies, Set.of());
        assertEquals(new Scope(2026), AlmanacView.scope(null, between(), betweenTiming, betweenYears),
                "between seasons: the last year the player took part in, not the last year that ran");

        List<YearChip> neverYears = AlmanacView.years(between(), betweenTiming, betweenDates, new CounterMap(), Set.of());
        assertEquals(Scope.EVERY, AlmanacView.scope(null, between(), betweenTiming, neverYears));
    }

    @Test
    void aRequestedScopeIsHonouredOnlyWhenItIsListed() {
        Dates dates = liveIn(2027, 2026);
        Timing timing = AlmanacView.timing(live(2027), dates, noon("2027-10-07"));
        List<YearChip> years = AlmanacView.years(live(2027), timing, dates, new CounterMap(), Set.of());

        assertEquals(new Scope(2026), AlmanacView.scope(new Scope(2026), live(2027), timing, years));
        assertEquals(Scope.EVERY, AlmanacView.scope(Scope.EVERY, live(2027), timing, years));
        assertEquals(new Scope(2027), AlmanacView.scope(new Scope(1999), live(2027), timing, years),
                "a year that never ran falls back to the default");
    }

    @Test
    void tookPartReadsAnyTallyInTheScope() {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.season(TEST_SEASON, 2026, "ghouls"), 1L);
        tallies.add(AlmanacKeys.lifetime(TEST_SEASON, "ghouls"), 1L);
        Dates dates = liveIn(2027, 2026);
        Timing timing = AlmanacView.timing(live(2027), dates, noon("2027-10-07"));
        List<YearChip> years = AlmanacView.years(live(2027), timing, dates, tallies, Set.of());

        assertTrue(AlmanacView.tookPartIn(new Scope(2026), years));
        assertFalse(AlmanacView.tookPartIn(new Scope(2027), years));
        assertTrue(AlmanacView.tookPartIn(Scope.EVERY, years));
        assertFalse(AlmanacView.tookPartIn(Scope.EVERY, List.of()));
    }

    // ---- tallies ----

    @Test
    void aYearsTilesReadThatYearEverySeasonAndTheServer() throws Exception {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.lifetime(TEST_SEASON, "bombs_thrown"), 12L);
        tallies.add(AlmanacKeys.season(TEST_SEASON, 2026, "bombs_thrown"), 5L);
        ServerTallies server = server();
        server.add(AlmanacKeys.lifetime(TEST_SEASON, "bombs_thrown"), 1204L);
        server.add(AlmanacKeys.season(TEST_SEASON, 2026, "bombs_thrown"), 300L);

        List<Tally> tiles = AlmanacView.tallies(seasonPage(), TEST_SEASON, tallies, new Scope(2026), server);

        assertEquals(List.of("bombs_thrown", "ghouls"), tiles.stream().map(Tally::statId).toList(), "by Order, then name");
        Tally bombs = tiles.get(0);
        assertEquals(5L, bombs.figure());
        assertEquals(12L, bombs.allSeasons());
        assertEquals(300L, bombs.server(), "the server line reads the same season as the figure");
        assertEquals("almanac.test.bombs", bombs.textKey());
        assertEquals("Test_Bomb", bombs.icon());
        Tally ghouls = tiles.get(1);
        assertEquals(0L, ghouls.figure(), "a tally nothing counted reads 0, never absent");
        assertEquals(0L, ghouls.allSeasons());
        assertNull(ghouls.server(), "no server line until someone on the server counts one");
    }

    @Test
    void everySeasonsTilesReadTheLifetimeWithNoCaption() throws Exception {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.lifetime(TEST_SEASON, "bombs_thrown"), 12L);
        ServerTallies server = server();
        server.add(AlmanacKeys.lifetime(TEST_SEASON, "bombs_thrown"), 1204L);

        Tally bombs = AlmanacView.tallies(seasonPage(), TEST_SEASON, tallies, Scope.EVERY, server).get(0);

        assertEquals(12L, bombs.figure());
        assertNull(bombs.allSeasons(), "the figure already is every season");
        assertEquals(1204L, bombs.server());
        assertTrue(AlmanacView.tallies(null, TEST_SEASON, tallies, Scope.EVERY, server).isEmpty(),
                "a season with no page has no tiles");
    }

    // ---- achievements and links ----

    @Test
    void theAchievementsSectionIsAbsentWithNoSubjectOrNothingFiled() {
        Achievement filed = Achievement.builder("test_first").category("Seasons").subcategory("Test_Season").build();

        assertNull(AlmanacView.achievements(TEST_SEASON, null, engine(filed), null), "no subject loaded");
        assertNull(AlmanacView.achievements(TEST_SEASON, null, null, ALICE));
        assertNull(AlmanacView.achievements(TEST_SEASON, null, engine(
                Achievement.builder("test_other").category("Seasons").subcategory("Other").build()), ALICE),
                "nothing filed under this season");
    }

    @Test
    void theAchievementsSectionCountsTheListedAndShowsTheEarnedFeats() {
        Achievement earned = Achievement.builder("test_first").category("Seasons").subcategory("Test_Season").build();
        Achievement open = Achievement.builder("test_many").category("Seasons").subcategory("Test_Season").build();
        Achievement feat = Achievement.builder("test_feat").category("Seasons").subcategory("Test_Season")
                .featOfStrength(true).icon("Test_Trophy").build();
        AchievementEngine engine = engine(earned, open, feat);
        engine.unlock(ALICE, earned);
        engine.unlock(ALICE, feat);

        SeasonAchievements section = AlmanacView.achievements(TEST_SEASON, null, engine, ALICE);

        assertNotNull(section);
        assertEquals(1, section.earned());
        assertEquals(2, section.total());
        assertEquals(List.of("test_feat"), section.feats().stream().map(AlmanacView.Feat::achievementId).toList());
        assertEquals("Test_Trophy", section.feats().get(0).icon());
    }

    @Test
    void aSeasonsLinksCarryTheirWordsAndWhereTheyGo() throws Exception {
        AlmanacDestinations.register();
        AlmanacEntryAsset page = AlmanacFixtures.page("""
                { "Links": [ { "TextKey": "almanac.test.link", "Destination": { "Type": "Almanac", "Event": "Other" } } ] }
                """, "Test_Season");

        List<SeasonLink> links = AlmanacView.links(page);

        assertEquals(1, links.size());
        assertEquals("almanac.test.link", links.get(0).textKey());
        assertEquals("Other", ((AlmanacDestinations.Almanac) links.get(0).destination()).getEvent());
        assertTrue(AlmanacView.links(null).isEmpty());
    }

    // ---- keepsakes ----

    @Test
    void keepsakeCopiesComeFromTheirOccurrenceWithTheMintedIdAsTheFallback() throws Exception {
        Achievement fromOccurrence = Achievement.builder("lantern_of_twenty_seven")
                .occurrence(new Achievement.Occurrence(TEST_SEASON, 2027, "Test_Keepsake")).build();
        Achievement fromId = Achievement.builder("test_keepsake_2026").build();
        Achievement otherBase = Achievement.builder("other_2025")
                .occurrence(new Achievement.Occurrence(TEST_SEASON, 2025, "other")).build();

        Map<Integer, Achievement> copies = AlmanacView.keepsakeCopies(seasonPage(),
                engine(fromOccurrence, fromId, otherBase));

        assertEquals(Set.of(2026, 2027), copies.keySet());
        assertEquals("lantern_of_twenty_seven", copies.get(2027).id());
        assertTrue(AlmanacView.keepsakeCopies(AlmanacFixtures.page("{}", "Bare"), engine(fromId)).isEmpty(),
                "a season with no Keepsake has no copies");
    }

    // ---- hero ----

    private static final Function<String, String> ICONS = Map.of(
            "Test_Icon", "Icons/ItemsGenerated/Test_Icon.png",
            "Test_Lantern", "Icons/ItemsGenerated/Test_Lantern.png",
            "Test_Pumpkin", "Icons/ItemsGenerated/Test_Pumpkin.png",
            "Test_Bomb", "Icons/ItemsGenerated/Test_Bomb.png")::get;

    private static final Predicate<String> SHIPPED = path -> path.startsWith("UI/Custom/");

    private static AlmanacEntryAsset dressed(String hero) throws Exception {
        return AlmanacFixtures.page("{ \"Icon\": \"Test_Icon\", \"Accent\": \"#E8752A\", \"Hero\": " + hero + " }",
                "Test_Season");
    }

    @Test
    void theHeroIsTheArtWhenItIsShownAndShips() throws Exception {
        Hero hero = AlmanacView.hero(live(2026), dressed("""
                { "Art": "UI/Custom/Almanac/Test.png",
                  "Composition": { "Items": [ { "Item": "Test_Lantern" } ] } }
                """), SHIPPED, ICONS);

        assertEquals("UI/Custom/Almanac/Test.png", hero.art());
        assertNull(hero.composition(), "one hero at a time: the image wins");
    }

    @Test
    void showArtFalseBeatsAPresentArt() throws Exception {
        Hero hero = AlmanacView.hero(live(2026), dressed("""
                { "Art": "UI/Custom/Almanac/Test.png", "ShowArt": false,
                  "Composition": { "Items": [ { "Item": "Test_Lantern", "X": 600, "Y": 40, "Size": 96 } ] } }
                """), SHIPPED, ICONS);

        assertNull(hero.art());
        assertNotNull(hero.composition());
        assertEquals(List.of(new HeroItem("Test_Lantern", "Icons/ItemsGenerated/Test_Lantern.png", 600, 40, 96)),
                hero.composition().items());
    }

    @Test
    void anArtThatDoesNotShipFallsToTheComposition() throws Exception {
        Hero hero = AlmanacView.hero(live(2026), dressed("""
                { "Art": "Elsewhere/Missing.png", "Composition": { "Items": [ { "Item": "Test_Pumpkin" } ] } }
                """), SHIPPED, ICONS);

        assertNull(hero.art());
        assertNotNull(hero.composition());
    }

    @Test
    void aCompositionClampsItsItemsOntoThePlateAndSkipsUnknownOnes() throws Exception {
        Hero hero = AlmanacView.hero(live(2026), dressed("""
                { "Composition": { "Items": [
                    { "Item": "Test_Lantern", "X": 900, "Y": 200, "Size": 200 },
                    { "Item": "No_Such_Item", "X": 10, "Y": 10 },
                    { "Item": "Test_Pumpkin", "X": -5, "Y": -9, "Size": 10 },
                    { "Item": "Test_Bomb" } ] } }
                """), SHIPPED, ICONS);

        List<HeroItem> items = hero.composition().items();
        assertEquals(List.of("Test_Lantern", "Test_Pumpkin", "Test_Bomb"), items.stream().map(HeroItem::itemId).toList(),
                "an unknown item is skipped, never drawn as the unknown picture");
        assertEquals(new HeroItem("Test_Lantern", "Icons/ItemsGenerated/Test_Lantern.png",
                AlmanacView.HERO_WIDTH - 128, AlmanacView.HERO_HEIGHT - 128, 128), items.get(0),
                "the size clamps to 128, then the picture is kept wholly on the plate");
        assertEquals(new HeroItem("Test_Pumpkin", "Icons/ItemsGenerated/Test_Pumpkin.png", 0, 0, 24), items.get(1));
        assertEquals(new HeroItem("Test_Bomb", "Icons/ItemsGenerated/Test_Bomb.png", 0, 0, 64), items.get(2),
                "unplaced: the top-left corner at an item's own size");
    }

    @Test
    void aCompositionDrawsAtMostTwelveItems() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            items.append(i == 0 ? "" : ", ").append("{ \"Item\": \"Test_Bomb\", \"X\": ").append(i * 10).append(" }");
        }
        Hero hero = AlmanacView.hero(live(2026), dressed("{ \"Composition\": { \"Items\": [ " + items + " ] } }"),
                SHIPPED, ICONS);

        assertEquals(AlmanacView.HERO_MAX_ITEMS, hero.composition().items().size());
        assertEquals(110, hero.composition().items().get(11).x(), "the first twelve, in the order written");
    }

    @Test
    void aCompositionWithNothingDrawableFallsBackToTheSeasonsOwnIcon() throws Exception {
        Hero hero = AlmanacView.hero(live(2026), dressed("""
                { "Composition": { "Background": "#101010", "Items": [ { "Item": "No_Such_Item" } ] } }
                """), SHIPPED, ICONS);

        assertNull(hero.art());
        assertNull(hero.composition());
        assertEquals("Icons/ItemsGenerated/Test_Icon.png", hero.iconPath());
    }

    @Test
    void theCompositionsBackgroundIsItsOwnElseTheAccentDarkened() throws Exception {
        Hero own = AlmanacView.hero(live(2026), dressed("""
                { "Composition": { "Background": "#101010", "BackgroundTexture": "UI/Custom/Almanac/Fade.png",
                                   "Items": [ { "Item": "Test_Bomb" } ] } }
                """), SHIPPED, ICONS);
        assertEquals("#101010", own.composition().backgroundHex());
        assertEquals("UI/Custom/Almanac/Fade.png", own.composition().backgroundTexture());

        Hero derived = AlmanacView.hero(live(2026), dressed("""
                { "Composition": { "BackgroundTexture": "Elsewhere/Fade.png", "Items": [ { "Item": "Test_Bomb" } ] } }
                """), SHIPPED, ICONS);
        assertEquals("#51290f", derived.composition().backgroundHex(), "#e8752a at 35 percent");
        assertNull(derived.composition().backgroundTexture(), "a texture that does not ship is left out");

        Hero plain = AlmanacView.hero(live(2026), AlmanacFixtures.page(
                "{ \"Hero\": { \"Composition\": { \"Items\": [ { \"Item\": \"Test_Bomb\" } ] } } }", "Test_Season"),
                SHIPPED, ICONS);
        assertNull(plain.composition().backgroundHex(), "no colour and no accent: the page's own surface");
    }

    @Test
    void aGradientSupersedesTheBackgroundAndAMalformedOneIsIgnored() throws Exception {
        Hero sky = AlmanacView.hero(live(2026), dressed("""
                { "Composition": { "Background": "#101010", "Gradient": { "Top": "#1A2A4A", "Bottom": "#0A1119" },
                                   "Items": [ { "Item": "Test_Bomb" } ] } }
                """), SHIPPED, ICONS);
        assertEquals(new HeroGradient("#1a2a4a", "#0a1119"), sky.composition().gradient(),
                "Top is the plate's top edge, Bottom its bottom edge");
        assertNull(sky.composition().backgroundHex(), "the gradient supersedes the flat colour");

        Hero broken = AlmanacView.hero(live(2026), dressed("""
                { "Composition": { "Background": "#101010", "Gradient": { "Top": "dusk", "Bottom": "#0A1119" },
                                   "Items": [ { "Item": "Test_Bomb" } ] } }
                """), SHIPPED, ICONS);
        assertNull(broken.composition().gradient(), "a gradient with a colour that is not one is not drawn");
        assertEquals("#101010", broken.composition().backgroundHex(), "and the flat colour stands");
    }

    @Test
    void aGlowKeepsToItsBoundsButMayHangPastThePlate() throws Exception {
        HeroGlow lantern = glow("{ \"Color\": \"#A0501A\", \"X\": 514, \"Y\": -78, \"Size\": 400 }");
        assertEquals(new HeroGlow("#a0501a", 514, -78, 400), lantern,
                "centred on a lantern, its box hangs off the top: the page clips it, the view does not");

        assertEquals(new HeroGlow("#a0501a", -480, 720, 480),
                glow("{ \"Color\": \"#A0501A\", \"X\": -2000, \"Y\": 5000, \"Size\": 9000 }"));
        assertEquals(new HeroGlow("#a0501a", 1442, -480, 32),
                glow("{ \"Color\": \"#A0501A\", \"X\": 3000, \"Y\": -900, \"Size\": 4 }"));
        assertEquals(new HeroGlow("#a0501a", 0, 0, AlmanacView.GLOW_SIZE), glow("{ \"Color\": \"#A0501A\" }"));
        assertNull(glow("{ \"Color\": \"amber\", \"X\": 10 }"), "a glow with no usable colour is not drawn");
    }

    private static HeroGlow glow(String glow) throws Exception {
        Hero hero = AlmanacView.hero(live(2026), dressed("{ \"Composition\": { \"Glow\": " + glow
                + ", \"Items\": [ { \"Item\": \"Test_Bomb\" } ] } }"), SHIPPED, ICONS);
        return hero.composition().glow();
    }

    @Test
    void withNoHeroAuthoredTheSeasonsOwnIconStands() throws Exception {
        Hero hero = AlmanacView.hero(live(2026), seasonPage(), SHIPPED, ICONS);

        assertNull(hero.art());
        assertNull(hero.composition());
        assertEquals("Icons/ItemsGenerated/Test_Icon.png", hero.iconPath());

        Season unknownIcon = new Season(TEST_SEASON, null, null, "No_Such_Item", true, 2026);
        assertNull(AlmanacView.hero(unknownIcon, null, SHIPPED, ICONS).iconPath(),
                "an unknown picture is no picture: the plate stands alone");
    }

    // ---- the record card and the year at a glance ----

    @Test
    void theRecordCardCountsSeasonsTakenPartAndKeepsakesAcrossEverySeason() throws Exception {
        CounterMap tallies = new CounterMap();
        tallies.add(AlmanacKeys.lifetime(TEST_SEASON, AlmanacKeys.ATTENDED), 2L);
        tallies.add(AlmanacKeys.lifetime("winter", AlmanacKeys.ATTENDED), 1L);
        tallies.add(AlmanacKeys.lifetime("gone", AlmanacKeys.ATTENDED), 5L);
        Achievement y2025 = Achievement.builder("test_keepsake_2025").build();
        Achievement y2026 = Achievement.builder("test_keepsake_2026").build();
        AchievementEngine engine = engine(y2025, y2026);
        engine.unlock(ALICE, y2026);
        List<Season> seasons = List.of(live(2026), new Season("winter", null, null, null, false, 0));
        Map<String, AlmanacEntryAsset> pages = Map.of(TEST_SEASON, seasonPage(),
                "winter", AlmanacFixtures.page("{}", "Winter"));

        AlmanacView.Record record = AlmanacView.record(seasons, pages, tallies, engine, ALICE);

        assertEquals(3L, record.seasonsTakenPart(), "only listed seasons count");
        assertEquals(1L, record.keepsakes());
        assertEquals(0L, AlmanacView.record(seasons, pages, tallies, null, null).keepsakes(),
                "no subject: no keepsakes to count");
    }

    @Test
    void theYearAtAGlanceMarksEveryMonthASeasonRunsIncludingAcrossTheNewYear() {
        Occurrence winter = FixedCalendar.run("winter", 2026, "2026-12-20", "2027-01-05", UTC);
        FixedCalendar calendar = new FixedCalendar()
                .season(TEST_SEASON, liveIn(2026, 2026))
                .season("winter", new Dates(null, winter, List.of(), 2026, UTC));
        List<Season> seasons = List.of(live(2026), new Season("winter", null, null, null, false, 0),
                new Season("undated", null, null, null, false, 0));

        List<MonthMarks> glance = AlmanacView.yearAtAGlance(seasons, calendar, noon("2026-10-07"));

        assertEquals(12, glance.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), glance.stream().map(MonthMarks::month).toList());
        assertEquals(List.of(TEST_SEASON), glance.get(9).eventIds(), "October");
        assertEquals(List.of(TEST_SEASON), glance.get(10).eventIds(), "November");
        assertEquals(List.of("winter"), glance.get(11).eventIds(), "December");
        assertEquals(List.of("winter"), glance.get(0).eventIds(), "January, across the new year");
        assertTrue(glance.get(5).eventIds().isEmpty());
    }
}
