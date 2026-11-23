package com.ziggfreed.common.track.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * What is left of a suggested build (R727): the ranks a player holds stand for each pick's first takes, and the read
 * stops at the first take past the build's first N points, held takes counted, so spending what is left never spends
 * past N and spending it again spends nothing.
 */
class StarterLeftTest {

    private static final List<String> BUILD = List.of("Root", "Edge", "Edge", "Edge", "Free_Note", "Guard", "Guard");

    private static final Map<String, Integer> COST = Map.of("root", 1, "edge", 1, "free_note", 0, "guard", 2);

    private static List<String> left(Map<String, Integer> held, int maxPoints) {
        return PickService.starterLeft(BUILD, id -> held.getOrDefault(id, 0), id -> COST.getOrDefault(id, 0),
                maxPoints);
    }

    @Test
    void heldRanksStandForTheFirstTakes() {
        assertEquals(List.of("Edge", "Edge", "Free_Note", "Guard", "Guard"),
                left(Map.of("root", 1, "edge", 1), Integer.MAX_VALUE), "a build half made is finished from there");
        assertEquals(List.of(), left(Map.of("root", 1, "edge", 3, "free_note", 1, "guard", 2), Integer.MAX_VALUE),
                "a build held whole leaves nothing");
    }

    @Test
    void theReadStopsAtTheBuildsFirstPoints() {
        assertEquals(List.of("Root", "Edge", "Edge"), left(Map.of(), 3), "three points: the first three takes");
        assertEquals(List.of("Root", "Edge", "Edge", "Edge", "Free_Note"), left(Map.of(), 5),
                "a free take inside the points comes along, and a take past them stops the read");
        assertEquals(List.of("Edge", "Edge"), left(Map.of("root", 1), 3), "held takes count toward the points");
        assertEquals(List.of(), left(Map.of("root", 1, "edge", 2), 3), "spent that far: nothing more to spend");
        assertEquals(List.of(), left(Map.of(), 0), "no points, nothing");
    }
}
