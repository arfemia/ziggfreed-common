package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.progress.ObjectiveProgressState;

/** The opaque per-quest progress payload a store persists: authored order in, authored order out. */
class QuestProgressPayloadTest {

    @Test
    void payloadRoundTripsEveryObjectiveInAuthoredOrder() {
        Map<String, ObjectiveProgressState> progress = new LinkedHashMap<>();
        progress.put("gather", new ObjectiveProgressState(2, 4));
        progress.put("deliver", new ObjectiveProgressState(0, 1));
        progress.put("return", new ObjectiveProgressState(1, 1));

        Map<String, ObjectiveProgressState> back =
                QuestProgressPayload.deserialize(QuestProgressPayload.serialize(progress));

        assertEquals(progress.keySet().stream().toList(), back.keySet().stream().toList());
        assertEquals(2, back.get("gather").current());
        assertEquals(4, back.get("gather").required());
        assertTrue(back.get("return").isCompleted());
    }

    @Test
    void emptyAndUnreadablePayloadsDecodeToAnEmptyMap() {
        assertEquals("", QuestProgressPayload.serialize(null));
        assertEquals("", QuestProgressPayload.serialize(Map.of()));
        assertTrue(QuestProgressPayload.deserialize(null).isEmpty());
        assertTrue(QuestProgressPayload.deserialize("").isEmpty());
        assertTrue(QuestProgressPayload.deserialize("not base64 at all !!!").isEmpty());
    }

    /**
     * The place a quest was taken from rides inside this one string, so no store needs a new field
     * for it. A payload written without one has to decode exactly as it always did, which is what
     * lets an already-stored blob be read by this version unchanged.
     */
    @Test
    void aPayloadWithNoPlaceIsByteIdenticalAndReadsBackAsNoPlace() {
        Map<String, ObjectiveProgressState> progress = new LinkedHashMap<>();
        progress.put("gather", new ObjectiveProgressState(2, 4));

        assertEquals(QuestProgressPayload.serialize(progress),
                QuestProgressPayload.serialize(progress, null));
        assertEquals(QuestProgressPayload.serialize(progress),
                QuestProgressPayload.serialize(progress, "   "));
        assertNull(QuestProgressPayload.acceptSite(QuestProgressPayload.serialize(progress)));
        assertNull(QuestProgressPayload.acceptSite(null));
        assertNull(QuestProgressPayload.acceptSite("not base64 at all !!!"));
    }

    @Test
    void aPlaceRoundTripsBesideTheProgressWithoutDisturbingIt() {
        Map<String, ObjectiveProgressState> progress = new LinkedHashMap<>();
        progress.put("gather", new ObjectiveProgressState(2, 4));
        progress.put("deliver", new ObjectiveProgressState(0, 1));

        String payload = QuestProgressPayload.serialize(progress, "North_Post");

        assertEquals("North_Post", QuestProgressPayload.acceptSite(payload));
        Map<String, ObjectiveProgressState> back = QuestProgressPayload.deserialize(payload);
        assertEquals(progress.keySet().stream().toList(), back.keySet().stream().toList());
        assertEquals(2, back.get("gather").current());
    }

    @Test
    void aQuestWithNoStepsStillRemembersWhereItWasTaken() {
        String payload = QuestProgressPayload.serialize(Map.of(), "North_Post");

        assertEquals("North_Post", QuestProgressPayload.acceptSite(payload));
        assertTrue(QuestProgressPayload.deserialize(payload).isEmpty());
    }

    @Test
    void aPlaceTheFormatCannotHoldIsRefusedRatherThanCutInHalf() {
        assertFalse(QuestProgressPayload.isRecordableSite("north|post"));
        assertFalse(QuestProgressPayload.isRecordableSite("north:post"));
        assertFalse(QuestProgressPayload.isRecordableSite("  "));
        assertFalse(QuestProgressPayload.isRecordableSite(null));
        assertTrue(QuestProgressPayload.isRecordableSite("North_Post"));

        assertNull(QuestProgressPayload.acceptSite(
                QuestProgressPayload.serialize(Map.of(), "north|post")));
    }
}
