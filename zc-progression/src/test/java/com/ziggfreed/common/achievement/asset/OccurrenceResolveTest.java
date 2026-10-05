package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The fold's half of a yearly achievement: which years exist, that an event switched off keeps its
 * copies, that the file itself never reaches a pool, what an id clash, an unknown event and a runaway
 * first year do, and that an explicit child of the same event is read as the same year's copy.
 */
class OccurrenceResolveTest {

    private static final String EVENT = "yourmod_festival";
    private static final String ONE_STEP =
            "\"Criteria\": { \"one\": { \"Kind\": \"BREAK_BLOCK\", \"Amount\": 1 } }";

    private static AchievementAsset keeper() throws IOException {
        return AchievementAssetCodecTest.decodeRoot(
                "{ \"Occurrence\": { \"Event\": \"YourMod_Festival\" }, " + ONE_STEP + " }", "festival_keeper");
    }

    private static AchievementAsset plain(@Nonnull String id) throws IOException {
        return AchievementAssetCodecTest.decodeRoot("{ " + ONE_STEP + " }", id);
    }

    private static AchievementAssetStore.Resolution resolve(@Nonnull FakeCalendar calendar,
            @Nonnull AchievementAsset... assets) {
        Map<String, AchievementAsset> layer = new LinkedHashMap<>();
        for (AchievementAsset asset : assets) {
            layer.put(asset.getId(), asset);
        }
        return AchievementAssetStore.resolve(layer, List.of(), calendar.reader());
    }

    private static Finding finding(@Nonnull AchievementAssetStore.Resolution resolution, @Nonnull String code) {
        for (Finding finding : resolution.issues()) {
            if (finding.code().equals(code)) {
                return finding;
            }
        }
        return null;
    }

    @Test
    void oneCopyIsMintedPerYearFromTheFirstYearThroughNextYear() throws Exception {
        AchievementPool pool = resolve(new FakeCalendar().event(EVENT, 2024, 2026), keeper()).pool();

        assertEquals(List.of("festival_keeper_2024", "festival_keeper_2025", "festival_keeper_2026",
                "festival_keeper_2027"), pool.ids());
        assertNull(pool.definition("festival_keeper"), "the file itself is never something to earn");
    }

    @Test
    void thePreMintedNextYearOpensOnItsOwnWhenItsOccurrenceStarts() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event(EVENT, 2026, 2026);
        Achievement next = resolve(calendar, keeper()).pool().definition("festival_keeper_2027").achievement();
        assertFalse(next.available(), "next year's copy waits, out of circulation");

