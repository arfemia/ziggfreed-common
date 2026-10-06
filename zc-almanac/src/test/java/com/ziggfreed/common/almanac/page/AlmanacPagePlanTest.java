package com.ziggfreed.common.almanac.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.almanac.AlmanacText;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.AchievementShelf;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.GlanceMonth;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.HeroBox;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.HeroKind;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.HeroPlan;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.KeepsakeShelf;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.SeasonBody;
import com.ziggfreed.common.almanac.page.AlmanacPagePlan.YearChoice;
import com.ziggfreed.common.almanac.view.AlmanacView.Banner;
import com.ziggfreed.common.almanac.view.AlmanacView.Feat;
import com.ziggfreed.common.almanac.view.AlmanacView.Hero;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroComposition;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroGlow;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroGradient;
import com.ziggfreed.common.almanac.view.AlmanacView.HeroItem;
import com.ziggfreed.common.almanac.view.AlmanacView.MonthMarks;
import com.ziggfreed.common.almanac.view.AlmanacView.Record;
import com.ziggfreed.common.almanac.view.AlmanacView.Scope;
import com.ziggfreed.common.almanac.view.AlmanacView.Season;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonAchievements;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonLink;
import com.ziggfreed.common.almanac.view.AlmanacView.SeasonPage;
import com.ziggfreed.common.almanac.view.AlmanacView.Tally;
import com.ziggfreed.common.almanac.view.AlmanacView.Timing;
import com.ziggfreed.common.almanac.view.AlmanacView.YearChip;
import com.ziggfreed.common.almanac.view.AlmanacView.YearKeepsake;
import com.ziggfreed.common.ui.kit.KeepsakeState;
import com.ziggfreed.common.ui.kit.KeepsakeTile;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.StatTile;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.kit.ZigTokens;

/**
 * What the Almanac page shows for each state a season can be in (on now, between runs and soon, between runs
 * and far off, a first run still ahead, a past year, every season), with no keepsake, nothing filed, no
 * seasons at all, and each of its hero's three forms. The page paints this plan through the kit and decides
 * nothing; these are the decisions it would otherwise hide.
 */
class AlmanacPagePlanTest {

    private static final MonthDay OCT_1 = MonthDay.of(10, 1);
    private static final MonthDay NOV_3 = MonthDay.of(11, 3);
    private static final String EVENT = "hallows_eve";

    // ---- what each state shows ----

    @Test
    void aSeasonOnNowCountsDownOnItsChipAndReadsThisYear() {
        Season live = season(EVENT, true);
        SeasonPage page = page(live, liveTiming(27), List.of(new YearChip(2026, true, true, false)),
                new Scope(2026), true, List.of(tally("bombs_thrown", 5L, 12L, 40L)));
        AlmanacPagePlan plan = plan(List.of(live), page);

        SeasonBody body = plan.body();
        assertNotNull(body);
        HeroPlan hero = body.hero();
        assertKey("chip.live", hero.chip().label());
        assertEquals(27L, number(hero.chip().label(), "0"));
        assertEquals(Tone.LIVE, hero.chip().tone(), "on now: the green dot");
        assertNotNull(hero.dates());
        assertKey("window", hero.dates());

        List<YearChoice> years = body.years();
        assertEquals(2, years.size(), "the year, then Every season");
        YearChoice year = years.get(0);
        assertKey("year", year.label());
        assertEquals("2026", year.label().getFormattedMessage().messageParams.get("0").rawText,
                "a year is a label, never a quantity: no locale groups it");
        assertEquals("2026", year.value());
        assertTrue(year.on() && year.check() && year.dot(), "the year read, taken part in, on now");
        YearChoice every = years.get(1);
        assertKey("scope.every", every.label());
        assertEquals(AlmanacEventData.EVERY, every.value());
        assertFalse(every.on());

        assertKey("scope.year", body.scopeHeader());
        assertKey("scope.took_part", body.scopeMeta());
        assertFalse(body.hint(), "the player took part, so no first-time hint");

        StatTile tile = body.tiles().get(0);
        assertEquals("bombs_thrown", tile.id());
        assertEquals(5L, number(tile.figure(), "0"));
        assertFalse(tile.zero());
        assertNotNull(tile.caption());
        assertKey("tile.in_all", tile.caption());
        assertNotNull(tile.serverLine());
        assertKey("tile.server", tile.serverLine());

        LedgerSection section = plan.seasons().sections().get(0);
        assertEquals(AlmanacPagePlan.LIVE_SECTION, section.id());
        assertKey("status.live", section.label());
        LedgerRow row = section.rows().get(0);
        assertEquals(EVENT, row.id());
        assertEquals(Tone.LIVE, row.tone());
        assertNotNull(row.meta());
        assertKey("chip.live", row.meta());
        assertEquals(EVENT, plan.selected());
    }

