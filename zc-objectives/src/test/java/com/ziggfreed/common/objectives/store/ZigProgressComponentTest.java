package com.ziggfreed.common.objectives.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.ziggfreed.common.achievement.AchievementProgressStore;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.quest.PerRuns;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.quest.QuestLifecycle;
import com.ziggfreed.common.quest.QuestProgressStore.CompletionRecord;
import com.ziggfreed.common.quest.QuestStatus;

/**
 * The persisted state machine both store adapters delegate to. The adapters themselves are a
 * component lookup and a call, so this is where the behaviour actually lives.
 *
 * <p>The codec is asserted for static initialization only (that is what catches a lower-case key at
 * build time); an encode / decode pass needs a running asset registry, so it belongs to in-game
 * smoke rather than here. What a saved world would hold is pinned by {@code ProgressBlobTest}.
 */
class ZigProgressComponentTest {

    @Test
    void theCodecStaticInitializes() {
        assertNotNull(ZigProgressComponent.CODEC,
                "a lower-case KeyedCodec key would throw here rather than at server start");
    }

    @Test
    void anEmptyComponentReadsNeutralEverywhere() {
        ZigProgressComponent component = new ZigProgressComponent();

        assertEquals(QuestStatus.NOT_STARTED, component.questStatus("q_first"));
        assertNull(component.questPayload("q_first"));
        assertEquals(0L, component.questCooldown("q_first"));
        assertEquals(CompletionRecord.NONE, component.questCompletions("q_first"));
        assertTrue(component.knownQuestIds().isEmpty());
        assertTrue(component.trackedPins().isEmpty());
        assertEquals(AchievementStatus.LOCKED, component.achievementStatus("a_first"));
        assertEquals(0L, component.achievementProgress("a_first#0"));
        assertEquals(0L, component.achievementUnlockedAt("a_first"));
        assertEquals(AchievementStatus.LOCKED, component.milestoneStatus(50));
        assertTrue(component.knownMilestones().isEmpty());
        assertTrue(component.achievementPins().isEmpty());
        assertTrue(component.achievementSeenMarks().isEmpty());
    }

    @Test
    void questStateRoundTripsAndTheDefaultStatusIsStoredAsAbsence() {
        ZigProgressComponent component = new ZigProgressComponent();

        component.setQuestStatus("q_first", QuestStatus.ACTIVE);
        component.putQuestPayload("q_first", "logs|3/5");
        component.setQuestCooldown("q_first", 1_700L);
        component.setTrackedPin("q_first", 42L);

        assertEquals(QuestStatus.ACTIVE, component.questStatus("q_first"));
        assertEquals("logs|3/5", component.questPayload("q_first"));
        assertEquals(1_700L, component.questCooldown("q_first"));
        assertEquals(Map.of("q_first", Long.valueOf(42L)), component.trackedPins());
        assertEquals(Set.of("q_first"), component.knownQuestIds());

        component.setQuestStatus("q_first", QuestStatus.NOT_STARTED);
        assertEquals(QuestStatus.NOT_STARTED, component.questStatus("q_first"));
    }

    @Test
    void clearingAQuestForgetsEveryPieceOfIt() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.setQuestStatus("q_first", QuestStatus.COMPLETED);
        component.putQuestPayload("q_first", "logs|5/5");
        component.setQuestCooldown("q_first", 99L);
        component.setTrackedPin("q_first", 1L);

        component.clearQuest("q_first");

