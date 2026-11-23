package com.ziggfreed.common.track.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * A source row's {@code Level} is a soft gate (R609): below it the next matching row pays, nothing is refused, and a
 * table whose rows wait for no level never reads the track.
 */
class SoftGateTest {

    private static TrackSources.Paid row(String pattern, double xp, Integer level) {
        return new TrackSources.Paid("src", "mining", "tracksource:src", SourceRow.of(xp, null, level), null, null,
                false);
    }

    @Test
    void belowARowsLevelTheNextMatchingRowPays() {
        TrackSources.Paid iron = row("Ore_Iron*", 50, 10);
        TrackSources.Paid ore = row("Ore_*", 30, null);
        List<TrackSources.Paid> ladder = List.of(iron, ore);
        assertSame(ore, TrackSources.reached(ladder, () -> 9), "at level 9 the broader row pays");
        assertSame(iron, TrackSources.reached(ladder, () -> 10), "from level 10 the row's own rate pays");
    }

    @Test
    void aLadderOfGatedRowsAllAboveTheLevelPaysNothing() {
        List<TrackSources.Paid> ladder = List.of(row("Ore_Mithril*", 460, 70), row("Ore_Adamantite*", 290, 55));
        assertNull(TrackSources.reached(ladder, () -> 20));
    }

    @Test
    void theTrackIsReadOnceAndOnlyWhenARowWaits() {
        AtomicInteger reads = new AtomicInteger();
        List<TrackSources.Paid> ungated = List.of(row("Rock_*", 3, null), row("*", 1, 0));
        TrackSources.reached(ungated, () -> {
            reads.incrementAndGet();
            return 50;
        });
        assertEquals(0, reads.get(), "no row waits, so the track is never read");

        List<TrackSources.Paid> gated = List.of(row("A", 1, 30), row("B", 1, 20), row("C", 1, null));
        TrackSources.reached(gated, () -> {
            reads.incrementAndGet();
            return 10;
        });
        assertEquals(1, reads.get(), "two gated rows ask the level once");
    }
}
