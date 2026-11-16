package com.ziggfreed.common.stats.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The station seam behind {@code ZigWorkingAt} (R948): unfilled, nobody works at a station, so the condition never
 * holds and a file naming it still decodes; filled, it holds at a station its list names (without regard to case), at
 * any station with no list, and never away from the work. Its session and piece knobs each ask one fact of the work, a
 * piece knob failing with no piece. The station readings' two tests of a share's conditions (counts here, bound
 * elsewhere) leave an inverted condition out and take the knobs as holding. The seam and the pieces are the test's own
 * stand-ins.
 */
class ZigWorkingAtConditionTest {

    private static final Ref<EntityStore> REF = new Ref<>((Store<EntityStore>) null, 1);

    @BeforeAll
    static void registerTheCondition() {
        // The library registers its conditions at setup; a unit JVM registers the one this file names.
        ZigWorkingAtCondition.ensureKnown();
    }

    @AfterEach
    void unfill() {
        StationWork.install(null);
    }

    @Test
    void theSeamUnfilled_neverHolds_andAFileNamingItStillDecodes() throws IOException {
        Condition decoded = decode("{ \"Id\": \"ZigWorkingAt\", \"Stations\": [ \"Sawmill\" ] }");
        assertInstanceOf(ZigWorkingAtCondition.class, decoded);
        assertFalse(StationWork.installed());
        assertNull(StationWork.workAt(null, REF), "no station mod: nobody works at a station");
        assertFalse(decoded.eval(null, REF, Instant.EPOCH));
        assertFalse(ZigWorkingAtCondition.at().eval(null, REF, Instant.EPOCH), "not even at any station");
    }

    @Test
    void theSeamFilled_holdsAtAListedStation_withoutRegardToCase() throws IOException {
        StationWork.Work[] work = {new StationWork.Work("sawmill", 1, null)};
        StationWork.install((accessor, ref) -> work[0]);
        Condition sawmill = decode("{ \"Id\": \"ZigWorkingAt\", \"Stations\": [ \"Sawmill\" ] }");
        Condition anywhere = decode("{ \"Id\": \"ZigWorkingAt\" }");
        Condition notAtSawmill = decode("{ \"Id\": \"ZigWorkingAt\", \"Stations\": [ \"Sawmill\" ], \"Inverse\": true }");

        assertTrue(sawmill.eval(null, REF, Instant.EPOCH));
        assertTrue(anywhere.eval(null, REF, Instant.EPOCH));
        assertFalse(notAtSawmill.eval(null, REF, Instant.EPOCH));

        work[0] = new StationWork.Work("Disenchanting_Table", 1, null);
        assertFalse(sawmill.eval(null, REF, Instant.EPOCH), "another station is not the Sawmill");
        assertTrue(anywhere.eval(null, REF, Instant.EPOCH));

        work[0] = null;
        assertFalse(sawmill.eval(null, REF, Instant.EPOCH), "away from the work");
        assertFalse(anywhere.eval(null, REF, Instant.EPOCH), "away from the work, even with no list");
        assertTrue(notAtSawmill.eval(null, REF, Instant.EPOCH));
    }

