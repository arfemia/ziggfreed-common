package com.ziggfreed.common.objectives.book.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.book.achievement.MilestoneTrack.Rung;
import com.ziggfreed.common.objectives.book.achievement.MilestoneTrack.State;

/**
 * Where the milestone card's rung markers sit: each at its threshold's share of the bar (the ladder's top at the
 * bar's end), centred on that point and kept inside the bar, lit once reached, the rung being worked toward marked,
 * the rest ahead; the bar itself on the ladder's scale while the track shows; and the numbers held to the document
 * the markers are placed in.
 */
class MilestoneTrackTest {

    private static final Path PAGES = Path.of("src", "main", "resources", "Common", "UI", "Custom", "Pages");

    private static List<Integer> lefts(List<Rung> rungs) {
        List<Integer> out = new ArrayList<>();
        for (Rung rung : rungs) {
            out.add(rung.left());
        }
        return out;
    }

    private static List<State> states(List<Rung> rungs) {
        List<State> out = new ArrayList<>();
        for (Rung rung : rungs) {
            out.add(rung.state());
        }
        return out;
    }

    @Test
    void eachMarkerSitsAtItsThresholdsShareOfTheBar() {
        List<Rung> rungs = MilestoneTrack.rungs(List.of(100, 250, 500, 1000), 0, 100, 446);
        // centre = threshold * 446 / 1000, left = centre - 6, kept inside 0 .. 446 - 12
        assertEquals(List.of(38, 105, 217, 434), lefts(rungs));
        assertEquals(List.of(100, 250, 500, 1000), rungs.stream().map(Rung::threshold).toList());
    }

    @Test
    void aMarkerNeverLeavesTheBar() {
        List<Rung> rungs = MilestoneTrack.rungs(List.of(1, 1000), 0, 1, 446);
        assertEquals(0, rungs.get(0).left(), "a rung near zero starts at the bar's left edge");
        assertEquals(446 - MilestoneTrack.MARKER, rungs.get(1).left(), "the top rung ends at the bar's right edge");
    }

    @Test
    void reachedRungsLightTheNextIsMarkedTheRestWait() {
        List<Rung> rungs = MilestoneTrack.rungs(List.of(100, 250, 500, 1000), 300, 500, 446);
        assertEquals(List.of(State.REACHED, State.REACHED, State.NEXT, State.AHEAD), states(rungs));
    }

    @Test
    void aReachedRungStillUncollectedReadsReached() {
        List<Rung> rungs = MilestoneTrack.rungs(List.of(100, 250), 260, 250, 446);
        assertEquals(List.of(State.REACHED, State.REACHED), states(rungs), "Collect on the card says the rest");
    }

    @Test
    void everyRungCollectedHasNoNext() {
        List<Rung> rungs = MilestoneTrack.rungs(List.of(100, 250), 900, -1, 446);
        assertEquals(List.of(State.REACHED, State.REACHED), states(rungs));
    }

    @Test
    void unsortedOrInvalidThresholdsAreSortedAndDropped() {
        List<Rung> rungs = MilestoneTrack.rungs(List.of(500, 0, 100, -5), 0, 100, 446);
        assertEquals(List.of(100, 500), rungs.stream().map(Rung::threshold).toList());
        assertTrue(MilestoneTrack.rungs(List.of(), 0, 0, 446).isEmpty());
        assertTrue(MilestoneTrack.rungs(null, 0, 0, 446).isEmpty());
    }

    @Test
    void theTrackShowsOnlyForALadderOfTwoOrMore() {
        assertTrue(MilestoneTrack.shows(List.of(100, 250)));
        assertTrue(!MilestoneTrack.shows(List.of(100)), "one rung is the bar itself");
        assertTrue(!MilestoneTrack.shows(List.of()));
    }

    @Test
    void theBarReadsOnTheLaddersScaleWhileTheTrackShows() {
        assertEquals(0.3f, MilestoneTrack.barFraction(List.of(100, 250, 500, 1000), 300), 1e-6f);
        assertEquals(1f, MilestoneTrack.barFraction(List.of(100, 250), 900), 1e-6f, "past the top reads full");
        assertEquals(0f, MilestoneTrack.barFraction(List.of(100, 250), -4), 1e-6f);
    }

    @Test
    void theNumbersAreTheDocumentsOwn() throws IOException {
        String ui = Files.readString(PAGES.resolve("ZigBookAchievements.ui"), StandardCharsets.UTF_8);
        // The bar row: the card's inner width (card less its padding both sides) less the count label's width.
        Matcher count = Pattern.compile("Label #MsCount \\{\\s*Anchor: \\(Width: (\\d+)\\);").matcher(ui);
        assertTrue(count.find(), "the count label has a fixed width");
        int padding = 12; // $ZK.@ZigSpace3
        assertTrue(ui.contains("Padding: (Full: $ZK.@ZigSpace3);"), "the card's padding is ZigSpace3 (12)");
        assertEquals(AchievementsTab.MILESTONE_WIDTH - 2 * padding - Integer.parseInt(count.group(1)),
                MilestoneTrack.WIDTH, "the markers' width is the bar's drawn width");
        Matcher spacer = Pattern.compile("Group #RungsEnd \\{\\s*Anchor: \\(Width: (\\d+)\\);").matcher(ui);
        assertTrue(spacer.find(), "the track row ends in the count label's width, so the rungs span the bar");
        assertEquals(count.group(1), spacer.group(1));

        String rung = Files.readString(PAGES.resolve("ZigMilestoneRung.ui"), StandardCharsets.UTF_8);
        assertTrue(rung.contains("Group #ZigMilestoneRung {"), "the template's root is named after its file");
        for (String part : List.of("#Reached", "#Next", "#Ahead")) {
            assertTrue(rung.contains("Group " + part + " {"), "the marker holds " + part);
        }
        assertTrue(rung.contains("Width: " + MilestoneTrack.MARKER) && rung.contains("Height: " + MilestoneTrack.MARKER),
                "the marker's authored size is the one Java places");
    }
}