        calendar.live(EVENT, 2027);
        assertTrue(next.available(), "and opens with its occurrence, with no fold in between");
        assertFalse(next.featOfStrength());
    }

    @Test
    void aSwitchedOffEventStillMintsItsCopiesSoWhatWasEarnedStaysListed() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event(EVENT, 2025, 2026).live(EVENT, 2026).switchedOff(EVENT);
        AchievementPool pool = resolve(calendar, keeper()).pool();

        assertEquals(List.of("festival_keeper_2025", "festival_keeper_2026", "festival_keeper_2027"),
                pool.ids(), "the years look past the owner's switch, so every copy a player may have earned"
                        + " still exists");
        Achievement current = pool.definition("festival_keeper_2026").achievement();
        assertFalse(current.available(), "switched off is out of circulation: nothing counts toward it");
        assertTrue(current.featOfStrength(), "and a feat for whoever earned it");
    }

    @Test
    void anEventNoCalendarKnowsMintsNothingAndSaysSo() throws Exception {
        AchievementAssetStore.Resolution resolution = resolve(new FakeCalendar(), keeper());

        assertEquals(0, resolution.pool().size());
        Finding unknown = finding(resolution, "OCCURRENCE_UNKNOWN_EVENT");
        assertNotNull(unknown);
        assertEquals(Severity.WARNING, unknown.severity(), "the pack shipping the event may be absent");
    }

    @Test
    void anOccurrenceNamingNoEventMintsNothing() throws Exception {
        AchievementAsset blank = AchievementAssetCodecTest.decodeRoot(
                "{ \"Occurrence\": { \"Event\": \"  \" }, " + ONE_STEP + " }", "festival_blank");
        AchievementAssetStore.Resolution resolution = resolve(new FakeCalendar().event(EVENT, 2026, 2026), blank);

        assertEquals(0, resolution.pool().size());
        assertEquals(Severity.ERROR, finding(resolution, "OCCURRENCE_WITHOUT_EVENT").severity());
    }

    @Test
    void aCopyWhoseIdAnAuthoredFileAlreadyUsesYieldsToTheFile() throws Exception {
        AchievementAsset file = AchievementAssetCodecTest.decodeRoot(
                "{ \"Criteria\": { \"one\": { \"Kind\": \"BREAK_BLOCK\", \"Amount\": 9 } } }",
                "festival_keeper_2026");
        AchievementAssetStore.Resolution resolution =
                resolve(new FakeCalendar().event(EVENT, 2026, 2026), keeper(), file);

        AchievementDefinition clash = resolution.pool().definition("festival_keeper_2026");
        assertEquals(9L, clash.achievement().criteria().get(0).amount(), "the authored file stands");
        assertNull(clash.achievement().occurrence());
        assertEquals(Severity.ERROR, finding(resolution, "MINTED_ID_CLASH").severity());
        assertNotNull(resolution.pool().definition("festival_keeper_2027"), "the other years still mint");
    }

    @Test
    void aFirstYearFarInThePastMintsOnlyTheLastHundredYears() throws Exception {
        AchievementAssetStore.Resolution resolution = resolve(new FakeCalendar().event(EVENT, 26, 2026), keeper());

        assertEquals(OccurrenceMinting.MAX_YEARS, resolution.pool().size());
        assertNotNull(resolution.pool().definition("festival_keeper_2027"));
        assertNotNull(resolution.pool().definition("festival_keeper_1928"));
        assertNull(resolution.pool().definition("festival_keeper_1927"));
        assertNotNull(finding(resolution, "OCCURRENCE_SPAN_CLAMPED"));
    }

    @Test
    void aCalendarThatThrowsAtTheFoldMintsNothingAndSaysSo() throws Exception {
        FakeCalendar broken = new FakeCalendar().event(EVENT, 2026, 2026).throwingAtFold();
        AchievementAssetStore.Resolution resolution = resolve(broken, keeper());

        assertEquals(0, resolution.pool().size());
        assertNotNull(finding(resolution, "OCCURRENCE_CALENDAR_FAILED"));
    }

    @Test
    void anEventSkeletonMintsNothingAndEachChildMintsItsOwnYears() throws Exception {
        AchievementAsset base = AchievementAssetCodecTest.decodeRoot(
                "{ \"Abstract\": true, \"Occurrence\": { \"Event\": \"yourmod_festival\" } }", "festival_base");
        AchievementAsset child = AchievementAssetCodecTest.decode("{ " + ONE_STEP + " }",
                "festival_carver", "festival_base", base);

        AchievementPool pool = resolve(new FakeCalendar().event(EVENT, 2026, 2026), base, child).pool();
        assertEquals(List.of("festival_carver_2026", "festival_carver_2027"), pool.ids());
    }

    @Test
    void anExplicitChildOfTheSameEventIsReadAsTheSameYearsCopy() throws Exception {
        AchievementAsset capstone = AchievementAssetCodecTest.decodeRoot("""
                { "Occurrence": { "Event": "yourmod_festival" },
                  "MetaChildren": [ "festival_keeper", "plain_child" ] }
                """, "festival_capstone");
        AchievementPool pool = resolve(new FakeCalendar().event(EVENT, 2026, 2026),
                keeper(), capstone, plain("plain_child")).pool();

        assertEquals(List.of("festival_keeper_2026", "plain_child"),
                pool.definition("festival_capstone_2026").achievement().metaChildren());
        assertEquals(List.of("festival_keeper_2027", "plain_child"),
                pool.definition("festival_capstone_2027").achievement().metaChildren());
    }

    @Test
    void anOrdinaryFileFoldsExactlyAsBefore() throws Exception {
        AchievementPool pool = resolve(new FakeCalendar(), plain("plain_child")).pool();

        assertEquals(List.of("plain_child"), pool.ids());
        assertNull(pool.definition("plain_child").achievement().occurrence());
    }

    @Test
    void aLoadedEventWithNoFirstYearMintsNothingAndSaysSo() throws Exception {
        Map<String, AchievementAsset> layer = new LinkedHashMap<>();
        layer.put("festival_keeper", keeper());
        AchievementAssetStore.Resolution resolution =
                AchievementAssetStore.resolve(layer, List.of(), withoutFirstYear(2026));

        assertEquals(0, resolution.pool().size(), "with no first year there is no span of years to mint,"
                + " even though the calendar knows the year it is in");
        assertEquals(Severity.WARNING, finding(resolution, "OCCURRENCE_UNKNOWN_EVENT").severity());
    }

    /**
     * A calendar that has the event loaded and knows the year it is in, yet answers no first year: what
     * a calendar answers for a file stating none, or a first year outside the range it accepts.
     */
    @Nonnull
    private static OccurrenceReader withoutFirstYear(int yearNow) {
        OccurrenceSource source = new OccurrenceSource() {
            @Override
            public boolean isEnabled(@Nonnull String eventId) {
                return true;
            }

            @Override
            @Nullable
            public Occurrence live(@Nonnull String eventId, long nowMs) {
                return null;
            }

            @Override
            @Nonnull
            public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
                return List.of();
            }

            @Override
            @Nullable
            public Integer firstYear(@Nonnull String eventId) {
                return null;
            }

            @Override
            @Nullable
            public Integer currentYear(@Nonnull String eventId, long nowMs) {
                return yearNow;
            }
        };
        return new OccurrenceReader(() -> source, () -> 0L);
    }
}