    @Test
    void betweenRunsTheChipSaysWhenItReturnsInTheNearToneWithinFourteenDays() {
        Season soon = season(EVENT, false);
        AlmanacPagePlan near = plan(List.of(soon), page(soon, betweenTiming(10), pastYears(), new Scope(2025), true,
                List.of()));
        HeroPlan nearHero = near.body().hero();
        assertKey("chip.returns_in", nearHero.chip().label());
        assertEquals(Tone.AVAILABLE, nearHero.chip().tone(), "returning within 14 days: the near tone");

        AlmanacPagePlan far = plan(List.of(soon), page(soon, betweenTiming(60), pastYears(), new Scope(2025), true,
                List.of()));
        HeroPlan farHero = far.body().hero();
        assertKey("chip.returns_on", farHero.chip().label());
        assertEquals(Tone.BLOCKED, farHero.chip().tone(), "far off: the idle tone");

        LedgerSection section = far.seasons().sections().get(0);
        assertEquals(AlmanacPagePlan.ALL_SECTION, section.id(), "a season not on now lists under All seasons");
        assertKey("seasons.all", section.label());
        assertEquals(1, far.seasons().sections().size(), "no On now head while nothing is on");
    }

    @Test
    void aFirstRunStillAheadStartsWithNoYearChipsEverySeasonAndTheHint() {
        Season ahead = season(EVENT, false);
        Timing startsLater = new Timing(false, null, false, 23, false, OCT_1, NOV_3, LocalDate.of(2026, 10, 1), true);
        SeasonPage page = page(ahead, startsLater, List.of(), Scope.EVERY, false,
                List.of(tally("bombs_thrown", 0L, null, null)));
        SeasonBody body = plan(List.of(ahead), page).body();

        assertKey("chip.starts_in", body.hero().chip().label());
        assertTrue(body.years().isEmpty(), "no year to read yet");
        assertKey("scope.every", body.scopeHeader());
        assertKey("scope.not_yet", body.scopeMeta());
        assertTrue(body.hint(), "your tallies start the first time you play during the season");
        assertTrue(body.tiles().get(0).zero(), "a tile nothing counted reads faint");
        assertNull(body.tiles().get(0).caption());
        assertNull(body.keepsakes(), "no keepsake shelf before a first run");
    }