        assertTrue(component.knownQuestIds().isEmpty(),
                "a quest that comes back around must start pristine, not half-remembered");
    }

    /**
     * The {@code CounterMap} rule applied to the five quest maps: a record saved under one casing
     * answers under any other, a write under the new spelling replaces the saved entry rather than
     * sitting beside it, and a removal under either spelling finds it. Pinned over a record built
     * the way a pre-1.6 owner quest's states reached this component - authored {@code My_Quest},
     * keyed {@code my_quest} by the catalogue that reads it back.
     */
    @Test
    void aQuestIdReadsUnderAnyCasingAndTakesTheWritersSpellingOnItsNextWrite() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.setQuestStatus("My_Quest", QuestStatus.ACTIVE);
        component.putQuestPayload("My_Quest", "step:1/3");
        component.setQuestCooldown("My_Quest", 1_700L);
        component.setTrackedPin("My_Quest", 42L);
        component.setQuestCompletions("My_Quest", new CompletionRecord(5L, 1, 2, 2));

        assertEquals(QuestStatus.ACTIVE, component.questStatus("my_quest"),
                "a status saved under the authored casing answers under the catalogue's");
        assertEquals("step:1/3", component.questPayload("my_quest"));
        assertEquals(1_700L, component.questCooldown("MY_QUEST"));
        assertEquals(2, component.questCompletions("my_quest").totalCount());
        assertTrue(component.trackedPins().containsKey("my_quest"),
                "the pin map answers containsKey under any spelling");
        assertEquals(Set.of("My_Quest"), component.knownQuestIds(),
                "one quest, listed under the spelling it is held");

        component.setQuestStatus("my_quest", QuestStatus.COMPLETED);
        component.putQuestPayload("my_quest", "step:3/3");
        component.setQuestCooldown("my_quest", 1_800L);
        component.setTrackedPin("my_quest", 43L);
        component.setQuestCompletions("my_quest", new CompletionRecord(6L, 1, 3, 3));

        assertEquals(Set.of("my_quest"), component.knownQuestIds(),
                "the writer's spelling replaced the saved one on every leaf, and nothing sits beside it");
        assertEquals(QuestStatus.COMPLETED, component.questStatus("My_Quest"),
                "the old spelling still reads, now finding the re-spelled entry");
        assertEquals("step:3/3", component.questPayload("MY_QUEST"));
        assertEquals(1_800L, component.questCooldown("My_Quest"));
        assertEquals(3, component.questCompletions("My_Quest").claimedCount());
        assertEquals(Map.of("my_quest", Long.valueOf(43L)), component.trackedPins());

        assertTrue(component.clearTrackedPin("MY_QUEST"), "a removal finds it under any casing");
        component.clearQuest("My_Quest");
        assertEquals(QuestStatus.NOT_STARTED, component.questStatus("my_quest"));
        assertNull(component.questPayload("my_quest"));
        assertEquals(0L, component.questCooldown("my_quest"));
        assertEquals(3, component.questCompletions("my_quest").totalCount(),
                "the completion record survives the re-arm, exactly as under one spelling");
    }

    /**
     * A saved record that already holds two spellings of one quest in one leaf (a build before
     * the rule existed wrote the second beside the first) reads as one quest, and a reset under
     * either spelling clears both - otherwise the other spelling would resurrect the state the
     * reset just removed.
     */
    @Test
    void twoSavedSpellingsOfOneQuestAreOneQuestAndAResetClearsBoth() {
        BsonDocument saved = new BsonDocument();
        saved.put("QuestStates", new BsonString("My_Quest=ACTIVE|my_quest=COMPLETED"));
        ZigProgressComponent older = ZigProgressComponent.CODEC.decode(saved, new ExtraInfo());

        assertEquals(1, older.knownQuestIds().size(), "two spellings list once");
        assertTrue(older.knownQuestIds().iterator().next().equalsIgnoreCase("my_quest"),
                "under one of its spellings");
        assertEquals(QuestStatus.ACTIVE, older.questStatus("My_Quest"), "an exact hit is read first");
        assertEquals(QuestStatus.COMPLETED, older.questStatus("my_quest"), "and so is the other");

        older.clearQuest("my_quest");
        assertEquals(QuestStatus.NOT_STARTED, older.questStatus("My_Quest"),
                "a reset under one spelling leaves nothing under the other to come back");
        assertTrue(older.knownQuestIds().isEmpty());
    }

    @Test
    void aTallyIsDroppedOutrightAndReportsWhetherItWasThere() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.putAchievementProgress("a_first#0", 4L);

        assertTrue(component.clearAchievementProgress("a_first#0"));
        assertEquals(0L, component.achievementProgress("a_first#0"));
        assertFalse(component.clearAchievementProgress("a_first#0"), "gone means gone");
    }

    @Test
    void aZeroTallyRemovesItsKeyRatherThanStoringAZero() {
        ZigProgressComponent component = new ZigProgressComponent();
        String key = AchievementProgressStore.criterionKey("a_first", "0");

        component.putAchievementProgress(key, 4L);
        assertEquals(Set.of(key), component.achievementProgressKeys());

        component.putAchievementProgress(key, 0L);
        assertTrue(component.achievementProgressKeys().isEmpty());
    }

    @Test
    void anAchievementIsKnownFromItsCriterionKeysAlone() {
        ZigProgressComponent component = new ZigProgressComponent();

        component.putAchievementProgress(AchievementProgressStore.criterionKey("a_first", "2"), 1L);

        assertEquals(Set.of("a_first"), component.knownAchievementIds(),
                "the composite key carries the achievement id, so a maintenance sweep finds it");
    }

    @Test
    void milestonesAreKeyedByTheirThreshold() {
        ZigProgressComponent component = new ZigProgressComponent();

        component.setMilestoneStatus(50, AchievementStatus.UNLOCKED);

        assertEquals(AchievementStatus.UNLOCKED, component.milestoneStatus(50));
        assertEquals(Set.of(Integer.valueOf(50)), component.knownMilestones());
    }

    @Test
    void pinsAreDroppedOnceAndReportItHonestly() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.setAchievementPin("a_first", 5L);

        assertTrue(component.clearAchievementPin("a_first"));
        assertFalse(component.clearAchievementPin("a_first"));
    }

    @Test
    void cloneCopiesEveryMapRatherThanSharingIt() {
        ZigProgressComponent original = new ZigProgressComponent();
        original.setQuestStatus("q_first", QuestStatus.ACTIVE);
        original.putAchievementProgress("a_first#0", 3L);
        original.setQuestCompletions("q_first", CompletionRecord.withoutCollectedTally(5L, 1, 2));

        ZigProgressComponent copy = original.clone();
        copy.setQuestStatus("q_first", QuestStatus.COMPLETED);
        copy.putAchievementProgress("a_first#0", 9L);
        copy.setQuestCompletions("q_first", CompletionRecord.withoutCollectedTally(9L, 3, 4));

        assertEquals(QuestStatus.ACTIVE, original.questStatus("q_first"));
        assertEquals(3L, original.achievementProgress("a_first#0"));
        assertEquals(CompletionRecord.withoutCollectedTally(5L, 1, 2), original.questCompletions("q_first"));
    }

    @Test
    void aCompletionRecordSurvivesTheReArmThatWipesEverythingElse() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.setQuestStatus("q_daily", QuestStatus.COMPLETED);
        component.putQuestPayload("q_daily", "packed");
        component.setQuestCooldown("q_daily", 1234L);
        component.setTrackedPin("q_daily", 7L);
        component.setQuestCompletions("q_daily", CompletionRecord.withoutCollectedTally(1234L, 1, 4));

        component.clearQuest("q_daily");

        assertEquals(QuestStatus.NOT_STARTED, component.questStatus("q_daily"));
        assertNull(component.questPayload("q_daily"));
        assertEquals(0L, component.questCooldown("q_daily"));
        assertTrue(component.trackedPins().isEmpty());
        assertEquals(CompletionRecord.withoutCollectedTally(1234L, 1, 4), component.questCompletions("q_daily"),
                "a lifetime cap that a re-arm wiped would be a cap nobody could ever reach");
        assertTrue(component.knownQuestIds().contains("q_daily"),
                "a quest whose only remaining trace is its tally is still one maintenance can see");
    }

    @Test
    void aCompletionRecordRoundTripsAsAFourNumberValue() {
        Map<String, CompletionRecord> records = Map.of(
                "q_daily", new CompletionRecord(1_700_000_000_000L, 2, 9, 8),
                "q_weekly", new CompletionRecord(5L, 0, 1, 0));

        Map<String, String> packed = ZigProgressComponent.encodeCompletions(records);
        assertEquals("1700000000000,2,9,8", packed.get("q_daily"),
                "finished and collected are both on the wire, in that order");

        Map<String, CompletionRecord> back = ZigProgressComponent.decodeCompletions(
                String.join("|", packed.get("q_daily").isEmpty() ? "" : "q_daily=" + packed.get("q_daily"),
                        "q_weekly=" + packed.get("q_weekly")));
        assertEquals(records.get("q_daily"), back.get("q_daily"));
        assertEquals(8, back.get("q_daily").claimedCount());
        assertEquals(records.get("q_weekly"), back.get("q_weekly"));
        assertEquals(0, back.get("q_weekly").claimedCount(),
                "a run finished and never collected keeps its uncollected tally through a save");
    }

    @Test
    void aOnceARunRecordCarriesItsRunTallyAndEveryOtherKeepsFourNumbers() {
        Map<String, CompletionRecord> records = Map.of(
                "q_fair", new CompletionRecord(1_700_000_000_000L, 0, 3, 3, 2026, 1),
                "q_daily", new CompletionRecord(5L, 1, 1, 1));

        Map<String, String> packed = ZigProgressComponent.encodeCompletions(records);
        assertEquals("1700000000000,0,3,3,2026,1", packed.get("q_fair"));
        assertEquals("5,1,1,1", packed.get("q_daily"), "a quest with no once-a-run rule saves exactly as before");

        Map<String, CompletionRecord> back = ZigProgressComponent.decodeCompletions(
                "q_fair=" + packed.get("q_fair") + "|q_daily=" + packed.get("q_daily"));
        assertEquals(records.get("q_fair"), back.get("q_fair"));
        assertEquals(Integer.valueOf(2026), back.get("q_fair").runYear());
        assertEquals(1, back.get("q_fair").runCount());
        assertEquals(records.get("q_daily"), back.get("q_daily"));
        assertNull(back.get("q_daily").runYear());
    }

    @Test
    void aLaterRunOfAYearAddsItsNumberAndRunOneKeepsSixNumbers() {
        Map<String, CompletionRecord> records = Map.of(
                "q_autumn", new CompletionRecord(1_700_000_000_000L, 0, 2, 2, 2026, 1, 2),
                "q_spring", new CompletionRecord(1_600_000_000_000L, 0, 1, 1, 2026, 1, 1),
                "q_daily", new CompletionRecord(5L, 1, 1, 1));
        Map<String, String> packed = ZigProgressComponent.encodeCompletions(records);
        assertEquals("1700000000000,0,2,2,2026,1,2", packed.get("q_autumn"));
        assertEquals("1600000000000,0,1,1,2026,1", packed.get("q_spring"), "run 1 saves exactly as before");
        assertEquals("5,1,1,1", packed.get("q_daily"));
        Map<String, CompletionRecord> back = ZigProgressComponent.decodeCompletions("q_autumn=" + packed.get("q_autumn")
                + "|q_spring=" + packed.get("q_spring") + "|q_daily=" + packed.get("q_daily"));
        assertEquals(records.get("q_autumn"), back.get("q_autumn"));
        assertEquals(2, back.get("q_autumn").runNumber());
        assertEquals(records.get("q_spring"), back.get("q_spring"));
        assertEquals(records.get("q_daily"), back.get("q_daily"));
    }

    /**
     * A run forced on outside its dates is still the run of its year, yet a finish in it can sit nearer
     * another year's run than its own: forced on in April, the 2027 run's days are October's, and the
     * 2026 run ended fewer months before. Only the saved run year keeps the quest spent for that run once
     * the player logs back in: read back as four numbers, it would be an old record placed in the run
     * nearest its last finish, the 2026 one, and offered again in the very run it was finished in.
     */
    @Test
    void aFinishInARunForcedOnOutsideItsDatesStaysSpentForThatRunThroughASave() {
        ForcedFair fair = new ForcedFair();
        Quest.Repeat repeat = new Quest.Repeat(0L, Quest.Repeat.CooldownFrom.CLAIM, null, 0,
                new Quest.Repeat.PerRun(ForcedFair.EVENT, 1));
        long forcedFinish = at("2027-04-10T12:00:00Z");
        Integer counted = PerRuns.runFor(repeat.perRun(), forcedFinish, fair).year();
        assertEquals(Integer.valueOf(2027), counted, "forced on in April it is the 2027 run");
        CompletionRecord finished = new CompletionRecord(forcedFinish, 0, 1, 1, counted, 1);

        ZigProgressComponent component = new ZigProgressComponent();
        component.setQuestCompletions("q_fair", finished);
        BsonDocument saved = ZigProgressComponent.CODEC.encode(component, ExtraInfo.THREAD_LOCAL.get());
        ZigProgressComponent loaded = new ZigProgressComponent();
        ZigProgressComponent.CODEC.decode(BsonDocument.parse(saved.toJson()), loaded, ExtraInfo.THREAD_LOCAL.get());
        CompletionRecord back = loaded.questCompletions("q_fair");
        assertEquals(finished, back, "the run tally survives a save and a load");

        long laterInTheForcedRun = at("2027-04-15T12:00:00Z");
        QuestLifecycle.RepeatCheck check = QuestLifecycle.repeatCheck(repeat, 0L, back, laterInTheForcedRun, fair);
        assertFalse(check.available(), "still spent for the 2027 run");
        assertEquals(QuestGates.REASON_RUN_SPENT, check.reason());
        assertTrue(QuestLifecycle.repeatCheck(repeat, 0L, new CompletionRecord(forcedFinish, 0, 1, 1),
                laterInTheForcedRun, fair).available(), "four numbers alone would have offered it twice in one run");
    }

    /**
     * One event with runs in 2026 and 2027, each October 1 through November 3 (UTC), forced on: as the
     * calendar answers a force, the run going on outside the dates is the current year's run, with that
     * year's days. The next run is the first to start after the one going on.
     */
    private static final class ForcedFair implements OccurrenceSource {

        static final String EVENT = "Spring_Fair";

        private final List<Occurrence> runs = List.of(
                new Occurrence(EVENT, 2026, at("2026-10-01T00:00:00Z"), at("2026-11-04T00:00:00Z")),
                new Occurrence(EVENT, 2027, at("2027-10-01T00:00:00Z"), at("2027-11-04T00:00:00Z")));

        @Override
        public boolean isEnabled(@Nonnull String eventId) {
            return EVENT.equalsIgnoreCase(eventId.trim());
        }

        @Override
        @Nullable
        public Occurrence live(@Nonnull String eventId, long nowMs) {
            if (!isEnabled(eventId)) {
                return null;
            }
            int year = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).getYear();
            return runs.stream().filter(run -> run.contains(nowMs) || run.year() == year).findFirst().orElse(null);
        }

        @Override
        @Nonnull
        public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
            return isEnabled(eventId) ? runs.stream().filter(run -> run.startMs() <= nowMs).toList() : List.of();
        }

        @Override
        @Nullable
        public Occurrence next(@Nonnull String eventId, long nowMs) {
            Occurrence running = live(eventId, nowMs);
            return isEnabled(eventId) ? runs.stream()
                    .filter(run -> run.startMs() > nowMs && (running == null || run.year() > running.year()))
                    .findFirst().orElse(null) : null;
        }
    }

    private static long at(@Nonnull String isoInstant) {
        return Instant.parse(isoInstant).toEpochMilli();
    }

    /**
     * The compatibility half: a value written before the collected tally existed carries three
     * fields, and every finish it recorded was paid out under the rule it was written under. Reading
     * it as nothing collected would take a completed prerequisite away from a player who had earned
     * it, so it reads as collected equal to finished.
     */
    @Test
    void aValueSavedBeforeTheCollectedTallyReadsEveryFinishAsCollected() {
        Map<String, CompletionRecord> back =
                ZigProgressComponent.decodeCompletions("q_daily=1700000000000,2,9");

        CompletionRecord legacy = back.get("q_daily");
        assertNotNull(legacy);
        assertEquals(9, legacy.totalCount());
        assertEquals(9, legacy.claimedCount());
        assertEquals(new CompletionRecord(1_700_000_000_000L, 2, 9, 9), legacy);
    }

    /**
     * The fourth field arrives off a save file, so it is the one number here that nothing upstream
     * vouches for. More collected than finished is not a history a player can have, and the record's
     * own constructor is what refuses it - the decoder deliberately does not check, so this pins that
     * the refusal survives the trip through it.
     */
    @Test
    void aCollectedTallyLargerThanTheFinishedOneIsClampedOnTheWayIn() {
        Map<String, CompletionRecord> back = ZigProgressComponent.decodeCompletions("q_over=5,1,3,9");

        assertEquals(3, back.get("q_over").claimedCount(),
                "nobody collected more runs than they finished, whatever the value said");
        assertEquals(3, back.get("q_over").totalCount());
    }

    @Test
    void aBlobSavedBeforeTheLeafExistedReadsAsNothingFinished() {
        assertTrue(ZigProgressComponent.decodeCompletions(null).isEmpty(),
                "an absent leaf must read as this player having finished nothing, never as a"
                        + " broken login");
        assertTrue(ZigProgressComponent.decodeCompletions("").isEmpty());
    }

    @Test
    void aMalformedValueCostsThatEntryAndNoOther() {
        Map<String, CompletionRecord> back = ZigProgressComponent.decodeCompletions(
                "q_bad=7,x,2|q_short=1,2|q_long=1,2,3,4,5|q_good=5,1,3");

        assertNull(back.get("q_bad"));
        assertNull(back.get("q_short"));
        assertNull(back.get("q_long"),
                "three, four, six and seven fields are the widths there are; five is unreadable");
        assertEquals(CompletionRecord.withoutCollectedTally(5L, 1, 3), back.get("q_good"));
    }

    @Test
    void wipingACompletionRecordIsAnExplicitAct() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.setQuestCompletions("q_daily", CompletionRecord.withoutCollectedTally(1234L, 1, 4));

        component.setQuestCompletions("q_daily", CompletionRecord.NONE);

        assertEquals(CompletionRecord.NONE, component.questCompletions("q_daily"));
        assertFalse(component.knownQuestIds().contains("q_daily"),
                "an empty record is stored as absence, so a wipe leaves nothing behind");
    }

    // ==================== achievement seen marks (the appended AchievementSeen leaf) ====================

    @Test
    void aSeenMarkRoundTripsThroughItsOwnLeaf() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.setAchievementSeen("Combat", 1_700_000_000_000L);
        component.setAchievementSeen("seasons", 1_800_000_000_000L);

        BsonDocument encoded = ZigProgressComponent.CODEC.encode(component, ExtraInfo.THREAD_LOCAL.get());
        assertTrue(encoded.containsKey("AchievementSeen"), "the marks travel in their own leaf");
        ZigProgressComponent back = new ZigProgressComponent();
        ZigProgressComponent.CODEC.decode(BsonDocument.parse(encoded.toJson()), back, ExtraInfo.THREAD_LOCAL.get());

        assertEquals(1_700_000_000_000L, back.achievementSeen("combat"), "a category reads under any casing");
        assertEquals(1_800_000_000_000L, back.achievementSeen("Seasons"));
        assertEquals(0L, back.achievementSeen("gathering"), "a category never opened reads 0");
        assertEquals(Map.of("combat", 1_700_000_000_000L, "seasons", 1_800_000_000_000L), back.achievementSeenMarks());
    }

    @Test
    void theSeenLeafIsAppendedAfterEveryOlderLeaf() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.setAchievementSeen("combat", 5L);
        component.setDialogueMemory("flag");
        component.claimMigration("m1");

        BsonDocument encoded = ZigProgressComponent.CODEC.encode(component, ExtraInfo.THREAD_LOCAL.get());
        String last = null;
        for (String key : encoded.keySet()) {
            last = key;
        }
        assertEquals("AchievementSeen", last, "a new leaf goes on the end of the codec, never in the middle");
    }

    @Test
    void aBlobSavedBeforeTheSeenLeafExistedReadsWithNoMarks() {
        BsonDocument saved = new BsonDocument();
        saved.put("AchievementPins", new BsonString("a_first=5"));
        saved.put("Migrations", new BsonString(""));
        ZigProgressComponent older = ZigProgressComponent.CODEC.decode(saved, new ExtraInfo());

        assertTrue(older.achievementSeenMarks().isEmpty());
        assertEquals(0L, older.achievementSeen("combat"));
        assertEquals(Map.of("a_first", 5L), older.achievementPins(), "the older leaves read as before");
    }

    @Test
    void aSeenMarkIsReplacedClearedAndRefusesAReservedCategory() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.setAchievementSeen("combat", 5L);
        component.setAchievementSeen("COMBAT", 9L);
        assertEquals(Map.of("combat", 9L), component.achievementSeenMarks(), "one mark per category, the latest");

        component.setAchievementSeen("combat", 0L);
        assertTrue(component.achievementSeenMarks().isEmpty(), "a non-positive stamp clears the mark");

        component.setAchievementSeen("a|b", 5L);
        component.setAchievementSeen("a=b", 5L);
        component.setAchievementSeen(" ", 5L);
        assertTrue(component.achievementSeenMarks().isEmpty(), "a category the blob format reserves is refused");
    }

    @Test
    void cloneCopiesTheSeenMarks() {
        ZigProgressComponent original = new ZigProgressComponent();
        original.setAchievementSeen("combat", 5L);
        ZigProgressComponent copy = original.clone();
        copy.setAchievementSeen("combat", 9L);
        assertEquals(5L, original.achievementSeen("combat"));
        assertEquals(9L, copy.achievementSeen("combat"));
    }
}
