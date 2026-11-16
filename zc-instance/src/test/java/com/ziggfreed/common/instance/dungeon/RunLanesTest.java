package com.ziggfreed.common.instance.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.dungeon.layout.Cell;

/**
 * A generated dungeon's lanes and run regions (the pure math in {@link RunLanes} and the router's run counting): the
 * floors keep a region out of sight of the next, the cap keeps the furthest region where a float still holds, and a
 * lane that has served its runs takes no party again. Untagged: nothing here builds an engine item.
 */
class RunLanesTest {

    private static final Cell BOUNDS = new Cell(512, 128, 512);

    @Test
    void theStrideAndSpacingAreFlooredSoNoRegionSeesTheNext() {
        assertEquals(2048, RunLanes.runStride(0, 512), "unauthored is the default, above the floor");
        assertEquals(512 + RunLanes.VIEW_MARGIN, RunLanes.runStride(1000, 512), "a short stride is raised to the floor");
        assertEquals(1200 + RunLanes.VIEW_MARGIN, RunLanes.runStride(2048, 1200), "a deep plan raises the default");
        assertEquals(2048, RunLanes.spacing(2048, 512));
        assertEquals(1200 + RunLanes.VIEW_MARGIN, RunLanes.spacing(2048, 1200), "a wide plan spreads the lanes");

        int stride = RunLanes.runStride(0, BOUNDS.z());
        int spacing = RunLanes.spacing(DungeonAsset.DEFAULT_SPACING, BOUNDS.x());
        Cell first = RunLanes.frameOrigin(0, 0, spacing, stride, BOUNDS);
        Cell second = RunLanes.frameOrigin(0, 1, spacing, stride, BOUNDS);
        assertTrue(second.z() - (first.z() + BOUNDS.z() - 1) > RunLanes.VIEW_MARGIN - 1,
                "two runs of a lane leave a view's width between their regions");
        Cell otherLane = RunLanes.frameOrigin(1, 0, spacing, stride, BOUNDS);
        assertTrue(otherLane.x() - (first.x() + BOUNDS.x() - 1) > RunLanes.VIEW_MARGIN - 1,
                "two lanes leave a view's width between their regions");
        assertTrue(first.z() > 32, "the first run stands clear of the origin chunk a fresh world loads");
        assertEquals(AreaLayout.AREA_Y, first.y());
        Cell centre = RunLanes.regionCentre(0, 0, spacing, stride);
        assertEquals(new Cell(centre.x() - 256, centre.y(), centre.z() - 256), first, "a frame starts half the plan before its centre");
    }

    @Test
    void theRunsALaneServesAreCappedWhereAFloatStillHolds() {
        int stride = RunLanes.runStride(0, BOUNDS.z());
        assertEquals(RunLanes.DEFAULT_MAX_RUNS_PER_LANE, RunLanes.maxRuns(0, stride, BOUNDS.z()));
        int cap = RunLanes.maxRuns(10_000, stride, BOUNDS.z());
        Cell last = RunLanes.frameOrigin(0, cap - 1, 2048, stride, BOUNDS);
        assertTrue(last.z() + BOUNDS.z() - 1 < RunLanes.FAR_LIMIT, "the furthest region ends inside the limit");
        Cell beyond = RunLanes.frameOrigin(0, cap, 2048, stride, BOUNDS);
        assertFalse(beyond.z() + BOUNDS.z() - 1 < RunLanes.FAR_LIMIT, "one more would pass it");
        assertEquals(1, RunLanes.maxRuns(5, RunLanes.FAR_LIMIT * 2, BOUNDS.z()), "a lane always serves one run");
    }

