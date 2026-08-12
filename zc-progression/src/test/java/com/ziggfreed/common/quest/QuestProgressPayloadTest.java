package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
