package com.ziggfreed.common.objectives.book.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * What the milestone track sends: the track row shown, the rung host cleared, one marker appended per rung and
 * placed by its whole {@code Anchor} (vanilla's Memories chest markers), exactly one of its dots shown; for a ladder
 * of one rung, the row hidden and nothing appended. Tagged {@code engine-items}: a {@link UICommandBuilder}'s static
 * init reaches the engine's item codec.
 */
@Tag("engine-items")
class MilestoneTrackPaintTest {

    private record Sent(Map<String, String> sets, List<String> appends, List<String> clears) {
    }

    @Nonnull
    private static Sent sent(@Nonnull UICommandBuilder cmd) {
        Map<String, String> sets = new HashMap<>();
        List<String> appends = new ArrayList<>();
        List<String> clears = new ArrayList<>();
        for (CustomUICommand c : cmd.getCommands()) {
            if (c.type == CustomUICommandType.Append) {
                appends.add(c.selector + " <- " + c.text);
            } else if (c.type == CustomUICommandType.Clear) {
                clears.add(c.selector);
            } else {
                sets.put(c.selector, c.data);
            }
        }
        return new Sent(sets, appends, clears);
    }

    @Test
    void eachRungIsAppendedAndPlacedByItsWholeAnchor() {
        UICommandBuilder cmd = new UICommandBuilder();
        boolean shows = MilestoneTrack.paint(cmd, "#T", "#R", List.of(100, 250, 500, 1000), 300, 500);
        Sent s = sent(cmd);

        assertTrue(shows);
        assertTrue(s.sets().get("#T.Visible").contains("true"));
        assertEquals(List.of("#R"), s.clears());
        assertEquals(4, s.appends().size());
        assertTrue(s.appends().get(0).equals("#R <- " + MilestoneTrack.TEMPLATE));
        String anchor = s.sets().get("#R[1].Anchor");
        assertNotNull(anchor, "the marker's whole Anchor is sent");
        assertTrue(Pattern.compile("\"Left\"\\s*:\\s*105").matcher(anchor).find(), anchor);
        assertTrue(Pattern.compile("\"Width\"\\s*:\\s*" + MilestoneTrack.MARKER).matcher(anchor).find(), anchor);
        assertTrue(s.sets().get("#R[0] #Reached.Visible").contains("true"));
        assertTrue(s.sets().get("#R[2] #Next.Visible").contains("true"));
        assertTrue(s.sets().get("#R[3] #Ahead.Visible").contains("true"));
        assertFalse(s.sets().get("#R[3] #Reached.Visible").contains("true"));
    }

    @Test
    void aLadderOfOneRungHidesTheTrack() {
        UICommandBuilder cmd = new UICommandBuilder();
        boolean shows = MilestoneTrack.paint(cmd, "#T", "#R", List.of(100), 30, 100);
        Sent s = sent(cmd);

        assertFalse(shows);
        assertTrue(s.sets().get("#T.Visible").contains("false"));
        assertEquals(List.of("#R"), s.clears(), "a repaint clears what an earlier ladder left");
        assertTrue(s.appends().isEmpty());
    }
}