    @Test
    void theLanesACopyHoldsAreCappedLikeItsRunsAndATallPlanIsCaught() {
        int spacing = RunLanes.spacing(DungeonAsset.DEFAULT_SPACING, BOUNDS.x());
        assertEquals(4, RunLanes.lanes(4, spacing, BOUNDS.x()), "a few lanes fit as asked");
        int cap = RunLanes.lanes(10_000, spacing, BOUNDS.x());
        Cell last = RunLanes.frameOrigin(cap - 1, 0, spacing, 2048, BOUNDS);
        assertTrue(last.x() + BOUNDS.x() - 1 < RunLanes.FAR_LIMIT, "the furthest lane ends inside the limit");
        Cell beyond = RunLanes.frameOrigin(cap, 0, spacing, 2048, BOUNDS);
        assertFalse(beyond.x() + BOUNDS.x() - 1 < RunLanes.FAR_LIMIT, "one more lane would pass it");
        assertEquals(1, RunLanes.lanes(0, RunLanes.FAR_LIMIT * 2, BOUNDS.x()), "a copy always holds one lane");

        assertTrue(RunLanes.fitsHeight(256), "rows 64 to 319 fit");
        assertFalse(RunLanes.fitsHeight(257), "one row more passes the world's top");
    }

    @Test
    void aGeneratedCopyLaysItsAreasOutAsLanesAlongX() {
        AreaLayout lanes = new AreaLayout("crypt", 4, 2048, 0, 0, 0, 0f, 2048);
        assertTrue(lanes.generated());
        assertEquals(3 * 2048, lanes.origin(3).x, "lane 3's line is three spacings along X");
        assertEquals(0, lanes.origin(3).z);
        assertTrue(lanes.inCell(1, 2048.5, 40_000), "a lane's cell runs the whole way along Z");
        assertFalse(lanes.inCell(1, 2048.5 + 1100, 0), "and stops half a spacing either side of its line");
        AreaLayout pasted = new AreaLayout("crypt", 4, 2048, 0, 0, 0, 0f);
        assertFalse(pasted.generated(), "the old form is a pasted dungeon's grid");
        assertEquals(2048, pasted.origin(3).z, "whose areas stand two to a row");
    }

    @Test
    void aLaneCountsItsRunsAndIsSpentAfterItsLast() {
        CopyRouter router = new CopyRouter();
        Probe probe = new Probe();
        CopyRouter.Rules rules = new CopyRouter.Rules("crypt", 1, 5, true, 60_000L, 2);

        UUID a1 = UUID.randomUUID();
        CopyRouter.Placement first = router.reserve("party:a", a1, rules, 0L, probe);
        assertEquals(CopyRouter.Kind.OPENED, first.kind());
        assertEquals(0, first.run(), "the first party a lane serves runs run 0");
        probe.live.add(first.area().world());
        router.arrived(a1);
        UUID a2 = UUID.randomUUID();
        CopyRouter.Placement follower = router.reserve("party:a", a2, rules, 0L, probe);
        assertEquals(CopyRouter.Kind.HELD, follower.kind());
        assertEquals(0, follower.run(), "a member following in joins the run under way");
        router.left(a1, 1L);
        router.left(a2, 1L);

        UUID b1 = UUID.randomUUID();
        CopyRouter.Placement second = router.reserve("party:b", b1, rules, 2L, probe);
        assertEquals(CopyRouter.Kind.FILLED, second.kind());
        assertEquals(first.area(), second.area(), "the freed lane takes the next party");
        assertEquals(1, second.run(), "on fresh ground: the lane's next run");
        router.arrived(b1);
        router.left(b1, 3L);

        UUID c1 = UUID.randomUUID();
        CopyRouter.Placement third = router.reserve("party:c", c1, rules, 4L, probe);
        assertEquals(CopyRouter.Kind.OPENED, third.kind(), "a spent lane takes no party, so a fresh copy opens");
        assertEquals(0, third.run());

        CopyRouter.Placement unlimited = new CopyRouter().reserve("party:d", UUID.randomUUID(),
                new CopyRouter.Rules("crypt", 1, 5, true, 60_000L), 0L, probe);
        assertEquals(0, unlimited.run(), "a pasted dungeon's rules set no limit");
    }

    /** Every copy the test opened is live and held; no tick time is known. */
    private static final class Probe implements CopyRouter.WorldProbe {
        final Set<String> live = new HashSet<>();

        @Override
        public boolean isLive(@Nonnull String world) {
            return live.contains(world);
        }

        @Override
        public boolean isRegistered(@Nonnull String world) {
            return live.contains(world);
        }

        @Override
        public double tickAverageMs(@Nonnull String world) {
            return Double.NaN;
        }
    }
}
