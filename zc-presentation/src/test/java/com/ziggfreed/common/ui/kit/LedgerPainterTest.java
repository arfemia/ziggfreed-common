package com.ziggfreed.common.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.ziggfreed.common.ui.menu.MenuPalette;

/**
 * What a ledger paint sends: one section template per section and one row template per row of an open section, by
 * index, inside the templates' own roots; a row's parts on its button (title, meta, picture, the tone's accent and
 * state style, value, bar, mark); the selected row swapped to its named styles (a tall row's meta keeping its second
 * line through every swap); a cap that ends in "Show N more";
 * and the partial updates (select, open, close) addressing only what the paint put there, binding only what they
 * append. Tagged {@code engine-items}: a {@link UICommandBuilder}'s static init reaches the engine's item codec.
 */
@Tag("engine-items")
class LedgerPainterTest {

    private static final String LIST = "#L";
    private static final String TEXTURE = "UI/Custom/Pages/Memories/MissingIcon.png";
    private static final String ROW0 = "#L[0] #Rows[0] #Select";
    private static final String ROW1 = "#L[0] #Rows[1] #Select";

    @Test
    void eachSectionIsItsTemplateAndEachOpenRowItsRowTemplate() {
        Run run = paint(model(), Set.of(), null, RowSize.STANDARD);
        assertEquals(List.of(LIST), run.p.clears(), "the list is cleared first");
        assertEquals(List.of(
                LIST + " <- " + LedgerPainter.SECTION_TEMPLATE,
                "#L[0] #Rows <- " + RowSize.STANDARD.template(),
                "#L[0] #Rows <- " + RowSize.STANDARD.template(),
                LIST + " <- " + LedgerPainter.SECTION_TEMPLATE), run.p.appends(),
                "a closed section appends no rows until it is opened");
    }

    @Test
    void aHeadShowsItsLabelCountAndChevronAndASectionItsOpenness() {
        Run run = paint(model(), Set.of(), null, RowSize.STANDARD);
        assertTrue(run.p.has("#L[0] #Head #Label.TextSpans"));
        assertTrue(run.p.set("#L[0] #Head #Count.TextSpans").contains("ziggfreedcommon.fmt.num"),
                "the count is a typed number");
        assertTrue(run.p.shown("#L[0] #Head #Chevron.Visible"), "a head the page answers shows its chevron");
        assertTrue(run.p.shown("#L[0] #Head #Chevron #Open.Visible"));
        assertFalse(run.p.shown("#L[0] #Head #Chevron #Closed.Visible"));
        assertTrue(run.p.shown("#L[0] #Rows.Visible"));
        assertFalse(run.p.shown("#L[1] #Rows.Visible"));
        assertTrue(run.p.shown("#L[1] #Head #Chevron #Closed.Visible"));
        assertEquals("#L[0] #Section", run.index.sectionSelector("open"));
        assertTrue(run.index.isOpen("open"));
        assertFalse(run.index.isOpen("closed"));
    }

    @Test
    void aRowPaintsItsPartsOnItsButton() {
        Run run = paint(model(), Set.of(), null, RowSize.STANDARD);
        Painted p = run.p;
        assertTrue(p.set(ROW0 + " #Title.TextSpans").contains("Ghoul Breaker"));
        assertTrue(p.set(ROW0 + " #Meta.TextSpans").contains("0 / 50"));
        assertTrue(p.shown(ROW0 + " #Meta.Visible"));
        assertTrue(p.set(ROW0 + " #Pic #IcoTex.AssetPath").contains(TEXTURE), "a plain picture on the AssetImage");
        assertTrue(p.set(ROW0 + " #Accent.Background").contains(ZigTokens.TONE_ACTIVE_FILL), "the tone's accent");
        assertTrue(p.shown(ROW0 + " #Accent.Visible"));
        assertTrue(p.set(ROW0 + " #State.TextSpans").contains("In progress"));
        assertTrue(p.references(ROW0 + " #State.Style", ZigStyles.DOCUMENT, "ZigStateActiveStyle"),
                "the state word takes its tone's named style");
        assertTrue(p.set(ROW0 + " #Value.TextSpans").contains("10 pts"));
        assertTrue(p.shown(ROW0 + " #BarTrack.Visible"));
        assertTrue(p.set(ROW0 + " #Bar.Value").contains("0.2"), "10 of 50 fills a fifth of the bar");
        assertTrue(p.shown(ROW0 + " #Mark.Visible"));
        assertTrue(p.shown(ROW0 + " #Mark #Pinned.Visible"));
        assertFalse(p.shown(ROW0 + " #Mark #Tracked.Visible"));
        for (String selector : p.sets().keySet()) {
            assertFalse(selector.contains("#IcoItem"), "a row's picture never addresses an item grid: " + selector);
            assertFalse(selector.endsWith(".Text"), "text goes on .TextSpans: " + selector);
        }
    }

