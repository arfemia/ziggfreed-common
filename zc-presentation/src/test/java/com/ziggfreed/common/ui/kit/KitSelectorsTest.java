package com.ziggfreed.common.ui.kit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBinding;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * Every selector a painter sends or binds names only ids its target document declares, because a command against
 * an element the page lacks disconnects the player ("Selected element in CustomUI command was not found"). Each
 * painter runs against recording builders; each selector is then walked the way the client resolves it, under the
 * kit's root convention: the page's own host (a list it owns, or an inline kit template it instantiated), then
 * {@code host[i]} as the root of the template appended there ({@code Pages/*.ui}, read from the recorded appends in
 * order, a clear starting the count again), then each {@code #Id} declared inside the scope before it, an inline
 * template instance ({@code $ZW.@ZigPicture #Pic}) opening that template's body from {@code Common/ZigKit.ui}. A
 * renamed template id or a mistyped painter selector fails here, not on a player's screen. Tagged
 * {@code engine-items}: a {@link UICommandBuilder}'s static init reaches the engine's item codec.
 */
@Tag("engine-items")
class KitSelectorsTest {

    private static final String TEXTURE = "UI/Custom/Pages/Memories/MissingIcon.png";

    /** One selector segment: an id, any indexes, and (on the last) the property path. */
    private static final Pattern SEGMENT = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)((?:\\[\\d+])*)(\\..*)?");

    /** An inline kit template instance inside a scope, whose body the scope then holds too. */
    private static final Pattern INSTANCE =
            Pattern.compile("(?:\\$ZW\\.)?@(Zig[A-Za-z0-9]*)\\s+#[A-Za-z][A-Za-z0-9]*\\s*\\{");

    /** A scope inside a vanilla template ({@code $C.@CircularProgressBar #Ring}): the kit never reaches into one. */
    private static final String VANILLA = "\u0000vanilla";

    @Test
    void theLedgerPainterAddressesOnlyDeclaredIds() throws IOException {
        for (RowSize size : RowSize.values()) {
            Recorder r = new Recorder(Map.of());
            LedgerSection open = new LedgerSection("open", Message.raw("Open"), List.of(full("a", Mark.PINNED),
                    full("b", Mark.TRACKED)), true);
            List<LedgerRow> many = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                many.add(full("m" + i, Mark.NONE));
            }
            LedgerSection capped = new LedgerSection("capped", Message.raw("Capped"), many, false, 5);
            LedgerModel model = new LedgerModel(List.of(open, capped), "a");
            LedgerIndex index = LedgerPainter.paint(r.cmd(), r.events(), "#List", model, Set.of(), "a",
                    ledgerBindings(), size, null);
            r.record();
            LedgerPainter.select(r.cmd(), index, "a", "b", null);
            r.record();
            LedgerPainter.openSection(r.cmd(), r.events(), index, capped, ledgerBindings());
            r.record();
            LedgerPainter.select(r.cmd(), index, "b", "m1", null);
            r.record();
            LedgerPainter.closeSection(r.cmd(), index, "capped");
            r.record();
            r.assertDeclared("LedgerPainter " + size);
        }
    }

    @Test
    void theDetailPainterAddressesOnlyDeclaredIds() throws IOException {
        Recorder r = new Recorder(Map.of("#Page", "@ZigDetailPage"));
        DetailPainter.bindActionsOnce(r.events(), "#Page", slot -> EventData.of("Slot", slot.name()),
                EventData.of("Action", "toggle"));
        DetailPainter.paint(r.cmd(), r.events(), "#Page", fullView(), detailBindings(), null);
        r.record();
        DetailPainter.paint(r.cmd(), r.events(), "#Page", fullView(), detailBindings(), null, false);
        r.record();
        DetailPainter.paint(r.cmd(), r.events(), "#Page", new DetailView(Picture.NONE, Message.raw("Bare"), null,
                null, List.of(), null, null, null, null, List.of(), List.of(), null), detailBindings(), null);
        r.record();
        r.assertDeclared("DetailPainter");
    }

    @Test
    void theTilePaintersAddressOnlyDeclaredIds() throws IOException {
        Recorder r = new Recorder(Map.of());
        TilePainter.collection(r.cmd(), r.events(), "#Grid", List.of(
                new CollectionTile("c", Message.raw("C"), Message.raw("1 / 2"), Picture.texture(TEXTURE),
                        new Progress(1, 2), true, Pill.of(Message.raw("On now"), Tone.LIVE), "#e05a2a", true),
                new CollectionTile("d", Message.raw("D"), null, Picture.NONE, null, false, null, null, false)),
                t -> EventData.of("Category", t.id()));
        TilePainter.keepsakes(r.cmd(), "#Shelf", List.of(
                new KeepsakeTile("1", Message.raw("2026"), Message.raw("Earned"), Picture.texture(TEXTURE),
                        KeepsakeState.EARNED, true, Message.raw("Tip")),
                new KeepsakeTile("2", Message.raw("2025"), Message.raw("Missed"), Picture.NONE,
                        KeepsakeState.MISSED, false, null),
                new KeepsakeTile("3", Message.raw("2027"), Message.raw("To earn"), Picture.NONE,
                        KeepsakeState.TO_EARN, false, null)));
        TilePainter.stats(r.cmd(), "#Stats", List.of(
                new StatTile("s", Picture.texture(TEXTURE), Message.raw("3"), Message.raw("Bombs"),
                        Message.raw("9 in all"), Message.raw("40 here"), false),
                new StatTile("z", Picture.NONE, Message.raw("0"), Message.raw("None"), null, null, true)));
        r.record();
        r.assertDeclared("TilePainter");
    }

    @Test
    void theSmallPaintersAddressOnlyDeclaredIds() throws IOException {
        Recorder r = new Recorder(Map.of("#Stat0", "@ZigStat", "#Chip", "@ZigPill", "#Own", "@ZigPill",
                "#ViewBrowse", "@ZigSegment", "#Empty", "@ZigEmptyState", "#Picture", "@ZigPicture"));
        StatPainter.paint(r.cmd(), "#Stat0", new Stat(Message.raw("830"), Message.raw("Points"), Tone.COLLECT));
        PillPainter.paint(r.cmd(), "#Chip", Pill.of(Message.raw("On now"), Tone.LIVE));
        PillPainter.paint(r.cmd(), "#Own", new Pill(Message.raw("Hallow's Eve"), Tone.LIVE, "#c0582a"));
        SegmentPainter.set(r.cmd(), "#ViewBrowse", Message.raw("Browse"), true, null);
        r.cmd().clear("#Years");
        SegmentPainter.append(r.cmd(), r.events(), "#Years", Message.raw("2026"), true, true, true,
                EventData.of("Year", "2026"));
        SegmentPainter.append(r.cmd(), r.events(), "#Years", Message.raw("All"), false, false, false,
                EventData.of("Year", "all"));
        DetailAction clear = new DetailAction(ActionSlot.PRIMARY, KitText.clearFilters(), ActionLook.NORMAL, "clear",
                null, true, null);
        EmptyStatePainter.paint(r.cmd(), r.events(), "#Empty", new EmptyState(Picture.texture(TEXTURE),
                KitText.nothingMatches(), KitText.nothingMatchesLine(), clear), EventData.of("Action", "clear"));
        KitPaint.picture(r.cmd(), "#Picture", Picture.texture(TEXTURE));
        KitPaint.tooltip(r.cmd(), "#Empty #EAction", Message.raw("Tip"));
        KitPaint.optional(r.cmd(), "#Empty #ELine", null);
        r.record();
        r.assertDeclared("the small painters");
    }

    @Test
    void theAuditCatchesAnUndeclaredId() throws IOException {
        Recorder r = new Recorder(Map.of("#Page", "@ZigDetailPage"));
        r.cmd().set("#Page #DTitel.TextSpans", Message.raw("typo"));
        r.cmd().append("#List", RowSize.STANDARD.template());
        r.cmd().set("#List[0] #Select #Subtitle.Visible", true);
        r.record();
        List<String> failures = r.failures();
        assertTrue(failures.size() == 2, "a typo and an id the row never declares both fail: " + failures);
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Records commands and bindings across several builders (a paint, then its partial updates) and walks every
     * selector against the documents.
     */
    private static final class Recorder {

        private final Map<String, String> inline;
        private final Map<String, List<String>> appended = new HashMap<>();
        private final List<String> failures = new ArrayList<>();
        private UICommandBuilder cmd = new UICommandBuilder();
        private UIEventBuilder events = new UIEventBuilder();
        private final String kit;

        Recorder(@Nonnull Map<String, String> inline) throws IOException {
            this.inline = inline;
            this.kit = KitDocs.document("Common/ZigKit.ui");
        }

        UICommandBuilder cmd() {
            return cmd;
        }

        UIEventBuilder events() {
            return events;
        }

        /** Walk what the current builders hold, in order, and start fresh ones for the next update. */
        void record() throws IOException {
            for (CustomUICommand command : cmd.getCommands()) {
                if (command.type == CustomUICommandType.Clear) {
                    check(command.selector);
                    appended.put(command.selector, new ArrayList<>());
                } else if (command.type == CustomUICommandType.Append) {
                    check(command.selector);
                    appended.computeIfAbsent(command.selector, s -> new ArrayList<>()).add(command.text);
                } else {
                    check(command.selector);
                }
            }
            // A binding targets the document as this update leaves it (a later repaint may clear what it bound).
            for (CustomUIEventBinding binding : events.getEvents()) {
                check(binding.selector);
            }
            cmd = new UICommandBuilder();
            events = new UIEventBuilder();
        }

        List<String> failures() {
            return failures;
        }

        void assertDeclared(@Nonnull String what) {
            List<String> all = failures();
            assertTrue(all.isEmpty(), what + " addresses ids its documents do not declare:\n" + String.join("\n", all));
        }

        private void check(@Nonnull String selector) throws IOException {
            String why = resolve(selector);
            if (why != null) {
                failures.add(selector + "  <-  " + why);
            }
        }

        /** Why {@code selector} names something its document does not declare, or null when it all resolves. */
        @Nullable
        private String resolve(@Nonnull String selector) throws IOException {
            String[] tokens = selector.trim().split(" +");
            String prefix = "";
            String scope = null;
            for (int i = 0; i < tokens.length; i++) {
                Matcher m = SEGMENT.matcher(tokens[i]);
                if (!m.matches()) {
                    return "unreadable segment " + tokens[i];
                }
                if (m.group(3) != null && i < tokens.length - 1) {
                    return "a property before the last segment";
                }
                String id = "#" + m.group(1);
                if (i == 0) {
                    String template = inline.get(id);
                    scope = template == null ? null : expand(KitDocs.template(kit, template));
                } else {
                    if (scope == null) {
                        return id + " would sit in the page's own document, which no painter addresses";
                    }
                    if (VANILLA.equals(scope)) {
                        return id + " reaches inside a vanilla template";
                    }
                    if (!KitDocs.declares(scope, id)) {
                        return id + " is not declared there";
                    }
                    String type = KitDocs.type(scope, id);
                    String block = KitDocs.block(scope, id);
                    if (type.startsWith("$C.")) {
                        scope = VANILLA;
                    } else if (type.contains("@")) {
                        scope = expand(KitDocs.template(kit, type.substring(type.indexOf('@'))) + block);
                    } else {
                        scope = expand(block);
                    }
                }
                prefix = prefix.isEmpty() ? id : prefix + " " + id;
                String indexes = m.group(2);
                if (!indexes.isEmpty()) {
                    int index = Integer.parseInt(indexes.substring(1, indexes.indexOf(']')));
                    List<String> templates = appended.get(prefix);
                    if (templates == null || index >= templates.size()) {
                        return prefix + "[" + index + "] was never appended";
                    }
                    scope = expand(KitDocs.document(templates.get(index)));
                    prefix = prefix + indexes;
                }
            }
            return null;
        }

        /** {@code scope} with the body of every inline kit template instantiated in it, recursively. */
        @Nonnull
        private String expand(@Nonnull String scope) {
            StringBuilder out = new StringBuilder(scope);
            Set<String> seen = new HashSet<>();
            int from = 0;
            while (true) {
                Matcher m = INSTANCE.matcher(out);
                if (!m.find(from)) {
                    return out.toString();
                }
                from = m.end();
                String name = "@" + m.group(1);
                if (seen.add(name) && KitDocs.defines(kit, name)) {
                    out.append('\n').append(KitDocs.template(kit, name));
                }
            }
        }
    }

    @Nonnull
    private static LedgerRow full(@Nonnull String id, @Nonnull Mark mark) {
        return new LedgerRow(id, Message.raw(id), Message.raw("meta"), Picture.texture(TEXTURE), Tone.ACTIVE,
                Message.raw("In progress"), Message.raw("10"), new Progress(1, 4), mark, true);
    }

    @Nonnull
    private static DetailView fullView() {
        List<DetailLine> lines = new ArrayList<>();
        for (Tick tick : Tick.values()) {
            lines.add(new DetailLine(Picture.texture(TEXTURE), Message.raw(tick.name()), Message.raw("1 / 2"),
                    Pill.of(Message.raw("Waiting"), Tone.COLLECT), tick, "line" + tick.ordinal(), tick == Tick.CURRENT));
        }
        lines.add(DetailLine.of(Picture.tooltipItem("No_Such_Item").or(Picture.texture(TEXTURE)),
                Message.raw("Reward")));
        DetailBlock block = new DetailBlock("b", Message.raw("Objectives"), Message.raw("In order"), lines);
        List<DetailAction> actions = List.of(
                new DetailAction(ActionSlot.PRIMARY, Message.raw("Collect"), ActionLook.COLLECT, "c", null, true, null),
                new DetailAction(ActionSlot.SECONDARY, Message.raw("Track"), ActionLook.NORMAL, "t", null, true,
                        Message.raw("Tip")),
                new DetailAction(ActionSlot.DANGER, Message.raw("Abandon"), ActionLook.DANGER, "a", null, false, null));
        return new DetailView(Picture.texture(TEXTURE), Message.raw("Title"), Message.raw("Meta"),
                Message.raw("Sub"), List.of(Pill.of(Message.raw("Feat"), Tone.DONE),
                        new Pill(Message.raw("Season"), Tone.LIVE, "#c0582a")),
                new DetailToggle(Message.raw("Pin"), false, "pin", Message.raw("Tip")), new Progress(1, 3),
                null, Message.raw("Lead"), List.of(block), actions, Message.raw("Hint"));
    }

    @Nonnull
    private static LedgerBindings ledgerBindings() {
        return new LedgerBindings() {
            @Override
            public EventData row(LedgerSection s, LedgerRow r) {
                return EventData.of("Row", r.id());
            }

            @Override
            public EventData section(LedgerSection s) {
                return EventData.of("Section", s.id());
            }

            @Override
            public EventData showMore(LedgerSection s) {
                return EventData.of("More", s.id());
            }
        };
    }

    @Nonnull
    private static DetailBindings detailBindings() {
        return new DetailBindings() {
            @Override
            public EventData line(DetailBlock b, DetailLine l) {
                return EventData.of("Open", l.selectId());
            }

            @Override
            public EventData toggle(DetailToggle t) {
                return EventData.of("Action", t.actionId());
            }
        };
    }
}
