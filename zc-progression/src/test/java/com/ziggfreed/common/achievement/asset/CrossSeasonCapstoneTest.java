package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.subject.Subject;

/**
 * The cross-season capstone: a selector with {@code AnyYear} lets every year's keepsake copy stand for
 * its base and counts the picks by the calendar event they come back with, {@code Needs} says how many
 * seasons, a season the owner switched off leaves the count, an earned rung stays earned, and a keepsake
 * earned before the ladder existed counts at the next self-heal. {@code AtLeast} is a floor under
 * whatever is needed, so on a one-season server the top rung never comes before the rung below it.
 */
class CrossSeasonCapstoneTest {

    private static final Subject ALICE = Subject.of(new UUID(0, 21), "Alice");
    private static final Subject BOB = Subject.of(new UUID(0, 22), "Bob");

    /** A season's keepsake: yearly with its event, filed under Seasons, carrying the ladder's tag. */
    private static AchievementAsset keepsake(@Nonnull String id, @Nonnull String event) throws IOException {
        return AchievementAssetCodecTest.decodeRoot("""
                { "Occurrence": { "Event": "@EVENT" },
                  "Listing": { "Category": "Seasons", "Subcategory": "@EVENT", "Tags": [ "season_keepsake" ] },
                  "Criteria": { "one": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """.replace("@EVENT", event), id);
    }

    /** A rung of the ladder: ordinary, cross-season, picking every keepsake by its tag. */
    private static AchievementAsset rung(@Nonnull String id, @Nullable Integer needs) throws IOException {
        return rung(id, needs, null);
    }

    /** A rung with a floor under what it needs ({@code AtLeast}). */
    private static AchievementAsset rung(@Nonnull String id, @Nullable Integer needs, @Nullable Integer atLeast)
            throws IOException {
        String needsLeaf = needs == null ? "" : ", \"Needs\": " + needs;
        String atLeastLeaf = atLeast == null ? "" : ", \"AtLeast\": " + atLeast;
        return AchievementAssetCodecTest.decodeRoot("""
                { "Listing": { "Category": "Seasons" },
                  "MetaSelector": { "Category": "Seasons", "Tags": [ "season_keepsake" ], "AnyYear": true@NEEDS } }
                """.replace("@NEEDS", needsLeaf + atLeastLeaf), id);
    }

    private static AchievementAssetStore.Resolution resolve(@Nonnull OccurrenceReader calendar,
            @Nonnull AchievementAsset... assets) {
        Map<String, AchievementAsset> layer = new LinkedHashMap<>();
        for (AchievementAsset asset : assets) {
            layer.put(asset.getId(), asset);
        }
        return AchievementAssetStore.resolve(layer, List.of(), calendar);
    }

    private static AchievementEngine engineOver(@Nonnull AchievementPool pool) {
        AchievementEngine engine = AchievementEngine.builder().nativeEvents(false).build();
        engine.setAchievements(pool.achievements());
        return engine;
    }

    /** Earn one copy through the engine, so the meta cascade runs exactly as a dispatch's would. */
    private static void earn(@Nonnull AchievementEngine engine, @Nonnull Subject who, @Nonnull String id) {
        Achievement achievement = engine.achievement(id);
        assertNotNull(achievement, id + " is in the catalogue");
        engine.unlock(who, achievement);
    }

    /** The children of {@code eventId}'s season group on {@code capstone}, none when it has no such group. */
    @Nonnull
    private static List<String> season(@Nonnull Achievement capstone, @Nonnull String eventId) {
        for (Achievement.MetaGroup group : capstone.metaGroups()) {
            if (group.key().equals(Achievement.MetaGroup.seasonKey(eventId))) {
                return group.children();
            }
        }
        return List.of();
    }

