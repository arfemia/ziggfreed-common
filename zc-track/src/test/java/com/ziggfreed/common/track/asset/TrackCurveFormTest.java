package com.ziggfreed.common.track.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The Level Curves form's writer: a change to the formula writes the curve's {@code Pieces} whole in the number types
 * the curve codec reads (a Hybrid number a decimal, a {@code To} and a floor whole), every piece but the last as it
 * reads; a Reset puts the number without the owner's word back. Pure: {@link TrackCurveForm#pieces} and
 * {@link TrackCurveForm#written} over curves built in code.
 */
class TrackCurveFormTest {

    @Test
    void aFormulaChangeWritesThePiecesWholeInTheCodecsNumberTypes() {
        TrackCurveAsset inForce = TrackCurveAsset.of("Your_Curve", new TrackCurveAsset.Piece[] {
                TrackCurveAsset.Piece.floors(0, 100, 250), TrackCurveAsset.Piece.hybrid(300, 200, 200)}, null, null);
        List<TrackCurveAsset.Piece> next = TrackCurveForm.pieces(inForce, inForce, true, 400.0, false, null);
        assertNotNull(next, "a curve ending in a Hybrid piece has a formula to change");
        List<Object> written = TrackCurveForm.written(next);
        assertEquals(2, written.size(), "every piece is written: " + written);
        Map<?, ?> floors = (Map<?, ?>) written.get(0);
        assertEquals(List.of(0L, 100L, 250L), floors.get("Floors"), "a floor is a whole number");
        Map<?, ?> last = (Map<?, ?>) written.get(1);
        Map<?, ?> hybrid = (Map<?, ?>) last.get("Hybrid");
        assertEquals(Double.valueOf(400.0), hybrid.get("Base"), "the changed number is a decimal");
        assertEquals(Double.valueOf(200.0), hybrid.get("Scale"), "the kept number is a decimal");
        assertEquals(Integer.valueOf(200), last.get("To"), "the top level is a whole number, kept as it reads");
    }

    @Test
    void aResetPutsTheNumberWithoutTheOwnersWordBack() {
        TrackCurveAsset below = TrackCurveAsset.of("Your_Curve",
                new TrackCurveAsset.Piece[] {TrackCurveAsset.Piece.hybrid(300, 200, 200)}, null, null);
        TrackCurveAsset inForce = TrackCurveAsset.of("Your_Curve",
                new TrackCurveAsset.Piece[] {TrackCurveAsset.Piece.hybrid(500, 200, 200)}, null, null);
        List<TrackCurveAsset.Piece> next = TrackCurveForm.pieces(inForce, below, true, null, false, null);
        assertNotNull(next);
        assertTrue(TrackCurveForm.samePieces(next, below.pieces()), "the curve reads as without the owner's entry");
    }

    @Test
    void aCurveEndingInFloorsHasNoFormulaToChange() {
        TrackCurveAsset floors = TrackCurveAsset.of("Your_Curve",
                new TrackCurveAsset.Piece[] {TrackCurveAsset.Piece.floors(0, 100, 250)}, 600L, null);
        assertNull(TrackCurveForm.pieces(floors, floors, true, 400.0, false, null));
    }

    @Test
    void paceBandsReadFromWordsAndWriteBackAsWords() {
        Map<String, Object> bands = TrackCurveForm.paceBands(List.of("1=16000", " 10 = 22000", "0=5", "x=9",
                "40=-3", "55=0"));
        assertEquals(Map.of("1", 16000.0, "10", 22000.0, "55", 0.0), bands,
                "a band of 0 is kept (it takes an inherited band out); a bad level or amount is passed over");
        assertEquals(List.of("1=16000", "10=22000", "55=0"),
                TrackCurveForm.paceWords(Map.of("10", 22000.0, "1", 16000.0, "55", 0.0, "x", 5.0)),
                "words by level, a take-out's 0 kept so the next save keeps it, a key that is no level left out");
    }

    @Test
    void onlyBandsThatDifferFromThePacksAreWritten() {
        Map<String, Double> below = Map.of("1", 16000.0, "10", 22000.0);
        Map<String, Object> typed = TrackCurveForm.paceBands(List.of("1=16000", "10=25000", "20=0", "30=40000"));
        assertEquals(Map.of("10", 25000.0, "30", 40000.0), TrackCurveForm.changedBands(typed, below),
                "a band as the packs have it is not written, nor a 0 taking out a band the packs lack");
        assertEquals(Map.of("10", 0.0), TrackCurveForm.changedBands(Map.of("10", 0.0), below),
                "a 0 taking out a band the packs ship is written");
    }
}