    @Test
    void aPastYearIsTheChosenChipAndItsKeepsakeShelfLightsTheLiveYear() {
        Season live = season(EVENT, true);
        List<YearChip> years = List.of(new YearChip(2025, false, true, true), new YearChip(2026, true, false, false));
        List<YearKeepsake> keepsakes = List.of(
                new YearKeepsake(2024, KeepsakeState.MISSED, "Keepsake_2024", "Test_Keepsake"),
                new YearKeepsake(2025, KeepsakeState.EARNED, "Keepsake_2025", "Test_Keepsake"),
                new YearKeepsake(2026, KeepsakeState.TO_EARN, "Keepsake_2026", "Test_Keepsake"));
        SeasonPage page = new SeasonPage(live, liveTiming(27), years, new Scope(2025), true, List.of(), keepsakes,
                null, new Hero(null, null, null), null, List.of());
        SeasonBody body = plan(List.of(live), page).body();

        assertTrue(body.years().get(0).on(), "2025 is the year read");
        assertTrue(body.years().get(0).check());
        assertFalse(body.years().get(0).dot());
        assertFalse(body.years().get(1).on());
        assertTrue(body.years().get(1).dot(), "2026 is on now");
        assertEquals("2025", body.scopeHeader().getFormattedMessage().messageParams.get("0").rawText);

        KeepsakeShelf shelf = body.keepsakes();
        assertNotNull(shelf);
        assertKey("keepsakes.meta", shelf.meta());
        assertEquals(1L, number(shelf.meta(), "0"), "one earned");
        assertEquals(3L, number(shelf.meta(), "1"), "of three years");
        List<KeepsakeTile> tiles = shelf.tiles();
        assertEquals(KeepsakeState.MISSED, tiles.get(0).state());
        assertKey("keepsake.missed", tiles.get(0).stateLine());
        assertEquals(KeepsakeState.EARNED, tiles.get(1).state());
        assertEquals(KeepsakeState.TO_EARN, tiles.get(2).state());
        assertKey("keepsake.to_earn", tiles.get(2).stateLine());
        assertTrue(tiles.get(2).highlighted(), "the year on now is the highlighted tile");
        assertFalse(tiles.get(1).highlighted());
        assertKey("year", tiles.get(0).label());
        assertEquals("Test_Keepsake", tiles.get(0).picture().itemId());
    }

    @Test
    void everySeasonCountsTheYearsTakenPartIn() {
        Season live = season(EVENT, true);
        List<YearChip> years = List.of(new YearChip(2025, false, true, false), new YearChip(2026, true, true, false));
        SeasonBody body = plan(List.of(live), page(live, liveTiming(27), years, Scope.EVERY, true,
                List.of(tally("bombs_thrown", 20L, null, null)))).body();

        assertTrue(body.years().get(2).on(), "Every season is the chip read");
        assertFalse(body.years().get(0).on());
        assertKey("scope.every", body.scopeHeader());
        assertKey("scope.taken_part_count", body.scopeMeta());
        assertEquals(2L, number(body.scopeMeta(), "0"));
        assertNull(body.tiles().get(0).caption(), "every season: the figure is the lifetime count, no caption");
    }

    @Test
    void noKeepsakeAndNothingFiledLeaveTheirSectionsOut() {
        Season live = season(EVENT, true);
        SeasonPage none = page(live, liveTiming(27), List.of(new YearChip(2026, true, true, false)), new Scope(2026),
                true, List.of());
        SeasonBody body = plan(List.of(live), none).body();
        assertNull(body.keepsakes(), "a season with no keepsake never says No keepsakes yet");
        assertNull(body.achievements(), "nothing filed: no achievements section");

        SeasonPage emptyShelf = new SeasonPage(live, liveTiming(27), List.of(), Scope.EVERY, false, List.of(),
                List.of(), null, new Hero(null, null, null), null, List.of());
        assertNull(plan(List.of(live), emptyShelf).body().keepsakes(), "an empty shelf is no shelf");
    }

    @Test
    void theAchievementsSectionCountsTheSeasonAndPillsItsFeats() {
        Season live = season(EVENT, true);
        SeasonPage page = new SeasonPage(live, liveTiming(27), List.of(), Scope.EVERY, false, List.of(), null,
                new SeasonAchievements(4, 9, List.of(new Feat("Feat_One", "Test_Icon"))),
                new Hero(null, null, null), null, List.of());
        AchievementShelf shelf = plan(List.of(live), page).body().achievements();

        assertNotNull(shelf);
        assertNotNull(shelf.meta());
        assertKey("achievements.count", shelf.meta());
        assertEquals(4L, number(shelf.meta(), "0"));
        assertEquals(9L, number(shelf.meta(), "1"));
        assertNotNull(shelf.fraction());
        assertEquals(4f / 9f, shelf.fraction(), 1e-6);
        assertEquals(1, shelf.feats().size());
        assertEquals(Tone.DONE, shelf.feats().get(0).tone(), "an earned feat");
    }

