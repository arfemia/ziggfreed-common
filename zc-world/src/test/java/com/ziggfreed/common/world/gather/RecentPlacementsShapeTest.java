package com.ziggfreed.common.world.gather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The shaped placement's two halves, which the thrift step pays apart ({@code BuildThrift_Straight} on the run,
 * {@code BuildThrift_Flow} in the flow after it, {@code BuildThrift_Shaped} on either): a placement continuing a
 * straight run of three is straight, one within the flow of the last straight placement is flowing, the flow is
 * counted from the straight placement before this one, and it ends at its window. Pure over one memory and a clock.
 */
class RecentPlacementsShapeTest {

    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long FLOW_MS = 10_000L;

    @Test
    void aStraightRunAndTheFlowAfterItAreToldApart() {
        RecentPlacements.Memory memory = new RecentPlacements.Memory();
        assertEquals(RecentPlacements.Shape.NONE, memory.place(WORLD, 0, 64, 0, 0L, FLOW_MS), "a first block");
        assertEquals(RecentPlacements.Shape.NONE, memory.place(WORLD, 1, 64, 0, 100L, FLOW_MS), "two make no run");
        assertEquals(new RecentPlacements.Shape(true, false), memory.place(WORLD, 2, 64, 0, 200L, FLOW_MS),
                "the third in line is straight, and no straight placement came before it");
        assertEquals(new RecentPlacements.Shape(true, true), memory.place(WORLD, 3, 64, 0, 300L, FLOW_MS),
                "a fourth in line is straight and in the flow of the third");
        assertEquals(new RecentPlacements.Shape(false, true), memory.place(WORLD, 3, 65, 5, 2_000L, FLOW_MS),
                "a block off the line soon after is in the flow alone");
        assertEquals(RecentPlacements.Shape.NONE, memory.place(WORLD, 9, 70, 9, 10_301L, FLOW_MS),
                "past the window after the last straight placement, neither half holds");
        assertTrue(new RecentPlacements.Shape(true, false).shaped() && new RecentPlacements.Shape(false, true).shaped()
                && !RecentPlacements.Shape.NONE.shaped(), "a shaped placement is either half");
    }
}