    @Test
    void aNeutralRowWithNothingToSayHidesItsParts() {
        Run run = paint(model(), Set.of(), null, RowSize.STANDARD);
        Painted p = run.p;
        assertFalse(p.shown(ROW1 + " #Accent.Visible"), "no tone, no accent");
        assertFalse(p.has(ROW1 + " #Accent.Background"));
        assertFalse(p.shown(ROW1 + " #Meta.Visible"));
        assertFalse(p.shown(ROW1 + " #State.Visible"));
        assertFalse(p.has(ROW1 + " #State.Style"));
        assertFalse(p.shown(ROW1 + " #Value.Visible"));
        assertFalse(p.shown(ROW1 + " #BarTrack.Visible"));
        assertFalse(p.shown(ROW1 + " #Mark.Visible"));
        assertFalse(p.shown(ROW1 + " #Pic #IcoTex.Visible"), "nothing to draw hides the picture");
    }

    @Test
    void aFinishedRowsMetaReadsFaint() {
        LedgerRow done = new LedgerRow("d", Message.raw("Done one"), Message.raw("Earned 2026-10-01"), Picture.NONE,
                Tone.DONE, Message.raw("Done"), null, null, Mark.NONE, true);
        Run run = paint(new LedgerModel(List.of(new LedgerSection("s", Message.raw("S"), List.of(done), true)), "d"),
                Set.of(), null, RowSize.STANDARD);
        assertTrue(run.p.references(ROW0 + " #Meta.Style", ZigStyles.TEXT_DOCUMENT, "ZigFaintStyle"));
        assertTrue(run.p.references(ROW0 + " #State.Style", ZigStyles.DOCUMENT, "ZigStateDoneStyle"));
    }

    @Test
    void theSelectedRowIsSwappedToItsNamedStylesAndOnlyIt() {
        Run run = paint(model(), Set.of(), "r1", RowSize.STANDARD);
        assertTrue(run.p.references(ROW0 + ".Style", ZigStyles.DOCUMENT, "ZigRowSelectedStyle"));
        assertTrue(run.p.references(ROW0 + " #Title.Style", ZigStyles.DOCUMENT, "ZigRowTitleOnSelectedStyle"));
        assertTrue(run.p.references(ROW0 + " #Meta.Style", ZigStyles.DOCUMENT, "ZigRowMetaOnSelectedStyle"));
        assertTrue(run.p.references(ROW0 + " #Value.Style", ZigStyles.DOCUMENT, "ZigRowValueOnSelectedStyle"));
        assertTrue(run.p.references(ROW0 + " #State.Style", ZigStyles.DOCUMENT, "ZigStateOnSelectedStyle"),
                "the selected row's words are white, the tone's colour given way (the last write wins)");
        assertFalse(run.p.has(ROW1 + ".Style"), "a fresh row keeps its authored resting style");
        assertFalse(run.p.has(ROW1 + " #Title.Style"));
        assertEquals("r1", run.index.selectedRowId());
    }