    @Test
    void noSeasonsListedShowsTheEmptyStateAcrossTheBody() {
        AlmanacPagePlan plan = AlmanacPagePlan.of(List.of(), Map.of(), null, new Record(0L, 0L), months(Map.of()),
                Map.of(), 10, null, null);

        assertNull(plan.body(), "no season page");
        assertTrue(plan.seasons().isEmpty());
        assertNull(plan.selected());
        assertKey("empty.none.title", plan.empty().title());
        assertNotNull(plan.empty().line());
        assertKey("empty.none.line", plan.empty().line());
        assertEquals(AlmanacPagePlan.EMPTY_PICTURE, plan.empty().picture().itemId());
    }

    @Test
    void theSeasonListPutsOnNowFirstThenAllSeasons() {
        Season live = season(EVENT, true);
        Season other = season("harvest_moon", false);
        AlmanacPagePlan plan = AlmanacPagePlan.of(List.of(live, other),
                Map.of(EVENT, liveTiming(27), "harvest_moon", betweenTiming(23)),
                page(live, liveTiming(27), List.of(), Scope.EVERY, false, List.of()), new Record(2L, 1L),
                months(Map.of()), Map.of(), 10, null, null);

        List<LedgerSection> sections = plan.seasons().sections();
        assertEquals(List.of(AlmanacPagePlan.LIVE_SECTION, AlmanacPagePlan.ALL_SECTION),
                sections.stream().map(LedgerSection::id).toList());
        assertEquals("harvest_moon", sections.get(1).rows().get(0).id());
        assertKey("chip.returns_in", sections.get(1).rows().get(0).meta());
        assertEquals("Test_Icon", sections.get(1).rows().get(0).picture().itemId(), "the season's own picture");

        assertEquals(2L, number(plan.record().seasonsFigure(), "0"));
        assertKey("record.seasons", plan.record().seasonsCaption());
        assertEquals(1L, number(plan.record().keepsakesFigure(), "0"));
        assertKey("record.keepsakes", plan.record().keepsakesCaption());
    }

    @Test
    void theYearAtAGlanceMarksEachSeasonInItsAccentAndKnowsTodaysMonth() {
        Season live = season(EVENT, true);
        Map<Integer, List<String>> marks = Map.of(10, List.of(EVENT), 11, List.of(EVENT));
        AlmanacPagePlan plan = AlmanacPagePlan.of(List.of(live), Map.of(EVENT, liveTiming(27)),
                page(live, liveTiming(27), List.of(), Scope.EVERY, false, List.of()), new Record(0L, 0L),
                months(marks), Map.of(EVENT, "#E8752A"), 10, null, null);

        List<GlanceMonth> months = plan.months();
        assertEquals(12, months.size());
        GlanceMonth october = months.get(9);
        assertEquals(10, october.month());
        assertKey("month.short.10", october.label());
        assertTrue(october.current());
        assertEquals(List.of("#e8752a"), october.markHexes(), "the season's own accent");
        assertEquals(EVENT, october.firstEventId(), "a click on October selects Hallow's Eve");
        assertFalse(months.get(10).current());
        assertTrue(months.get(0).markHexes().isEmpty());
        assertNull(months.get(0).firstEventId(), "an empty month selects nothing");
    }

    @Test
    void theBannerCardCountsItsSeasons() {
        Season live = season(EVENT, true);
        AlmanacPagePlan plan = AlmanacPagePlan.of(List.of(live), Map.of(EVENT, liveTiming(27)),
                page(live, liveTiming(27), List.of(), Scope.EVERY, false, List.of()), new Record(0L, 0L),
                months(Map.of()), Map.of(), 10, new Banner("Seasons_Of_Orbis", false, 2, 4), "Deco_Scroll");

        AlmanacPagePlan.BannerCard banner = plan.banner();
        assertNotNull(banner);
        assertEquals("Deco_Scroll", banner.picture().itemId());
        assertNotNull(banner.meta());
        assertKey("achievements.count", banner.meta());
        assertEquals(2L, number(banner.meta(), "0"));
        assertNotNull(banner.fraction());
        assertEquals(0.5f, banner.fraction(), 1e-6);

        assertNull(plan(List.of(live), page(live, liveTiming(27), List.of(), Scope.EVERY, false, List.of())).banner(),
                "no banner while none is in circulation");
    }

