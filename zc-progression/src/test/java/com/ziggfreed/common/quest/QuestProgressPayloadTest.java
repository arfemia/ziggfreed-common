package com.ziggfreed.common.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

    @Test
    void theRunAQuestWasTakenInRidesBesideItsPlaceAndAnOlderPayloadNamesNone() {
        Map<String, ObjectiveProgressState> progress = new LinkedHashMap<>();
        progress.put("logs", new ObjectiveProgressState(1, 3));
        String both = QuestProgressPayload.serialize(progress, "North_Post", new PerRuns.RunKey(2026, 2));
        assertEquals("North_Post", QuestProgressPayload.acceptSite(both), "the place and the run, each read by its own reader");
        assertEquals(new PerRuns.RunKey(2026, 2), QuestProgressPayload.takenIn(both));
        assertEquals(1, QuestProgressPayload.deserialize(both).get("logs").current());
        String runOnly = QuestProgressPayload.serialize(progress, null, new PerRuns.RunKey(2027, 1));
        assertNull(QuestProgressPayload.acceptSite(runOnly));
        assertEquals(new PerRuns.RunKey(2027, 1), QuestProgressPayload.takenIn(runOnly));
        assertEquals(QuestProgressPayload.serialize(progress, "North_Post"),
                QuestProgressPayload.serialize(progress, "North_Post", null), "no run: byte for byte as before");
        assertNull(QuestProgressPayload.takenIn(QuestProgressPayload.serialize(progress, "North_Post")));
    }

    /**
     * A rollback to a build from before the run stamp reads a stamped payload as its own: the same steps and a
     * clean place. That build reads everything after the first {@code |} as the place, so the stamp rides in the
     * step list as an item with no {@code :}, which its reader drops; a place read as {@code North_Post|...} would
     * strand an at-the-place quest away from its own place.
     */
    @Test
    void aBuildFromBeforeTheRunStampReadsAStampedPayloadsStepsAndPlaceAsItsOwn() {
        Map<String, ObjectiveProgressState> progress = new LinkedHashMap<>();
        progress.put("logs", new ObjectiveProgressState(1, 3));
        progress.put("ore", new ObjectiveProgressState(0, 2));

        String both = QuestProgressPayload.serialize(progress, "North_Post", new PerRuns.RunKey(2026, 2));
        assertEquals("North_Post", releasedAcceptSite(both), "the place reads clean");
        assertEquals(steps(progress), steps(releasedDeserialize(both)), "the same steps, and the stamp is none of them");
        assertEquals(steps(progress), steps(QuestProgressPayload.deserialize(both)), "nor is it one to this build");
        assertEquals(new PerRuns.RunKey(2026, 2), QuestProgressPayload.takenIn(both));

        String runOnly = QuestProgressPayload.serialize(progress, null, new PerRuns.RunKey(2027, 1));
        assertNull(releasedAcceptSite(runOnly));
        assertEquals(steps(progress), steps(releasedDeserialize(runOnly)));

        String noSteps = QuestProgressPayload.serialize(Map.of(), "North_Post", new PerRuns.RunKey(2027, 1));
        assertEquals("North_Post", releasedAcceptSite(noSteps));
        assertTrue(releasedDeserialize(noSteps).isEmpty());
        assertEquals(new PerRuns.RunKey(2027, 1), QuestProgressPayload.takenIn(noSteps));
    }

    /** Each step as {@code id:current/required}, in order, since a progress state has no equality of its own. */
    @Nonnull
    private static List<String> steps(@Nonnull Map<String, ObjectiveProgressState> progress) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, ObjectiveProgressState> entry : progress.entrySet()) {
            out.add(entry.getKey() + ':' + entry.getValue().serialize());
        }
        return out;
    }

    // The released reader: deserialize and acceptSite as every build before the run stamp carries them (886e1a31),
    // copied as they shipped. Never update these to match this build: they are what a rollback reads with.

    @Nonnull
    private static Map<String, ObjectiveProgressState> releasedDeserialize(@Nullable String payload) {
        Map<String, ObjectiveProgressState> out = new LinkedHashMap<>();
        String decoded = releasedDecode(payload);
        if (decoded == null) {
            return out;
        }
        for (String pair : releasedEntriesOf(decoded).split(",")) {
            int colon = pair.indexOf(':');
            if (colon > 0 && colon < pair.length() - 1) {
                out.put(pair.substring(0, colon), ObjectiveProgressState.deserialize(pair.substring(colon + 1)));
            }
        }
        return out;
    }

    @Nullable
    private static String releasedAcceptSite(@Nullable String payload) {
        String decoded = releasedDecode(payload);
        if (decoded == null) {
            return null;
        }
        int separator = decoded.indexOf('|');
        if (separator < 0) {
            return null;
        }
        String header = decoded.substring(separator + 1);
        if (!header.startsWith("@site=")) {
            return null;
        }
        String site = header.substring("@site=".length()).trim();
        return site.isEmpty() ? null : site;
    }

    @Nullable
    private static String releasedDecode(@Nullable String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nonnull
    private static String releasedEntriesOf(@Nonnull String decoded) {
        int separator = decoded.indexOf('|');
        return separator < 0 ? decoded : decoded.substring(0, separator);
    }
}
