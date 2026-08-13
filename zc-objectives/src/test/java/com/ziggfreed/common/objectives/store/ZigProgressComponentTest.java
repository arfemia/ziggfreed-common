package com.ziggfreed.common.objectives.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.AchievementProgressStore;
import com.ziggfreed.common.achievement.AchievementStatus;
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

    @Test
    void aZeroTallyRemovesItsKeyRatherThanStoringAZero() {
        ZigProgressComponent component = new ZigProgressComponent();
        String key = AchievementProgressStore.criterionKey("a_first", 0);

        component.putAchievementProgress(key, 4L);
        assertEquals(Set.of(key), component.achievementProgressKeys());

        component.putAchievementProgress(key, 0L);
        assertTrue(component.achievementProgressKeys().isEmpty());
    }

    @Test
    void anAchievementIsKnownFromItsCriterionKeysAlone() {
        ZigProgressComponent component = new ZigProgressComponent();

        component.putAchievementProgress(AchievementProgressStore.criterionKey("a_first", 2), 1L);

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
        original.setQuestCompletions("q_first", new CompletionRecord(5L, 1, 2));

        ZigProgressComponent copy = original.clone();
        copy.setQuestStatus("q_first", QuestStatus.COMPLETED);
        copy.putAchievementProgress("a_first#0", 9L);
        copy.setQuestCompletions("q_first", new CompletionRecord(9L, 3, 4));

        assertEquals(QuestStatus.ACTIVE, original.questStatus("q_first"));
        assertEquals(3L, original.achievementProgress("a_first#0"));
        assertEquals(new CompletionRecord(5L, 1, 2), original.questCompletions("q_first"));
    }

    @Test
    void aCompletionRecordSurvivesTheReArmThatWipesEverythingElse() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.setQuestStatus("q_daily", QuestStatus.COMPLETED);
        component.putQuestPayload("q_daily", "packed");
        component.setQuestCooldown("q_daily", 1234L);
        component.setTrackedPin("q_daily", 7L);
        component.setQuestCompletions("q_daily", new CompletionRecord(1234L, 1, 4));

        component.clearQuest("q_daily");

        assertEquals(QuestStatus.NOT_STARTED, component.questStatus("q_daily"));
        assertNull(component.questPayload("q_daily"));
        assertEquals(0L, component.questCooldown("q_daily"));
        assertTrue(component.trackedPins().isEmpty());
        assertEquals(new CompletionRecord(1234L, 1, 4), component.questCompletions("q_daily"),
                "a lifetime cap that a re-arm wiped would be a cap nobody could ever reach");
        assertTrue(component.knownQuestIds().contains("q_daily"),
                "a quest whose only remaining trace is its tally is still one maintenance can see");
    }

    @Test
    void aCompletionRecordRoundTripsAsAThreeNumberTriple() {
        Map<String, CompletionRecord> records = Map.of(
                "q_daily", new CompletionRecord(1_700_000_000_000L, 2, 9),
                "q_weekly", new CompletionRecord(5L, 0, 1));

        Map<String, String> packed = ZigProgressComponent.encodeCompletions(records);
        assertEquals("1700000000000,2,9", packed.get("q_daily"));

        Map<String, CompletionRecord> back = ZigProgressComponent.decodeCompletions(
                String.join("|", packed.get("q_daily").isEmpty() ? "" : "q_daily=" + packed.get("q_daily"),
                        "q_weekly=" + packed.get("q_weekly")));
        assertEquals(records.get("q_daily"), back.get("q_daily"));
        assertEquals(records.get("q_weekly"), back.get("q_weekly"));
    }

    @Test
    void aBlobSavedBeforeTheLeafExistedReadsAsNothingFinished() {
        assertTrue(ZigProgressComponent.decodeCompletions(null).isEmpty(),
                "an absent leaf must read as this player having finished nothing, never as a"
                        + " broken login");
        assertTrue(ZigProgressComponent.decodeCompletions("").isEmpty());
    }

    @Test
    void aMalformedTripleCostsThatEntryAndNoOther() {
        Map<String, CompletionRecord> back =
                ZigProgressComponent.decodeCompletions("q_bad=7,x,2|q_short=1,2|q_good=5,1,3");

        assertNull(back.get("q_bad"));
        assertNull(back.get("q_short"));
        assertEquals(new CompletionRecord(5L, 1, 3), back.get("q_good"));
    }

    @Test
    void wipingACompletionRecordIsAnExplicitAct() {
        ZigProgressComponent component = new ZigProgressComponent();
        component.setQuestCompletions("q_daily", new CompletionRecord(1234L, 1, 4));

        component.setQuestCompletions("q_daily", CompletionRecord.NONE);

        assertEquals(CompletionRecord.NONE, component.questCompletions("q_daily"));
        assertFalse(component.knownQuestIds().contains("q_daily"),
                "an empty record is stored as absence, so a wipe leaves nothing behind");
    }
}