    // ---- the hero's three forms ----

    @Test
    void shippedArtDrawsTheImageAlone() {
        Season live = season(EVENT, true);
        HeroPlan hero = heroOf(live, new Hero("UI/Custom/Almanac/Hallows_Eve.png", null, "Icons/ItemsGenerated/X.png"),
                "#E8752A");

        assertEquals(HeroKind.ART, hero.kind());
        assertEquals("UI/Custom/Almanac/Hallows_Eve.png", hero.artTexture());
        assertTrue(hero.pictures().isEmpty());
        assertNull(hero.pictureTexture(), "the art carries the picture");
        assertNull(hero.glow());
        assertNull(hero.skyHex());
        assertEquals("#e8752a", hero.accentHex(), "the season's accent along the bottom edge");
        assertKey("chip.live", hero.chip().label());
    }

    @Test
    void aCompositionDrawsItsSkyGlowAndPicturesWhereTheJsonPutsThem() {
        Season live = season(EVENT, true);
        HeroComposition composition = new HeroComposition(null, null, new HeroGradient("#0a0f1e", "#2a1a2c"),
                new HeroGlow("#a0501a", 514, -78, 400),
                List.of(new HeroItem("Lantern", "Icons/ItemsGenerated/Lantern.png", 650, 58, 128),
                        new HeroItem("Bomb", "Icons/ItemsGenerated/Bomb.png", 562, 14, 64)));
        HeroPlan hero = heroOf(live, new Hero(null, composition, "Icons/ItemsGenerated/X.png"), null);

        assertEquals(HeroKind.COMPOSED, hero.kind());
        assertNull(hero.artTexture(), "no art layer: the flat fill and the tinted sky show");
        assertEquals("#2a1a2c", hero.fillHex(), "the gradient's bottom is the flat fill");
        assertEquals("#0a0f1e", hero.skyHex(), "its top tints the white sky");
        assertNull(hero.pictureTexture(), "the composition's pictures replace the season's own");
        assertEquals(2, hero.pictures().size());
        assertEquals(new HeroBox(650, 58, 128, 128), hero.pictures().get(0).box(), "placed and sized as authored");
        assertEquals("Icons/ItemsGenerated/Lantern.png", hero.pictures().get(0).iconPath());

        assertNotNull(hero.glow());
        assertEquals("#a0501a", hero.glow().colorHex());
        assertEquals(new HeroBox(514, 4, 400, 236), hero.glow().box(),
                "the client does not clip a child: the largest box about the authored centre on the plate");
        assertEquals(ZigTokens.ACCENT, hero.accentHex(), "no authored accent: the kit's gold");
    }

    @Test
    void aCompositionWithNoGradientFillsItsBackgroundAndShowsItsTexture() {
        Season live = season(EVENT, true);
        HeroComposition flat = new HeroComposition("#121a2e", "UI/Custom/Almanac/Sky.png", null, null,
                List.of(new HeroItem("Bomb", "Icons/ItemsGenerated/Bomb.png", 0, 0, 64)));
        HeroPlan hero = heroOf(live, new Hero(null, flat, null), null);

        assertEquals("#121a2e", hero.fillHex());
        assertEquals("UI/Custom/Almanac/Sky.png", hero.artTexture(), "the composition's texture takes the art layer");
        assertNull(hero.skyHex());
        assertNull(hero.glow());
    }

