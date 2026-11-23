package com.ziggfreed.common.loot.diminish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * A rolling window counts only the clears inside its last span, each falling out as its own time passes, and a key
 * keeps its own count: the mechanics a track source's rolling diminish stands on.
 */
class DiminishLedgerRollingTest {

    private static final long MINUTE = 60_000L;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    void clearsInsideTheSpanStepDownAndFallOutAsTheirTimePasses() {
        DiminishLedger ledger = new DiminishLedger("rolling-test");
        DiminishSpec spec = DiminishSpec.rolling(10 * MINUTE, 1.0, 0.5, 0.25);
        long t0 = 1_000_000_000L;
        assertEquals(1.0, ledger.count(PLAYER, "k", spec, t0));
        assertEquals(0.5, ledger.count(PLAYER, "k", spec, t0 + MINUTE));
        assertEquals(0.25, ledger.count(PLAYER, "k", spec, t0 + 2 * MINUTE));
        assertEquals(0.25, ledger.count(PLAYER, "k", spec, t0 + 3 * MINUTE), "past the last step pays the last");
        // At t0 + 10m the first clear has left the span; three are still inside it.
        assertEquals(0.25, ledger.next(PLAYER, "k", spec, t0 + 10 * MINUTE));
        // At t0 + 12m30s every clear but the one at t0 + 3m has left.
        assertEquals(0.5, ledger.next(PLAYER, "k", spec, t0 + 12 * MINUTE + 30_000L));
        // Long after, the count is gone.
        assertEquals(1.0, ledger.next(PLAYER, "k", spec, t0 + 60 * MINUTE));
    }

    @Test
    void eachKeyKeepsItsOwnCount() {
        DiminishLedger ledger = new DiminishLedger("rolling-keys");
        DiminishSpec spec = DiminishSpec.rolling(10 * MINUTE, 1.0, 0.0);
        long t0 = 2_000_000_000L;
        assertEquals(1.0, ledger.count(PLAYER, "spot:a", spec, t0));
        assertEquals(0.0, ledger.count(PLAYER, "spot:a", spec, t0 + 1));
        assertEquals(1.0, ledger.count(PLAYER, "spot:b", spec, t0 + 2), "another key starts its own count");
    }

    @Test
    void aCountUnderOneWindowReadsAsNoneUnderAnother() {
        DiminishLedger ledger = new DiminishLedger("rolling-words");
        long t0 = 3_000_000_000L;
        ledger.count(PLAYER, "k", DiminishSpec.rolling(10 * MINUTE, 1.0, 0.0), t0);
        assertEquals(1.0, ledger.next(PLAYER, "k", DiminishSpec.rolling(20 * MINUTE, 1.0, 0.0), t0 + 1));
        assertEquals(1.0, ledger.next(PLAYER, "k", DiminishSpec.of(DiminishSpec.Window.DAILY, 1.0, 0.0), t0 + 1));
    }

    @Test
    void onlyAsManyTimesAsStepsAreKept() {
        long[] kept = DiminishLedger.withClear(new long[] {1, 2, 3, 4}, 5, 100, 3);
        assertEquals(3, kept.length);
        assertEquals(3, kept[0]);
        assertEquals(5, kept[2]);
        assertEquals(1, DiminishLedger.withClear(new long[] {1}, 500, 100, 3).length, "a time past the span drops");
    }
}