    @Test
    void eachKnob_asksOneFactOfTheWork_andAPieceKnobFailsWithNoPiece() throws IOException {
        StationWork.Work[] work = {new StationWork.Work("Sawmill", 19, null)};
        StationWork.install((accessor, ref) -> work[0]);
        Condition longSession = decode("{ \"Id\": \"ZigWorkingAt\", \"Stations\": [ \"Sawmill\" ], \"CyclesMin\": 20 }");
        assertFalse(longSession.eval(null, REF, Instant.EPOCH));
        work[0] = new StationWork.Work("Sawmill", 20, null);
        assertTrue(longSession.eval(null, REF, Instant.EPOCH), "from the named cycle on");

        ZigWorkingAtCondition highLevel = (ZigWorkingAtCondition) decode(
                "{ \"Id\": \"ZigWorkingAt\", \"ItemLevelMin\": 40 }");
        assertFalse(highLevel.eval(null, REF, Instant.EPOCH), "a piece knob fails with no piece");
        assertTrue(highLevel.pieceHolds(piece(40.0, 1.0, 100.0, 0.0)));
        assertFalse(highLevel.pieceHolds(piece(39.0, 1.0, 100.0, 0.0)));

        ZigWorkingAtCondition fine = (ZigWorkingAtCondition) decode("{ \"Id\": \"ZigWorkingAt\", \"QualityMin\": 2 }");
        assertTrue(fine.pieceHolds(piece(1.0, 2.0, 100.0, 0.0)));
        assertFalse(fine.pieceHolds(piece(1.0, 1.0, 100.0, 0.0)));

        ZigWorkingAtCondition worn = (ZigWorkingAtCondition) decode(
                "{ \"Id\": \"ZigWorkingAt\", \"DurabilityBelow\": 50 }");
        assertTrue(worn.pieceHolds(piece(1.0, 1.0, 49.0, 0.0)));
        assertFalse(worn.pieceHolds(piece(1.0, 1.0, 50.0, 0.0)), "below, never at");

        ZigWorkingAtCondition stamped = (ZigWorkingAtCondition) decode("{ \"Id\": \"ZigWorkingAt\", \"Stamped\": true }");
        ZigWorkingAtCondition plain = (ZigWorkingAtCondition) decode("{ \"Id\": \"ZigWorkingAt\", \"Stamped\": false }");
        assertTrue(stamped.pieceHolds(piece(1.0, 1.0, 100.0, 3.0)));
        assertFalse(stamped.pieceHolds(piece(1.0, 1.0, 100.0, 0.0)));
        assertTrue(plain.pieceHolds(piece(1.0, 1.0, 100.0, 0.0)));
        assertFalse(plain.pieceHolds(StationWork.Piece.NONE), "a reading the piece lacks fails either way");
    }

    @Test
    void theStationReadings_parseTheirParam_andLeaveAnInvertedConditionOut() throws IOException {
        StationStatReading.Param param = StationStatReading.parse(" StationYield_Crafting @ Sawmill ");
        assertEquals(new StationStatReading.Param("StationYield_Crafting", "Sawmill"), param);
        assertNull(StationStatReading.parse("StationYield_Crafting"));
        assertNull(StationStatReading.parse("A@B@C"));
        assertNull(StationStatReading.parse("@Sawmill"));

        ZigWorkingAtCondition tables = ZigWorkingAtCondition.at("Disenchanting_Table", "Disenchanting_Table_Greater");
        ZigWorkingAtCondition any = ZigWorkingAtCondition.at();
        assertTrue(StationStatReading.countsAt(tables, "disenchanting_table_greater"));
        assertFalse(StationStatReading.countsAt(tables, "Sawmill"));
        assertTrue(StationStatReading.boundElsewhere(tables, "Sawmill"), "a table's share is another station's");
        assertTrue(StationStatReading.countsAt(any, "Sawmill"));
        assertFalse(StationStatReading.boundElsewhere(any, "Sawmill"), "a share at any station counts everywhere");

        Condition longSession = decode("{ \"Id\": \"ZigWorkingAt\", \"Stations\": [ \"Sawmill\" ], \"CyclesMin\": 20 }");
        assertTrue(StationStatReading.countsAway(longSession, "Sawmill", null),
                "away from the work the knobs are taken as holding");
        assertFalse(StationStatReading.countsAway(longSession, "Sawmill", "sawmill"),
                "at the work the holder pass read them live, so an off share stays off");
        assertFalse(StationStatReading.countsAway(tables, "Disenchanting_Table", "Disenchanting_Table_Greater"),
                "at any station the condition names, whichever the reading names");
        assertTrue(StationStatReading.countsAway(tables, "Disenchanting_Table", "Sawmill"));

        Condition inverted = decode("{ \"Id\": \"ZigWorkingAt\", \"Stations\": [ \"Sawmill\" ], \"Inverse\": true }");
        assertFalse(StationStatReading.countsAt(inverted, "Sawmill"));
        assertFalse(StationStatReading.boundElsewhere(inverted, "Disenchanting_Table"));
        assertFalse(StationStatReading.countsAt(new ZigCrouchingCondition(), "Sawmill"), "only a station condition");
    }

    private static StationWork.Piece piece(double level, double quality, double durability, double stampPoints) {
        return new StationWork.Piece(null, level, quality, durability, stampPoints);
    }

    private static Condition decode(String json) throws IOException {
        return Condition.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }
}