    @Test
    void withNeitherTheHeroFallsBackToTheSeasonsOwnPicture() {
        Season live = season(EVENT, true);
        HeroPlan hero = heroOf(live, new Hero(null, null, "Icons/ItemsGenerated/Jack.png"), "#101925");

        assertEquals(HeroKind.PICTURE, hero.kind());
        assertEquals("Icons/ItemsGenerated/Jack.png", hero.pictureTexture());
        assertNull(hero.artTexture(), "the plate itself draws behind it");
        assertTrue(hero.pictures().isEmpty());
        assertEquals(ZigTokens.ACCENT, hero.accentHex(), "an accent that does not read on the row falls to gold");

        assertNull(heroOf(live, new Hero(null, null, null), null).pictureTexture(),
                "a season with no picture the server has: the plate alone");
    }

    @Test
    void aGlowIsFittedOntoThePlateAboutItsCentreOrHidden() {
        assertEquals(new HeroBox(514, 4, 400, 236), AlmanacPagePlan.fitGlow(new HeroGlow("#a0501a", 514, -78, 400)),
                "Hallow's Eve: its full width, and as tall as the plate holds about its centre (an ellipse, on purpose)");
        assertEquals(new HeroBox(100, 20, 200, 200), AlmanacPagePlan.fitGlow(new HeroGlow("#a0501a", 100, 20, 200)),
                "a glow already on the plate keeps its box");
        assertEquals(new HeroBox(838, 20, 124, 200), AlmanacPagePlan.fitGlow(new HeroGlow("#a0501a", 800, 20, 200)),
                "near the right edge it narrows about its centre and keeps its height where that fits");
        assertNull(AlmanacPagePlan.fitGlow(new HeroGlow("#a0501a", -480, -480, 240)),
                "a glow whose centre is off the plate draws nothing");
    }

    // ---- fixtures ----

    private static AlmanacPagePlan plan(List<Season> seasons, SeasonPage page) {
        return AlmanacPagePlan.of(seasons, Map.of(page.season().eventId(), page.timing()), page, new Record(0L, 0L),
                months(Map.of()), Map.of(), 10, null, null);
    }

    private static HeroPlan heroOf(Season season, Hero hero, String accent) {
        SeasonPage page = new SeasonPage(season, liveTiming(27), List.of(), Scope.EVERY, false, List.of(), null, null,
                hero, accent, List.<SeasonLink>of());
        return AlmanacPagePlan.hero(page);
    }

    private static SeasonPage page(Season season, Timing timing, List<YearChip> years, Scope scope, boolean tookPart,
            List<Tally> tallies) {
        return new SeasonPage(season, timing, years, scope, tookPart, tallies, null, null, new Hero(null, null, null),
                null, List.of());
    }

    private static Season season(String id, boolean live) {
        return new Season(id, "almanac.test.title", "almanac.test.flavor", "Test_Icon", live, live ? 2026 : 0);
    }

    private static Timing liveTiming(int daysLeft) {
        return new Timing(true, daysLeft, false, null, false, OCT_1, NOV_3, LocalDate.of(2027, 10, 1), false);
    }

    private static Timing betweenTiming(int daysUntil) {
        return new Timing(false, null, false, daysUntil, daysUntil <= 14, OCT_1, NOV_3,
                LocalDate.of(2026, 10, 1).plusDays(daysUntil), false);
    }

    private static List<YearChip> pastYears() {
        return List.of(new YearChip(2025, false, true, false));
    }

    private static Tally tally(String statId, long figure, Long allSeasons, Long server) {
        return new Tally(statId, null, "Test_Bomb", figure, allSeasons, server);
    }

    private static List<MonthMarks> months(Map<Integer, List<String>> marks) {
        List<MonthMarks> out = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            out.add(new MonthMarks(m, marks.getOrDefault(m, List.of())));
        }
        return out;
    }

    private static void assertKey(String key, Message message) {
        assertEquals(AlmanacText.PREFIX + key, message.getFormattedMessage().messageId);
    }

    private static long number(Message message, String param) {
        FormattedMessage formatted = message.getFormattedMessage();
        return ((LongParamValue) formatted.params.get(param)).value;
    }
}