    @Test
    void everyRowHeadAndShowMoreIsBoundWithThePagesData() {
        Run run = paint(model(), Set.of(), null, RowSize.STANDARD);
        assertTrue(run.p.binding(ROW0).contains("\"r1\""));
        assertTrue(run.p.binding(ROW1).contains("\"r2\""));
        assertTrue(run.p.binding("#L[0] #Head").contains("\"open\""));
        assertTrue(run.p.binding("#L[1] #Head").contains("\"closed\""));
        assertEquals(4, run.p.bindingCount(), "two rows and two heads; the closed section's row is not appended");
    }

    @Test
    void aNullBindingLeavesThatPartUnbound() {
        LedgerBindings none = new LedgerBindings() {
            @Override
            public EventData row(LedgerSection s, LedgerRow r) {
                return null;
            }

            @Override
            public EventData section(LedgerSection s) {
                return null;
            }

            @Override
            public EventData showMore(LedgerSection s) {
                return null;
            }
        };
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        LedgerPainter.paint(cmd, events, LIST, model(), Set.of(), null, none, RowSize.STANDARD, null);
        Painted p = Painted.of(cmd, events);
        assertEquals(0, p.bindingCount());
        assertFalse(p.shown("#L[0] #Head #Chevron.Visible"), "a head nothing answers shows no chevron, so it never looks foldable");
        assertFalse(p.shown("#L[1] #Head #Chevron.Visible"));
    }

