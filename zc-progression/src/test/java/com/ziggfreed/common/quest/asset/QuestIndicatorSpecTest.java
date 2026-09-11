package com.ziggfreed.common.quest.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The {@code Indicator} block: what an authored file decodes to, what an unauthored leaf reads
 * as, and the per-leaf overlay that lets three scopes each say one thing.
 */
class QuestIndicatorSpecTest {

    private static QuestIndicatorSpec decode(String json) throws IOException {
        return QuestIndicatorSpec.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    @Test
    void everyAuthoredLeafLandsAndResolves() throws Exception {
        QuestIndicatorSpec spec = decode("""
                { "Enabled": true,
                  "Available": { "Enabled": true, "State": "Guild_Scroll",
                                 "Overhead": { "Enabled": false },
                                 "Map": { "Enabled": true, "Icon": "Coordinate.png" } } }
                """);

        QuestIndicatorSpec.Resolved available = spec.resolve(QuestSituation.AVAILABLE);
        assertTrue(available.enabled());
        assertEquals("Guild_Scroll", available.state());
        assertFalse(available.overhead());
        assertFalse(available.showsOverhead());
        assertTrue(available.map());
        assertTrue(available.showsMap());
        assertEquals("Coordinate.png", available.mapIcon());
    }

    @Test
    void anUnauthoredSituationReadsAsOverheadOnMapOffUnderItsOwnDefaultState() throws Exception {
        QuestIndicatorSpec spec = decode("{ }");
        for (QuestSituation situation : QuestSituation.values()) {
            QuestIndicatorSpec.Resolved r = spec.resolve(situation);
            assertTrue(r.showsOverhead(), situation.name());
            assertFalse(r.showsMap(), situation.name());
            assertEquals(situation.defaultState(), r.state(), situation.name());
            assertNull(r.mapIcon(), situation.name());
        }
        assertTrue(spec.isBlank());
        assertTrue(QuestIndicatorSpec.EMPTY.isBlank());
    }

    @Test
    void theBlockSwitchOverridesEveryGroup() throws Exception {
        QuestIndicatorSpec spec = decode("""
                { "Enabled": false, "Collect": { "Enabled": true, "Map": { "Enabled": true } } }
                """);
        QuestIndicatorSpec.Resolved collect = spec.resolve(QuestSituation.COLLECT);
        assertFalse(collect.enabled());
        assertFalse(collect.showsOverhead());
        assertFalse(collect.showsMap(), "map on under a block that is off is still off");
    }

    @Test
    void mergeLaysEveryWrittenLeafOverAndKeepsEveryUnwrittenOne() throws Exception {
        QuestIndicatorSpec global = decode("""
                { "Available": { "State": "Quest_Available", "Map": { "Enabled": true, "Icon": "Coordinate.png" } },
                  "InProgress": { "Overhead": { "Enabled": true } } }
                """);
        QuestIndicatorSpec quest = decode("""
                { "Available": { "Map": { "Enabled": false } }, "TurnIn": { "State": "Hand_Me_That" } }
                """);
        QuestIndicatorSpec step = decode("""
                { "TurnIn": { "Overhead": { "Enabled": false } } }
                """);

        QuestIndicatorSpec merged = QuestIndicatorSpec.merge(QuestIndicatorSpec.merge(global, quest), step);

        QuestIndicatorSpec.Resolved available = merged.resolve(QuestSituation.AVAILABLE);
        assertEquals("Quest_Available", available.state(), "the global state survives a quest that only touched Map");
        assertFalse(available.showsMap(), "the quest's Map.Enabled wins");
        assertEquals("Coordinate.png", available.mapIcon(), "the icon the quest never mentioned is kept");

        QuestIndicatorSpec.Resolved turnIn = merged.resolve(QuestSituation.TURN_IN);
        assertEquals("Hand_Me_That", turnIn.state(), "the quest's state survives a step that only touched Overhead");
        assertFalse(turnIn.showsOverhead(), "the step's Overhead.Enabled wins");

        assertTrue(merged.resolve(QuestSituation.IN_PROGRESS).showsOverhead());
        assertTrue(merged.resolve(QuestSituation.COLLECT).showsOverhead(), "untouched everywhere: the default");
    }

    @Test
    void mergeWithNothingOnEitherSideIsTheOtherSideOrEmpty() throws Exception {
        QuestIndicatorSpec only = decode("{ \"Enabled\": false }");
        assertEquals(only, QuestIndicatorSpec.merge(only, null));
        assertEquals(only, QuestIndicatorSpec.merge(null, only));
        assertTrue(QuestIndicatorSpec.merge(null, null).isBlank());
    }

    @Test
    void aBlankStateReadsAsTheDefaultAndTheSituationKeysRoundTrip() throws Exception {
        QuestIndicatorSpec spec = decode("{ \"Collect\": { \"State\": \"   \" } }");
        assertEquals(QuestSituation.COLLECT.defaultState(), spec.resolve(QuestSituation.COLLECT).state());
        for (QuestSituation situation : QuestSituation.values()) {
            assertEquals(situation, QuestSituation.byKey(situation.key()));
            assertEquals(situation, QuestSituation.byKey(situation.key().toUpperCase(java.util.Locale.ROOT)));
        }
        assertNull(QuestSituation.byKey("Parked"));
        assertNotNull(spec.situation(QuestSituation.COLLECT));
        assertTrue(QuestSituation.COLLECT.outranks(QuestSituation.IN_PROGRESS));
        assertFalse(QuestSituation.IN_PROGRESS.outranks(QuestSituation.AVAILABLE));
    }
}