    // Review Focus 4: an owner who switches a season off.
    @Test
    void aSeasonTheOwnerSwitchedOffLeavesTheCountAndTheTopRungStaysReachable() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2025, 2026)
                .event("harvest_feast", 2026, 2026).event("winter_festival", 2026, 2026)
                .switchedOff("winter_festival");
        AchievementPool pool = resolve(calendar.reader(), keepsake("hallowed", "hallows_eve"),
                keepsake("feast_keepsake", "harvest_feast"), keepsake("winter_keepsake", "winter_festival"),
                rung("every_season", null)).pool();
        AchievementEngine engine = engineOver(pool);
        Achievement top = engine.achievement("every_season");

        assertEquals(3, top.metaGroups().size(),
                "a switched-off season's keepsakes stay minted and picked; only its place in the count goes");
        assertTrue(AchievementPoolValidator.validate(pool, null, null, null, null).stream()
                        .noneMatch(f -> "UNKNOWN_META_CHILD".equals(f.code())),
                "no validator reports the switched-off season's copies as orphans");

        earn(engine, ALICE, "hallowed_2026");
        assertEquals(new AchievementEngine.CriterionTally(1, 2), engine.tally(ALICE, top),
                "the switched-off season is in neither number");
        earn(engine, ALICE, "feast_keepsake_2026");
        assertTrue(engine.isUnlocked(ALICE, "every_season"),
                "every season this server runs is two, so the top rung is reachable with two keepsakes");

        calendar.switchedOn("winter_festival");
        earn(engine, BOB, "hallowed_2026");
        earn(engine, BOB, "feast_keepsake_2026");
        assertFalse(engine.isUnlocked(BOB, "every_season"),
                "the count is read live: switched back on, the season is needed again");
        assertTrue(engine.isUnlocked(ALICE, "every_season"), "and what was earned stays earned");
    }

    @Test
    void twoYearsOfOneSeasonCountOnce() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2025, 2026)
                .event("harvest_feast", 2026, 2026);
        AchievementEngine engine = engineOver(resolve(calendar.reader(), keepsake("hallowed", "hallows_eve"),
                keepsake("feast_keepsake", "harvest_feast"), rung("two_seasons", 2)).pool());
        Achievement rung = engine.achievement("two_seasons");

        assertEquals(List.of("hallowed_2025", "hallowed_2026", "hallowed_2027"), season(rung, "hallows_eve"),
                "AnyYear lets every year's copy stand for its base, grouped under its event");
        earn(engine, ALICE, "hallowed_2025");
        earn(engine, ALICE, "hallowed_2026");
        assertFalse(engine.isUnlocked(ALICE, "two_seasons"), "two Hallow's Eves are one season");
        assertEquals(new AchievementEngine.CriterionTally(1, 2), engine.tally(ALICE, rung),
                "the tally reads seasons, not copies");

        earn(engine, ALICE, "feast_keepsake_2026");
        assertTrue(engine.isUnlocked(ALICE, "two_seasons"));
    }

    // Review Focus 5 (the minting part): a run that crosses the new year.
    @Test
    void aRunThatCrossesTheNewYearCountsItsCopyForTheYearItStarted() throws Exception {
        long january3 = LocalDate.of(2027, 1, 3).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        OccurrenceSource winter = new WinterCalendar();
        OccurrenceReader calendar = new OccurrenceReader(() -> winter, () -> january3);
        AchievementEngine engine = engineOver(resolve(calendar,
                keepsake("winter_keepsake", "winter_festival"), rung("one_season", 1)).pool());

        Achievement copy2026 = engine.achievement("winter_keepsake_2026");
        assertNotNull(copy2026, "early January is still the run that started in 2026, so its copy exists");
        assertTrue(copy2026.available(), "and it is the copy in circulation");
        assertFalse(engine.achievement("winter_keepsake_2027").available(), "2027's copy waits for its own run");
        assertEquals(2026, copy2026.occurrence().year());

        earn(engine, ALICE, "winter_keepsake_2026");
        assertTrue(engine.isUnlocked(ALICE, "one_season"),
                "a copy earned in January counts for the season it started in");
    }

    // Review Focus 2 (the minting part): a forced run mints the copy for the run's year.
    @Test
    void aForcedRunOutsideItsDatesMintsAndCirculatesTheCopyForTheRunsYear() throws Exception {
        long october20 = LocalDate.of(2026, 10, 20).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        OccurrenceSource forced = new ForcedFeastCalendar();
        OccurrenceReader calendar = new OccurrenceReader(() -> forced, () -> october20);
        AchievementEngine engine = engineOver(resolve(calendar,
                keepsake("feast_keepsake", "harvest_feast"), rung("one_season", 1)).pool());

        Achievement copy2026 = engine.achievement("feast_keepsake_2026");
        assertNotNull(copy2026, "the forced run is 2026's, so 2026's copy is minted");
        assertTrue(copy2026.available(), "and it is the copy in circulation, a month before the feast's own days");
        assertEquals(2026, copy2026.occurrence().year());
        Achievement copy2027 = engine.achievement("feast_keepsake_2027");
        assertTrue(copy2027 == null || !copy2027.available(), "no other year's copy is in circulation");

        earn(engine, ALICE, "feast_keepsake_2026");
        assertTrue(engine.isUnlocked(ALICE, "one_season"), "a copy earned in a forced run counts for its year");
    }

    @Test
    void needsTwoIsMetByTheSecondSeasonAndAThirdChangesNothing() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2026, 2026)
                .event("harvest_feast", 2026, 2026).event("winter_festival", 2026, 2026);
        AchievementEngine engine = engineOver(resolve(calendar.reader(), keepsake("hallowed", "hallows_eve"),
                keepsake("feast_keepsake", "harvest_feast"), keepsake("winter_keepsake", "winter_festival"),
                rung("two_seasons", 2)).pool());
        Achievement rung = engine.achievement("two_seasons");

        earn(engine, ALICE, "hallowed_2026");
        assertFalse(engine.isUnlocked(ALICE, "two_seasons"));
        earn(engine, ALICE, "feast_keepsake_2026");
        assertTrue(engine.isUnlocked(ALICE, "two_seasons"), "any two of the three seasons");
        long earnedAt = engine.unlockedAt(ALICE, "two_seasons");

        earn(engine, ALICE, "winter_keepsake_2026");
        assertEquals(earnedAt, engine.unlockedAt(ALICE, "two_seasons"), "a third season earns nothing twice");
        assertEquals(new AchievementEngine.CriterionTally(2, 2), engine.tally(ALICE, rung),
                "three seasons held, two needed: the bar reads full, never past it");
    }

    @Test
    void anEarnedRungStaysEarnedWhenANewSeasonRaisesEveryToThree() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2026, 2026)
                .event("harvest_feast", 2026, 2026);
        AchievementAsset hallowed = keepsake("hallowed", "hallows_eve");
        AchievementAsset feast = keepsake("feast_keepsake", "harvest_feast");
        AchievementAsset every = rung("every_season", null);
        AchievementEngine engine = engineOver(resolve(calendar.reader(), hallowed, feast, every).pool());
        earn(engine, ALICE, "hallowed_2026");
        earn(engine, ALICE, "feast_keepsake_2026");
        assertTrue(engine.isUnlocked(ALICE, "every_season"));

        calendar.event("winter_festival", 2026, 2026);
        engine.setAchievements(resolve(calendar.reader(), hallowed, feast,
                keepsake("winter_keepsake", "winter_festival"), every).pool().achievements());
        engine.selfHeal(ALICE);

        assertTrue(engine.isUnlocked(ALICE, "every_season"),
                "a new season raises the top rung for whoever has not reached it, and never takes it back");
        earn(engine, BOB, "hallowed_2026");
        earn(engine, BOB, "feast_keepsake_2026");
        assertFalse(engine.isUnlocked(BOB, "every_season"), "for anyone else, every season is now three");
    }

    @Test
    void aKeepsakeEarnedBeforeTheLadderShippedCountsAtTheNextSelfHeal() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2025, 2026)
                .event("harvest_feast", 2026, 2026);
        AchievementAsset hallowed = keepsake("hallowed", "hallows_eve");
        AchievementAsset feast = keepsake("feast_keepsake", "harvest_feast");
        AchievementEngine engine = engineOver(resolve(calendar.reader(), hallowed, feast).pool());
        earn(engine, ALICE, "hallowed_2025");
        earn(engine, ALICE, "feast_keepsake_2026");

        engine.setAchievements(resolve(calendar.reader(), hallowed, feast, rung("two_seasons", 2))
                .pool().achievements());
        assertFalse(engine.isUnlocked(ALICE, "two_seasons"), "a catalogue swap cascades nothing");
        engine.selfHeal(ALICE);
        assertTrue(engine.isUnlocked(ALICE, "two_seasons"), "the login self-heal re-reads saved keepsakes");
    }

    @Test
    void revokingOneYearsCopyKeepsARungAnotherYearStillHolds() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2025, 2026)
                .event("harvest_feast", 2026, 2026);
        AchievementEngine engine = engineOver(resolve(calendar.reader(), keepsake("hallowed", "hallows_eve"),
                keepsake("feast_keepsake", "harvest_feast"), rung("two_seasons", 2)).pool());
        earn(engine, ALICE, "hallowed_2025");
        earn(engine, ALICE, "hallowed_2026");
        earn(engine, ALICE, "feast_keepsake_2026");
        assertTrue(engine.isUnlocked(ALICE, "two_seasons"));

        engine.revoke(ALICE, "hallowed_2025");
        assertTrue(engine.isUnlocked(ALICE, "two_seasons"), "the season is still held through its 2026 copy");
        engine.revoke(ALICE, "hallowed_2026");
        assertFalse(engine.isUnlocked(ALICE, "two_seasons"), "with the season gone the rung no longer stands");
    }

    // M240, earned stays earned: revoking a copy whose season another year's copy still holds takes nothing the rung
    // stands on, so it never re-asks today's count, which a season added since may have raised.
    @Test
    void revokingARedundantCopyKeepsARungEarnedBeforeTheCountGrew() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2025, 2026)
                .event("harvest_feast", 2026, 2026);
        AchievementAsset hallowed = keepsake("hallowed", "hallows_eve");
        AchievementAsset feast = keepsake("feast_keepsake", "harvest_feast");
        AchievementAsset every = rung("every_season", null);
        AchievementEngine engine = engineOver(resolve(calendar.reader(), hallowed, feast, every).pool());
        earn(engine, ALICE, "hallowed_2025");
        earn(engine, ALICE, "hallowed_2026");
        earn(engine, ALICE, "feast_keepsake_2026");
        assertTrue(engine.isUnlocked(ALICE, "every_season"));

        calendar.event("winter_festival", 2026, 2026);
        engine.setAchievements(resolve(calendar.reader(), hallowed, feast,
                keepsake("winter_keepsake", "winter_festival"), every).pool().achievements());

        engine.revoke(ALICE, "hallowed_2025");
        assertTrue(engine.isUnlocked(ALICE, "every_season"),
                "Hallow's Eve is still held through its 2026 copy, so the rung keeps what it earned on two seasons");
        engine.revoke(ALICE, "hallowed_2026");
        assertFalse(engine.isUnlocked(ALICE, "every_season"),
                "the season's last copy gone, the rung is asked again and no longer stands");
    }

    /** An ordinary achievement filed with the keepsakes and carrying the ladder's tag, with no calendar event. */
    private static AchievementAsset ordinaryTagged(@Nonnull String id) throws IOException {
        return AchievementAssetCodecTest.decodeRoot("""
                { "Listing": { "Category": "Seasons", "Tags": [ "season_keepsake" ] },
                  "Criteria": { "one": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """, id);
    }

    // A season's group and a group of one pick are keyed in spaces of their own: an ordinary pick whose id is an
    // event's id never merges into that season's group.
    @Test
    void anOrdinaryPickNamedLikeASeasonIsAGroupOfItsOwnAndTheSeasonStillLeavesTheCount() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2025, 2026)
                .event("harvest_feast", 2026, 2026);
        AchievementEngine engine = engineOver(resolve(calendar.reader(), keepsake("lantern", "hallows_eve"),
                keepsake("feast_keepsake", "harvest_feast"), ordinaryTagged("hallows_eve"),
                rung("every_season", null)).pool());
        Achievement top = engine.achievement("every_season");

        assertEquals(3, top.metaGroups().size(),
                "Hallow's Eve, Harvest Feast, and the ordinary pick that shares Hallow's Eve's id");
        assertEquals(List.of("lantern_2025", "lantern_2026", "lantern_2027"), season(top, "hallows_eve"),
                "the season's group holds its copies alone");
        earn(engine, ALICE, "hallows_eve");
        earn(engine, ALICE, "feast_keepsake_2026");
        assertFalse(engine.isUnlocked(ALICE, "every_season"), "the ordinary pick never stands for the season");
        assertEquals(new AchievementEngine.CriterionTally(2, 3), engine.tally(ALICE, top));

        calendar.switchedOff("hallows_eve");
        assertEquals(new AchievementEngine.CriterionTally(2, 2), engine.tally(ALICE, top),
                "switched off, the season leaves the count, and the ordinary pick stays in it");
    }

    // Under AnyYear an explicit MetaChildren entry naming one year's copy stands for its season, so a season never
    // counts twice: once through the explicit copy and once through the copies the selector picks.
    @Test
    void anExplicitYearlyCopyCountsInItsSeasonsGroupSoNoSeasonCountsTwice() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2025, 2026)
                .event("harvest_feast", 2026, 2026);
        AchievementAsset every = AchievementAssetCodecTest.decodeRoot("""
                { "Listing": { "Category": "Seasons" }, "MetaChildren": [ "hallowed_2025" ],
                  "MetaSelector": { "Category": "Seasons", "Tags": [ "season_keepsake" ], "AnyYear": true } }
                """, "every_season");
        AchievementEngine engine = engineOver(resolve(calendar.reader(), keepsake("hallowed", "hallows_eve"),
                keepsake("feast_keepsake", "harvest_feast"), every).pool());
        Achievement top = engine.achievement("every_season");

        assertEquals(2, top.metaGroups().size(), "two seasons, two groups");
        assertEquals(List.of("hallowed_2025", "hallowed_2026", "hallowed_2027"), season(top, "hallows_eve"),
                "the explicit copy first, then the picks, in the season's one group");
        earn(engine, ALICE, "hallowed_2025");
        assertEquals(new AchievementEngine.CriterionTally(1, 2), engine.tally(ALICE, top),
                "the explicit copy is Hallow's Eve: one season of two");
        earn(engine, ALICE, "feast_keepsake_2026");
        assertTrue(engine.isUnlocked(ALICE, "every_season"));

        calendar.switchedOff("hallows_eve");
        assertEquals(new AchievementEngine.CriterionTally(1, 1), engine.tally(ALICE, top),
                "the explicit copy leaves the count with its season");
    }

    @Test
    void aNeedsBelowOneIsReportedAndReadsAsEverySeason() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2026, 2026);
        AchievementAssetStore.Resolution resolution = resolve(calendar.reader(),
                keepsake("hallowed", "hallows_eve"), rung("broken", 0));

        assertTrue(resolution.issues().stream().anyMatch(f -> "BAD_META_NEEDS".equals(f.code())));
        assertNull(resolution.pool().definition("broken").achievement().metaNeeds(),
                "read as every counted season");
    }

    @Test
    void anyYearOnAYearlyCapstoneIsReportedOnceAndReadAsFalse() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2026, 2026);
        AchievementAsset yearly = AchievementAssetCodecTest.decodeRoot("""
                { "Occurrence": { "Event": "hallows_eve" },
                  "MetaSelector": { "Category": "Seasons", "Tags": [ "season_keepsake" ], "AnyYear": true } }
                """, "yearly_cap");
        AchievementAssetStore.Resolution resolution = resolve(calendar.reader(),
                keepsake("hallowed", "hallows_eve"), yearly);

        assertEquals(1, resolution.issues().stream()
                        .filter(f -> "ANY_YEAR_ON_YEARLY_CAPSTONE".equals(f.code())).count(),
                "one finding for the file, not one per year it mints");
        assertEquals(List.of("hallowed_2026"),
                resolution.pool().definition("yearly_cap_2026").achievement().metaChildren(),
                "each year still stands on its own year's copies");
    }

    // AtLeast: on a one-season server "every season" is one keepsake, which would earn the top rung
    // while the rung below it (Needs 2) cannot be. The floor keeps the ladder in order.
    @Test
    void anAtLeastFloorKeepsTheTopRungLockedOnAOneSeasonWorld() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2026, 2026)
                .event("harvest_feast", 2026, 2026).switchedOff("harvest_feast");
        AchievementEngine engine = engineOver(resolve(calendar.reader(), keepsake("hallowed", "hallows_eve"),
                keepsake("feast_keepsake", "harvest_feast"), rung("two_seasons", 2),
                rung("every_season", null, 2)).pool());
        Achievement top = engine.achievement("every_season");
        assertEquals(Integer.valueOf(2), top.metaAtLeast());

        earn(engine, ALICE, "hallowed_2026");
        assertFalse(engine.isUnlocked(ALICE, "two_seasons"), "rung 1 needs two seasons, and one is on");
        assertFalse(engine.isUnlocked(ALICE, "every_season"),
                "every season on is one, but the floor is two, so the top rung never comes before rung 1");
        assertEquals(new AchievementEngine.CriterionTally(1, 2), engine.tally(ALICE, top),
                "the bar reads the floor");

        earn(engine, ALICE, "feast_keepsake_2026");
        assertFalse(engine.isUnlocked(ALICE, "every_season"),
                "a switched-off season's keepsake stays out of the count, floor or not");
    }

    @Test
    void anAtLeastFloorIsMetAtTwoWhenTwoSeasonsAreOn() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2026, 2026)
                .event("harvest_feast", 2026, 2026);
        AchievementEngine engine = engineOver(resolve(calendar.reader(), keepsake("hallowed", "hallows_eve"),
                keepsake("feast_keepsake", "harvest_feast"), rung("every_season", null, 2)).pool());

        earn(engine, ALICE, "hallowed_2026");
        assertFalse(engine.isUnlocked(ALICE, "every_season"));
        earn(engine, ALICE, "feast_keepsake_2026");
        assertTrue(engine.isUnlocked(ALICE, "every_season"),
                "two seasons on and two held: every season and the floor agree");
    }

    @Test
    void anAtLeastBelowNeedsChangesNothing() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("hallows_eve", 2026, 2026)
                .event("harvest_feast", 2026, 2026).event("winter_festival", 2026, 2026);
        AchievementEngine engine = engineOver(resolve(calendar.reader(), keepsake("hallowed", "hallows_eve"),
                keepsake("feast_keepsake", "harvest_feast"), keepsake("winter_keepsake", "winter_festival"),
                rung("three_seasons", 3, 2)).pool());
        Achievement rung = engine.achievement("three_seasons");

        earn(engine, ALICE, "hallowed_2026");
        earn(engine, ALICE, "feast_keepsake_2026");
        assertFalse(engine.isUnlocked(ALICE, "three_seasons"), "Needs 3 stands over a floor of 2");
        assertEquals(new AchievementEngine.CriterionTally(2, 3), engine.tally(ALICE, rung));
        earn(engine, ALICE, "winter_keepsake_2026");
        assertTrue(engine.isUnlocked(ALICE, "three_seasons"));
    }

    @Test
    void anAtLeastAloneGroupsTheSelectorAndItsFloorHolds() throws Exception {
        AchievementAsset floored = AchievementAssetCodecTest.decodeRoot("""
                { "MetaSelector": { "Category": "Seasons", "AtLeast": 3 } }
                """, "floored");
        String leaf = """
                { "Listing": { "Category": "Seasons" },
                  "Criteria": { "one": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """;
        AchievementEngine engine = engineOver(resolve(new FakeCalendar().reader(), floored,
                AchievementAssetCodecTest.decodeRoot(leaf, "first_leaf"),
                AchievementAssetCodecTest.decodeRoot(leaf, "second_leaf")).pool());
        Achievement capstone = engine.achievement("floored");

        assertEquals(2, capstone.metaGroups().size(), "without AnyYear each pick is its own group");
        earn(engine, ALICE, "first_leaf");
        earn(engine, ALICE, "second_leaf");
        assertFalse(engine.isUnlocked(ALICE, "floored"),
                "a floor above every pick is out of reach until more exist, exactly like Needs");
        assertEquals(new AchievementEngine.CriterionTally(2, 3), engine.tally(ALICE, capstone));
    }

    /**
     * A Winter festival from December 15 through January 6, read on a real clock: the run belongs to the
     * year it starts in, exactly as zc-core's occurrence contract says.
     */
    private static final class WinterCalendar implements OccurrenceSource {

        private static final String EVENT = "winter_festival";
        private static final long START = LocalDate.of(2026, 12, 15).atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli();
        private static final long END = LocalDate.of(2027, 1, 7).atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli();

        @Override
        public boolean isEnabled(@Nonnull String eventId) {
            return EVENT.equals(eventId);
        }

        @Override
        @Nullable
        public Occurrence live(@Nonnull String eventId, long nowMs) {
            return isEnabled(eventId) && nowMs >= START && nowMs < END ? new Occurrence(eventId, 2026, START, END)
                    : null;
        }

        @Override
        @Nonnull
        public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
            Occurrence run = live(eventId, nowMs);
            return run == null ? List.of() : List.of(run);
        }

        @Override
        @Nullable
        public Integer firstYear(@Nonnull String eventId) {
            return isEnabled(eventId) ? 2026 : null;
        }

        @Override
        @Nullable
        public Integer currentYear(@Nonnull String eventId, long nowMs) {
            if (!isEnabled(eventId)) {
                return null;
            }
            return live(eventId, nowMs) != null ? 2026 : Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).getYear();
        }
    }

    /**
     * Harvest Feast (November 20 through December 1) forced on in October 2026, answering as the calendar
     * answers a forced run (YZ1's CalendarWindowRuleServiceTest pins that answer): the run of the current
     * year, on its own days, going on whatever the clock says, and that run's year as the current one.
     */
    private static final class ForcedFeastCalendar implements OccurrenceSource {

        private static final String EVENT = "harvest_feast";
        private static final long START = LocalDate.of(2026, 11, 20).atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli();
        private static final long END = LocalDate.of(2026, 12, 2).atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli();

        @Override
        public boolean isEnabled(@Nonnull String eventId) {
            return EVENT.equals(eventId);
        }

        @Override
        @Nullable
        public Occurrence live(@Nonnull String eventId, long nowMs) {
            return isEnabled(eventId) ? new Occurrence(eventId, 2026, START, END) : null;
        }

        @Override
        @Nonnull
        public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
            Occurrence run = live(eventId, nowMs);
            return run == null ? List.of() : List.of(run);
        }

        @Override
        @Nullable
        public Integer firstYear(@Nonnull String eventId) {
            return isEnabled(eventId) ? 2026 : null;
        }

        @Override
        @Nullable
        public Integer currentYear(@Nonnull String eventId, long nowMs) {
            return isEnabled(eventId) ? 2026 : null;
        }
    }
}