    @Test
    void aSectionOverItsCapEndsInShowMore() {
        List<LedgerRow> rows = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            rows.add(plain("r" + i));
        }
        LedgerSection big = new LedgerSection("big", Message.raw("Big"), rows, true);
        Run run = paint(new LedgerModel(List.of(big), "r0"), Set.of(), null, RowSize.STANDARD);
        assertEquals(40, Collections.frequency(run.p.appends(), "#L[0] #Rows <- " + RowSize.STANDARD.template()));
        assertTrue(run.p.appends().contains("#L[0] #Rows <- " + LedgerPainter.SHOW_MORE_TEMPLATE));
        String more = "#L[0] #Rows[40] #More";
        String label = run.p.set(more + " #Label.TextSpans");
        assertTrue(label.contains(KitText.key("show_more")) && label.contains("5"), "Show 5 more: " + label);
        assertTrue(run.p.binding(more).contains("\"more\""));
        assertTrue(run.p.set("#L[0] #Head #Count.TextSpans").contains("45"), "the head counts every row");
        assertNull(run.index.rowSelector("r40"), "a row past the cap is not painted");
    }

    @Test
    void aCompactRowNeverAddressesAMetaLine() {
        Run run = paint(model(), Set.of(), null, RowSize.COMPACT);
        assertTrue(run.p.appends().contains("#L[0] #Rows <- Pages/ZigLedgerRowCompact.ui"));
        for (String selector : run.p.sets().keySet()) {
            assertFalse(selector.contains("#Meta"), "the compact row has no #Meta: " + selector);
        }
    }

    @Test
    void aTallRowIsAppendedAndPaintedLikeTheStandardRow() {
        Run run = paint(model(), Set.of(), null, RowSize.TALL);
        assertEquals(List.of(
                LIST + " <- " + LedgerPainter.SECTION_TEMPLATE,
                "#L[0] #Rows <- Pages/ZigLedgerRowTall.ui",
                "#L[0] #Rows <- Pages/ZigLedgerRowTall.ui",
                LIST + " <- " + LedgerPainter.SECTION_TEMPLATE), run.p.appends());
        assertTrue(run.p.set(ROW0 + " #Meta.TextSpans").contains("0 / 50"), "the tall row paints its meta line");
        assertTrue(run.p.shown(ROW0 + " #Meta.Visible"));
        assertFalse(run.p.shown(ROW1 + " #Meta.Visible"), "a row with nothing to say hides it");
    }

    /**
     * A tall row's meta keeps its second line in every state (M369): the selected row (the Almanac always has one),
     * a finished row's faint meta, and a row selected or let go by a partial update. Read in the order the client
     * applies them, every whole-style write to a tall row's {@code #Meta} names a style that wraps to two lines (a
     * one-line style would clip the line the row was made tall for); a leaf write such as {@code .TextColor} keeps
     * the shape the row has. The words still read: white on the selected row, the faint ink on a finished one.
     */
    @Test
    void aTallRowsMetaKeepsTwoLinesSelectedFaintAndLetGo() throws IOException {
        LedgerRow done = new LedgerRow("d", Message.raw("Done one"), Message.raw("Ended 12 days ago"), Picture.NONE,
                Tone.DONE, Message.raw("Done"), null, null, Mark.NONE, true);
        LedgerRow live = new LedgerRow("l", Message.raw("Live one"), Message.raw("On now - 27 days left"),
                Picture.NONE, Tone.LIVE, Message.raw("On now"), null, null, Mark.NONE, false);
        LedgerModel model = new LedgerModel(List.of(new LedgerSection("s", Message.raw("S"), List.of(live, done),
                true)), "l");
        String liveRow = ROW0;
        String doneRow = ROW1;

        UICommandBuilder cmd = new UICommandBuilder();
        LedgerIndex index = LedgerPainter.paint(cmd, new UIEventBuilder(), LIST, model, Set.of(), "l", bindings(),
                RowSize.TALL, null);
        MetaLook authored = metaLook(new UICommandBuilder(), liveRow);
        assertEquals(2, authored.lines(), "the tall template's meta wraps to two lines");
        assertEquals(ZigTokens.INK_MUTED, authored.ink(), "in the meta's muted ink");

        MetaLook livePainted = metaLook(cmd, liveRow);
        assertTrue(livePainted.lines() >= 2, "the selected tall row's meta keeps its second line: " + livePainted);
        assertEquals(ZigTokens.INK_BRIGHT, livePainted.ink(), "and reads white on the steel blue");
        MetaLook donePainted = metaLook(cmd, doneRow);
        assertTrue(donePainted.lines() >= 2, "a finished tall row's meta keeps its second line: " + donePainted);
        assertEquals(ZigTokens.INK_FAINT, donePainted.ink(), "and reads in the faint ink");

        UICommandBuilder moved = new UICommandBuilder();
        LedgerPainter.select(moved, index, "l", "d", null);
        MetaLook letGo = metaLook(cmd, moved, liveRow);
        assertTrue(letGo.lines() >= 2, "a tall row let go keeps its second line: " + letGo);
        assertEquals(ZigTokens.INK_MUTED, letGo.ink(), "and goes back to the muted ink");
        MetaLook picked = metaLook(cmd, moved, doneRow);
        assertTrue(picked.lines() >= 2, "a finished tall row selected keeps its second line: " + picked);
        assertEquals(ZigTokens.INK_BRIGHT, picked.ink(), "and reads white");
    }

    @Test
    void selectingMovesTheNamedStylesFromOneRowToTheOther() {
        Run run = paint(model(), Set.of(), "r1", RowSize.STANDARD);
        UICommandBuilder cmd = new UICommandBuilder();
        LedgerPainter.select(cmd, run.index, "r1", "r2", null);
        Painted p = Painted.of(cmd);
        assertTrue(p.references(ROW0 + ".Style", ZigStyles.DOCUMENT, "ZigRowStyle"));
        assertTrue(p.references(ROW0 + " #Title.Style", ZigStyles.TEXT_DOCUMENT, "ZigRowTitleStyle"));
        assertTrue(p.set(ROW0 + " #Meta.Style.TextColor").contains(ZigTokens.INK_MUTED), "the meta back in its ink");
        assertTrue(p.set(ROW0 + " #Value.Style.TextColor").contains(ZigTokens.INK_BODY));
        assertTrue(p.references(ROW0 + " #State.Style", ZigStyles.DOCUMENT, "ZigStateActiveStyle"),
                "the state word back in its tone");
        assertTrue(p.references(ROW1 + ".Style", ZigStyles.DOCUMENT, "ZigRowSelectedStyle"));
        assertFalse(p.has(ROW1 + " #Meta.Style"), "a row with no meta line is not restyled there");
        assertFalse(p.has(ROW1 + " #State.Style"));
        assertTrue(p.references(ROW1 + " #Title.Style", ZigStyles.DOCUMENT, "ZigRowTitleOnSelectedStyle"));
        assertTrue(p.appends().isEmpty() && p.clears().isEmpty(), "a selection appends nothing");
        assertEquals("r2", run.index.selectedRowId());
    }

    @Test
    void aRowPaintedTwiceIsSelectedInBothPlaces() {
        LedgerSection pinned = new LedgerSection("pinned", Message.raw("Pinned"), List.of(plain("x")), true);
        LedgerSection rest = new LedgerSection("rest", Message.raw("Rest"), List.of(plain("y"), plain("x")), true);
        Run run = paint(new LedgerModel(List.of(pinned, rest), "x"), Set.of(), null, RowSize.STANDARD);
        assertEquals(List.of("#L[0] #Rows[0] #Select", "#L[1] #Rows[1] #Select"), run.index.rowSelectors("x"));
        UICommandBuilder cmd = new UICommandBuilder();
        LedgerPainter.select(cmd, run.index, null, "x", null);
        Painted p = Painted.of(cmd);
        assertTrue(p.references("#L[0] #Rows[0] #Select.Style", ZigStyles.DOCUMENT, "ZigRowSelectedStyle"));
        assertTrue(p.references("#L[1] #Rows[1] #Select.Style", ZigStyles.DOCUMENT, "ZigRowSelectedStyle"));
    }

    @Test
    void selectingARowThatIsNotPaintedSendsNothingForIt() {
        Run run = paint(model(), Set.of(), null, RowSize.STANDARD);
        UICommandBuilder cmd = new UICommandBuilder();
        LedgerPainter.select(cmd, run.index, null, "r3", null);
        assertTrue(Painted.of(cmd).sets().isEmpty(), "r3 sits in a closed section: no selector that is not there");
    }

    @Test
    void openingASectionAppendsAndBindsItsRowsOnlyTheFirstTime() {
        Run run = paint(model(), Set.of(), null, RowSize.STANDARD);
        LedgerSection closed = model().sections().get(1);

        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        LedgerPainter.openSection(cmd, events, run.index, closed, bindings());
        Painted opened = Painted.of(cmd, events);
        assertEquals(List.of("#L[1] #Rows <- " + RowSize.STANDARD.template()), opened.appends());
        assertTrue(opened.binding("#L[1] #Rows[0] #Select").contains("\"r3\""), "the appended row is bound");
        assertEquals(1, opened.bindingCount(), "nothing already live is bound again");
        assertTrue(opened.shown("#L[1] #Rows.Visible"));
        assertTrue(opened.shown("#L[1] #Head #Chevron #Open.Visible"));
        assertTrue(run.index.isOpen("closed"));
        assertEquals("#L[1] #Rows[0] #Select", run.index.rowSelector("r3"));

        UICommandBuilder close = new UICommandBuilder();
        LedgerPainter.closeSection(close, run.index, "closed");
        assertFalse(Painted.of(close).shown("#L[1] #Rows.Visible"));
        assertFalse(run.index.isOpen("closed"));

        UICommandBuilder again = new UICommandBuilder();
        UIEventBuilder againEvents = new UIEventBuilder();
        LedgerPainter.openSection(again, againEvents, run.index, closed, bindings());
        Painted reopened = Painted.of(again, againEvents);
        assertTrue(reopened.appends().isEmpty(), "its rows are already there");
        assertEquals(0, reopened.bindingCount(), "a live row is never bound twice");
        assertTrue(reopened.shown("#L[1] #Rows.Visible"));
    }

    @Test
    void anUnknownSectionIsSkipped() {
        Run run = paint(model(), Set.of(), null, RowSize.STANDARD);
        UICommandBuilder cmd = new UICommandBuilder();
        LedgerPainter.openSection(cmd, new UIEventBuilder(), run.index,
                new LedgerSection("gone", Message.raw("Gone"), List.of(), true), bindings());
        LedgerPainter.closeSection(cmd, run.index, "gone");
        assertEquals(0, cmd.getCommands().length);
    }

    @Test
    void theViewersAnswersOverrideEachSectionsDefault() {
        LedgerSection open = model().sections().get(0);
        LedgerSection closed = model().sections().get(1);
        assertTrue(LedgerPainter.isOpen(open, Set.of()));
        assertFalse(LedgerPainter.isOpen(closed, Set.of()));

        Set<String> answers = LedgerPainter.withSection(Set.of(), "open", false);
        answers = LedgerPainter.withSection(answers, "closed", true);
        assertFalse(LedgerPainter.isOpen(open, answers), "a default-open section the viewer closed stays closed");
        assertTrue(LedgerPainter.isOpen(closed, answers));
        assertEquals(Set.of(LedgerPainter.CLOSED + "open", "closed"), answers);

        Set<String> reopened = LedgerPainter.withSection(answers, "open", true);
        assertTrue(LedgerPainter.isOpen(open, reopened));
        assertFalse(reopened.contains(LedgerPainter.CLOSED + "open"), "one answer per section");

        Run run = paint(model(), answers, null, RowSize.STANDARD);
        assertFalse(run.p.shown("#L[0] #Rows.Visible"));
        assertTrue(run.p.shown("#L[1] #Rows.Visible"));
    }

    @Test
    void everyFillIsATypedPatch() {
        Run run = paint(model(), Set.of(), null, RowSize.STANDARD);
        for (var set : run.p.sets().entrySet()) {
            if (set.getKey().endsWith(".Background")) {
                assertTrue(set.getValue().contains("\"Color\""), set.getKey() + " is a typed PatchStyle: " + set.getValue());
            }
        }
    }

    @Test
    void aModelPicksItsFirstSelectableRow() {
        assertEquals("r1", LedgerModel.of(model().sections()).firstSelectable());
        LedgerSection closedOnly = new LedgerSection("c", Message.raw("C"), List.of(plain("z")), false);
        assertEquals("z", LedgerModel.of(List.of(closedOnly)).firstSelectable(), "no open section: the first row");
        assertNull(LedgerModel.of(List.of()).firstSelectable());
        assertTrue(LedgerModel.of(List.of()).isEmpty());
        assertTrue(model().contains("r3"));
    }

    @Test
    void aTrackedRowShowsTheTrackedGlyphAlone() {
        LedgerRow tracked = new LedgerRow("t", Message.raw("Tracked"), null, Picture.NONE, Tone.ACTIVE, null, null,
                null, Mark.TRACKED, false);
        Run run = paint(new LedgerModel(List.of(new LedgerSection("s", Message.raw("S"), List.of(tracked), true)), "t"),
                Set.of(), null, RowSize.STANDARD);
        assertTrue(run.p.shown(ROW0 + " #Mark.Visible"));
        assertTrue(run.p.shown(ROW0 + " #Mark #Tracked.Visible"));
        assertFalse(run.p.shown(ROW0 + " #Mark #Pinned.Visible"));
    }

    @Test
    void deselectingAFinishedRowPutsItsMetaBackInTheFaintInk() {
        LedgerRow done = new LedgerRow("d", Message.raw("Done one"), Message.raw("Earned 2026-10-01"), Picture.NONE,
                Tone.DONE, Message.raw("Done"), null, null, Mark.NONE, true);
        Run run = paint(new LedgerModel(List.of(new LedgerSection("s", Message.raw("S"), List.of(done, plain("x")),
                true)), "d"), Set.of(), "d", RowSize.STANDARD);
        UICommandBuilder cmd = new UICommandBuilder();
        LedgerPainter.select(cmd, run.index, "d", "x", null);
        assertTrue(Painted.of(cmd).set(ROW0 + " #Meta.Style.TextColor").contains(ZigTokens.INK_FAINT));
    }

    @Test
    void theShowMoreCountIsCappedByTheSectionsCap() {
        List<LedgerRow> rows = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            rows.add(plain("r" + i));
        }
        Run run = paint(new LedgerModel(List.of(new LedgerSection("s", Message.raw("S"), rows, true, 10)), "r0"),
                Set.of(), null, RowSize.STANDARD);
        String label = run.p.set("#L[0] #Rows[10] #More #Label.TextSpans");
        assertTrue(label.contains("10") && !label.contains("35"), "Show 10 more, not the 35 left: " + label);
    }

    // ---------------------------------------------------------------------------------------------

    private record Run(Painted p, LedgerIndex index) {
    }

    /** What a row's {@code #Meta} ends up as: the lines its style lets it draw, and its ink as {@code #rrggbb}. */
    private record MetaLook(int lines, String ink) {
    }

    private static final Pattern REF_DOCUMENT = Pattern.compile("\"\\$Document\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REF_VALUE = Pattern.compile("\"@Value\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STRING_VALUE = Pattern.compile("\\{\\s*\"0\"\\s*:\\s*\"([^\"]*)\"\\s*}");

    /**
     * A tall row's {@code #Meta} after the client applies {@code builders} in order, starting from the look the tall
     * template authors: a whole-style write replaces the look with the named style's (its line cap and its ink,
     * read through any spread it builds on), a {@code .TextColor} leaf replaces the ink alone.
     */
    @Nonnull
    private static MetaLook metaLook(@Nonnull UICommandBuilder paint, @Nonnull String row) throws IOException {
        return metaLook(List.of(paint), row);
    }

    /** {@link #metaLook(UICommandBuilder, String)} after a paint and then a partial update. */
    @Nonnull
    private static MetaLook metaLook(@Nonnull UICommandBuilder paint, @Nonnull UICommandBuilder update,
            @Nonnull String row) throws IOException {
        return metaLook(List.of(paint, update), row);
    }

    @Nonnull
    private static MetaLook metaLook(@Nonnull List<UICommandBuilder> builders, @Nonnull String row) throws IOException {
        String doc = RowSize.TALL.template();
        String authored = KitDocs.property(KitDocs.block(KitDocs.document(doc), "#Meta"), "Style");
        assertNotNull(authored, doc + "'s #Meta has a style");
        int lines = lines(doc, authored);
        String ink = ink(doc, authored);
        String whole = row + " #Meta.Style";
        for (UICommandBuilder builder : builders) {
            for (CustomUICommand command : builder.getCommands()) {
                if (command.type != CustomUICommandType.Set) {
                    continue;
                }
                if (command.selector.equals(whole)) {
                    Matcher from = REF_DOCUMENT.matcher(command.data);
                    Matcher name = REF_VALUE.matcher(command.data);
                    assertTrue(from.find() && name.find(), whole + " takes a named style: " + command.data);
                    String body = KitDocs.style(KitDocs.document(from.group(1)), "@" + name.group(1));
                    lines = lines(from.group(1), body);
                    ink = ink(from.group(1), body);
                } else if (command.selector.equals(whole + ".TextColor")) {
                    Matcher value = STRING_VALUE.matcher(command.data);
                    assertTrue(value.find(), whole + ".TextColor is a colour: " + command.data);
                    ink = value.group(1).toLowerCase(Locale.ROOT);
                }
            }
        }
        return new MetaLook(lines, ink);
    }

    /** The lines a label style lets its text draw: its {@code WrapMaxLines}, unbounded when it wraps with none, else 1. */
    private static int lines(@Nonnull String doc, @Nonnull String style) throws IOException {
        String max = styleLeaf(doc, style, "WrapMaxLines");
        if (max != null) {
            return Integer.parseInt(max);
        }
        return "true".equals(styleLeaf(doc, style, "Wrap")) ? Integer.MAX_VALUE : 1;
    }

    /** A label style's ink as {@code #rrggbb}, its {@code $ZK.@} token read from {@code Common/ZigTokens.ui}. */
    @Nonnull
    private static String ink(@Nonnull String doc, @Nonnull String style) throws IOException {
        String colour = styleLeaf(doc, style, "TextColor");
        assertNotNull(colour, "the style names an ink: " + style);
        Matcher token = Pattern.compile("\\$ZK\\.@([A-Za-z][A-Za-z0-9]*)").matcher(colour);
        String markup = token.matches() ? ZigTokens.colours().get(token.group(1)) : colour;
        assertNotNull(markup, colour + " is a token of Common/ZigTokens.ui");
        return MenuPalette.fromMarkup(markup);
    }

    /** One leaf of a style's body ({@code (...)}), its own or the one it spreads ({@code ...$ZX.@Name}). */
    private static String styleLeaf(@Nonnull String doc, @Nonnull String style, @Nonnull String leaf)
            throws IOException {
        Matcher own = Pattern.compile("(?<![\\w@.])" + Pattern.quote(leaf) + "\\s*:\\s*([^,)]+)").matcher(style);
        if (own.find()) {
            return own.group(1).trim();
        }
        Matcher spread = Pattern.compile("\\.\\.\\.(?:\\$([A-Za-z]+)\\.)?@([A-Za-z][A-Za-z0-9]*)").matcher(style);
        if (!spread.find()) {
            return null;
        }
        String from = spread.group(1) == null ? doc : imported(doc, spread.group(1));
        return styleLeaf(from, KitDocs.style(KitDocs.document(from), "@" + spread.group(2)), leaf);
    }

    /** The document {@code doc} imports as {@code $alias}, rooted at {@code Common/UI/Custom/}. */
    @Nonnull
    private static String imported(@Nonnull String doc, @Nonnull String alias) throws IOException {
        Matcher m = Pattern.compile("\\$" + Pattern.quote(alias) + "\\s*=\\s*\"([^\"]+)\"").matcher(KitDocs.document(doc));
        assertTrue(m.find(), doc + " imports $" + alias);
        String relative = m.group(1);
        return relative.startsWith("../") ? relative.substring(3) : doc.substring(0, doc.lastIndexOf('/') + 1) + relative;
    }

    @Nonnull
    private static Run paint(@Nonnull LedgerModel model, @Nonnull Set<String> open, String selected,
            @Nonnull RowSize size) {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        LedgerIndex index = LedgerPainter.paint(cmd, events, LIST, model, open, selected, bindings(), size, null);
        assertNotNull(index);
        return new Run(Painted.of(cmd, events), index);
    }

    /** Section "open" (default open): r1 (active, pinned, everything) and r2 (neutral, nothing); "closed": r3. */
    @Nonnull
    private static LedgerModel model() {
        LedgerRow r1 = new LedgerRow("r1", Message.raw("Ghoul Breaker 2026"), Message.raw("0 / 50 ghouls"),
                Picture.texture(TEXTURE), Tone.ACTIVE, Message.raw("In progress"), Message.raw("10 pts"),
                new Progress(10, 50), Mark.PINNED, false);
        LedgerSection open = new LedgerSection("open", Message.raw("Open"), List.of(r1, plain("r2")), true);
        LedgerSection closed = new LedgerSection("closed", Message.raw("Closed"), List.of(plain("r3")), false);
        return new LedgerModel(List.of(open, closed), "r1");
    }

    @Nonnull
    private static LedgerRow plain(@Nonnull String id) {
        return new LedgerRow(id, Message.raw(id), null, Picture.NONE, Tone.NEUTRAL, null, null, null, Mark.NONE, false);
    }

    @Nonnull
    private static LedgerBindings bindings() {
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
                return EventData.of("Action", "more");
            }
        };
    }
}
