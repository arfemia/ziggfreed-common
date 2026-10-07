package com.ziggfreed.common.objectives.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBinding;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Progress;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * What the pinned-and-tracked panel sends: its host cleared, one kit section per side holding compact rows, each row
 * bound with its kind and id; nothing at all, and no throw, when the runtime cannot be read. Tagged
 * {@code engine-items}: a {@link UICommandBuilder}'s static init reaches the engine's item codec.
 */
@Tag("engine-items")
class ObjectivePanelsPaintTest {

    private static LedgerRow row(@Nonnull String id) {
        return new LedgerRow(id, Message.raw(id), null, Picture.NONE, Tone.ACTIVE, Message.raw("In progress"), null,
                new Progress(1, 4), Mark.NONE, false);
    }

    @Test
    void eachSideIsASectionOfCompactRowsBoundByKindAndId() {
        List<LedgerSection> sections = List.of(
                new LedgerSection(ObjectivePanels.PINNED, Message.raw("Pinned"), List.of(row("a_one")), true),
                new LedgerSection(ObjectivePanels.TRACKED, Message.raw("Tracked"), List.of(row("q_one"), row("q_two")),
                        true));
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        int painted = ObjectivePanels.paint(cmd, events, "#Panel", sections, null,
                (kind, id) -> EventData.of("Action", "open").append("Kind", kind).append("Id", id));

        assertEquals(3, painted);
        List<String> clears = new ArrayList<>();
        List<String> appends = new ArrayList<>();
        for (CustomUICommand c : cmd.getCommands()) {
            if (c.type == CustomUICommandType.Clear) {
                clears.add(c.selector);
            } else if (c.type == CustomUICommandType.Append) {
                appends.add(c.selector + " <- " + c.text);
            }
        }
        assertEquals(List.of("#Panel"), clears);
        String compact = RowSize.COMPACT.template();
        assertEquals(List.of(
                "#Panel <- " + LedgerPainter.SECTION_TEMPLATE, "#Panel[0] #Rows <- " + compact,
                "#Panel <- " + LedgerPainter.SECTION_TEMPLATE, "#Panel[1] #Rows <- " + compact,
                "#Panel[1] #Rows <- " + compact), appends);

        List<String> bound = new ArrayList<>();
        for (CustomUIEventBinding b : events.getEvents()) {
            bound.add(b.selector + " " + b.data);
        }
        assertEquals(3, bound.size(), "every row is bound, no head folds: " + bound);
        assertTrue(bound.get(0).startsWith("#Panel[0] #Rows[0] #Select ") && bound.get(0).contains("\"achievement\"")
                && bound.get(0).contains("\"a_one\""), bound.get(0));
        assertTrue(bound.get(2).startsWith("#Panel[1] #Rows[1] #Select ") && bound.get(2).contains("\"quest\"")
                && bound.get(2).contains("\"q_two\""), bound.get(2));
    }

    @Test
    void aBindingThatThrowsLeavesItsRowUnboundAndThePanelPainted() {
        List<LedgerSection> sections = List.of(
                new LedgerSection(ObjectivePanels.PINNED, Message.raw("Pinned"), List.of(row("a_one")), true));
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        int painted = ObjectivePanels.paint(cmd, events, "#Panel", sections, null, (kind, id) -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(1, painted);
        assertEquals(0, events.getEvents().length);
    }

    @Test
    void anUnreadableRuntimeSendsNothingAndNeverThrows() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();

        int painted = ObjectivePanels.paintPinnedAndTracked(cmd, events, "#Panel", null, null, null, 5,
                (kind, id) -> EventData.of("Id", id));

        assertEquals(0, painted);
        assertEquals(0, cmd.getCommands().length, "no host is touched when nothing could be read");
        assertEquals(0, events.getEvents().length);
    }

    @Test
    void nothingPinnedOrTrackedClearsTheHost() {
        UICommandBuilder cmd = new UICommandBuilder();
        int painted = ObjectivePanels.paint(cmd, new UIEventBuilder(), "#Panel", List.of(), null,
                (kind, id) -> EventData.of("Id", id));
        assertEquals(0, painted);
        assertEquals(1, cmd.getCommands().length);
        assertEquals(CustomUICommandType.Clear, cmd.getCommands()[0].type);
    }
}
