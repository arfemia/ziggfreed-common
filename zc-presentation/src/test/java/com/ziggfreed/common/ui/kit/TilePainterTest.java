package com.ziggfreed.common.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * What the tile painters send: a grid cleared and one template per tile by index; a category tile's parts on its
 * button, its complete style and check, its pill, its clamped accent strip and its click; a keepsake's one
 * background layer for its state, its scrim, its gold year and its tooltip; a statistic's figure, faint at zero,
 * and its optional lines. The header stat, the pill, the segment and the empty state ride along. Tagged
 * {@code engine-items}: a {@link UICommandBuilder}'s static init reaches the engine's item codec.
 */
@Tag("engine-items")
class TilePainterTest {

    private static final String TEXTURE = "UI/Custom/Pages/Memories/MissingIcon.png";

    @Test
    void categoryTilesPaintTheirPartsAndTheirClick() {
        CollectionTile combat = new CollectionTile("combat", Message.raw("Combat"), Message.raw("12 / 40"),
                Picture.texture(TEXTURE), new Progress(12, 40), false, null, "#e05a2a", true);
        CollectionTile seasons = new CollectionTile("seasons", Message.raw("Seasons"), null, Picture.NONE,
                new Progress(9, 9), true, Pill.of(Message.raw("On now"), Tone.LIVE), "#101010", false);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        TilePainter.collection(cmd, events, "#G", List.of(combat, seasons), t -> EventData.of("Category", t.id()));
        Painted p = Painted.of(cmd, events);

        assertEquals(List.of("#G"), p.clears());
        assertEquals(List.of("#G <- " + TilePainter.COLLECTION_TEMPLATE, "#G <- " + TilePainter.COLLECTION_TEMPLATE),
                p.appends());
        String t0 = "#G[0] #Tile";
        String t1 = "#G[1] #Tile";
        assertTrue(p.set(t0 + " #Name.TextSpans").contains("Combat"));
        assertTrue(p.set(t0 + " #Count.TextSpans").contains("12 / 40"));
        assertTrue(p.set(t0 + " #Pic #IcoTex.AssetPath").contains(TEXTURE));
        assertTrue(p.shown(t0 + " #BarTrack.Visible"));
        assertTrue(p.set(t0 + " #Bar.Value").contains("0.3"));
        assertFalse(p.shown(t0 + " #Check.Visible"));
        assertFalse(p.has(t0 + ".Style"), "an incomplete tile keeps its authored style");
        assertFalse(p.shown(t0 + " #Badge.Visible"));
        assertTrue(p.set(t0 + " #AccentStrip.Background").contains("#e05a2a"), "a readable accent is kept");
        assertTrue(p.shown(t0 + " #New.Visible"));
        assertTrue(p.binding(t0).contains("\"combat\""));

        assertTrue(p.references(t1 + ".Style", ZigStyles.DOCUMENT, "ZigTileCompleteStyle"));
        assertTrue(p.shown(t1 + " #Check.Visible"));
        assertFalse(p.shown(t1 + " #Count.Visible"));
        assertTrue(p.shown(t1 + " #Badge.Visible"));
        assertTrue(p.set(t1 + " #Badge #Label.TextSpans").contains("On now"));
        assertTrue(p.set(t1 + " #AccentStrip.Background").contains(ZigTokens.ACCENT),
                "an accent too dark for the row falls back to the kit's gold");
        assertFalse(p.shown(t1 + " #New.Visible"));
    }

    @Test
    void aTileTheBindingDeclinesIsNotClickable() {
        CollectionTile tile = new CollectionTile("x", Message.raw("X"), null, Picture.NONE, null, false, null, null,
                false);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        TilePainter.collection(cmd, events, "#G", List.of(tile), t -> null);
        Painted p = Painted.of(cmd, events);
        assertEquals(0, p.bindingCount());
        assertFalse(p.shown("#G[0] #Tile #BarTrack.Visible"));
        assertFalse(p.shown("#G[0] #Tile #AccentStrip.Visible"));
    }

    @Test
    void aTileUnderwayShowsItsCompletionRingAndACompleteTileItsCheckInstead() {
        CollectionTile underway = new CollectionTile("combat", Message.raw("Combat"), null, Picture.NONE,
                new Progress(3, 12), false, null, null, false);
        CollectionTile complete = new CollectionTile("seasons", Message.raw("Seasons"), null, Picture.NONE,
                new Progress(9, 9), true, null, null, false);
        CollectionTile counted = new CollectionTile("feats", Message.raw("Feats"), null, Picture.NONE, null, false,
                null, null, false);
        UICommandBuilder cmd = new UICommandBuilder();
        TilePainter.collection(cmd, new UIEventBuilder(), "#G", List.of(underway, complete, counted), t -> null);
        Painted p = Painted.of(cmd);

        assertTrue(p.shown("#G[0] #Tile #Ring.Visible"), "a category underway wears its ring");
        assertEquals("0.25", ringValue(p.set("#G[0] #Tile #Ring.Value")), "the ring reads the tile's own fraction");
        assertFalse(p.shown("#G[1] #Tile #Ring.Visible"), "a complete tile shows its check, never a full ring");
        assertTrue(p.shown("#G[1] #Tile #Check.Visible"));
        assertFalse(p.shown("#G[2] #Tile #Ring.Visible"), "a tile with no progress (the Feats tile) has no ring");
        assertFalse(p.has("#G[2] #Tile #Ring.Value"));
    }

    /** A set command's value, unwrapped from {@code {"0": value}}. */
    private static String ringValue(String data) {
        int colon = data.indexOf(':');
        return data.substring(colon + 1, data.lastIndexOf('}')).trim();
    }

    @Test
    void aKeepsakeShowsExactlyItsStatesLayer() {
        KeepsakeTile earned = new KeepsakeTile("2026", Message.raw("2026"), Message.raw("Earned"),
                Picture.texture(TEXTURE), KeepsakeState.EARNED, true, Message.raw("Lantern Keeper 2026"));
        KeepsakeTile missed = new KeepsakeTile("2025", Message.raw("2025"), Message.raw("Missed"), Picture.NONE,
                KeepsakeState.MISSED, false, null);
        UICommandBuilder cmd = new UICommandBuilder();
        TilePainter.keepsakes(cmd, "#Shelf", List.of(earned, missed));
        Painted p = Painted.of(cmd);

        assertEquals(List.of("#Shelf"), p.clears());
        String k0 = "#Shelf[0] #Keep";
        String k1 = "#Shelf[1] #Keep";
        assertTrue(p.shown(k0 + " #KeepEarned.Visible"));
        assertFalse(p.shown(k0 + " #KeepToEarn.Visible"));
        assertFalse(p.shown(k0 + " #KeepMissed.Visible"));
        assertFalse(p.shown(k0 + " #Scrim.Visible"));
        assertTrue(p.set(k0 + " #Label.Style.TextColor").contains(ZigTokens.ACCENT), "an earned year is gold");
        assertTrue(p.set(k0 + " #StateLine.Style.TextColor").contains(ZigTokens.TONE_LIVE_TEXT));
        assertTrue(p.set(k0 + ".TooltipText").contains("Lantern Keeper"), "the tooltip sits on #Keep");

        assertTrue(p.shown(k1 + " #KeepMissed.Visible"));
        assertFalse(p.shown(k1 + " #KeepEarned.Visible"));
        assertTrue(p.shown(k1 + " #Scrim.Visible"));
        assertFalse(p.has(k1 + " #Label.Style.TextColor"));
        assertFalse(p.has(k1 + ".TooltipText"));
    }

    @Test
    void aStatTileReadsFaintAtZeroAndShowsOnlyItsLines() {
        StatTile bombs = new StatTile("bombs", Picture.texture(TEXTURE), Message.raw("12"), Message.raw("Bombs thrown"),
                Message.raw("40 in all"), Message.raw("900 on this server"), false);
        StatTile none = new StatTile("geodes", Picture.NONE, Message.raw("0"), Message.raw("Geodes"), null, null, true);
        UICommandBuilder cmd = new UICommandBuilder();
        TilePainter.stats(cmd, "#Stats", List.of(bombs, none));
        Painted p = Painted.of(cmd);
        assertEquals(List.of("#Stats <- " + TilePainter.STAT_TEMPLATE, "#Stats <- " + TilePainter.STAT_TEMPLATE),
                p.appends());
        String s0 = "#Stats[0] #StatTile";
        String s1 = "#Stats[1] #StatTile";
        assertTrue(p.set(s0 + " #Figure.TextSpans").contains("12"));
        assertTrue(p.shown(s0 + " #Caption.Visible"));
        assertTrue(p.shown(s0 + " #Server.Visible"));
        assertFalse(p.has(s0 + " #Figure.Style.TextColor"));
        assertTrue(p.set(s1 + " #Figure.Style.TextColor").contains(ZigTokens.INK_FAINT));
        assertFalse(p.shown(s1 + " #Caption.Visible"));
        assertFalse(p.shown(s1 + " #Server.Visible"));
    }

    @Test
    void aHeaderStatTakesItsTonesColourOnItsFigureAlone() {
        UICommandBuilder cmd = new UICommandBuilder();
        StatPainter.paint(cmd, "#Stat1", new Stat(Message.raw("830"), Message.raw("Points"), Tone.COLLECT));
        StatPainter.paint(cmd, "#Stat0", new Stat(Message.raw("45 / 312"), Message.raw("Earned"), Tone.NEUTRAL));
        Painted p = Painted.of(cmd);
        assertTrue(p.set("#Stat1 #Value.TextSpans").contains("830"));
        assertTrue(p.set("#Stat1 #Label.TextSpans").contains("Points"));
        assertTrue(p.set("#Stat1 #Value.Style.TextColor").contains(ZigTokens.ACCENT), "points read gold");
        assertTrue(p.set("#Stat0 #Value.Style.TextColor").contains(ZigTokens.INK_STRONG), "painted back to ink");
        assertFalse(p.has("#Stat1 #Value.Style"), "the figure keeps its authored alignment and size");
    }

    @Test
    void aPillIsItsWordOnTheScrimOrItsOwnFill() {
        UICommandBuilder cmd = new UICommandBuilder();
        PillPainter.paint(cmd, "#Chip", Pill.of(Message.raw("On now"), Tone.LIVE));
        PillPainter.paint(cmd, "#Plain", Pill.of(Message.raw("Note"), Tone.NEUTRAL));
        Painted p = Painted.of(cmd);
        assertTrue(p.set("#Chip.Background").contains("\"Color\""));
        assertTrue(p.set("#Chip #Label.Style.TextColor").contains(ZigTokens.TONE_LIVE_TEXT));
        assertTrue(p.shown("#Chip #Dot.Visible"));
        assertFalse(p.shown("#Plain #Dot.Visible"), "a neutral pill has no dot");
        assertTrue(p.set("#Plain #Label.Style.TextColor").contains(ZigTokens.INK_BODY));
    }

    @Test
    void aSegmentIsSwappedOnAndOffAndAppendedByItsOwnCount() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        SegmentPainter.set(cmd, "#ViewBrowse", Message.raw("Browse"), true, null);
        SegmentPainter.set(cmd, "#ViewOverview", Message.raw("Overview"), false, null);
        cmd.clear("#Years");
        String first = SegmentPainter.append(cmd, events, "#Years", Message.raw("2026"), true, true, true,
                EventData.of("Year", "2026"));
        String second = SegmentPainter.append(cmd, events, "#Years", Message.raw("Every season"), false, false, false,
                EventData.of("Year", "all"));
        Painted p = Painted.of(cmd, events);
        assertTrue(p.references("#ViewBrowse.Style", ZigStyles.DOCUMENT, "ZigSegmentOnStyle"));
        assertTrue(p.references("#ViewOverview.Style", ZigStyles.DOCUMENT, "ZigSegmentStyle"));
        assertTrue(p.set("#ViewBrowse #Label.TextSpans").contains("Browse"));
        assertEquals("#Years[0] #Seg", first);
        assertEquals("#Years[1] #Seg", second);
        assertTrue(p.references(first + ".Style", ZigStyles.DOCUMENT, "ZigSegmentOnStyle"));
        assertFalse(p.has(second + ".Style"), "an appended segment that is off keeps its authored style");
        assertTrue(p.shown(first + " #Check.Visible"));
        assertTrue(p.shown(first + " #Dot.Visible"));
        assertFalse(p.shown(second + " #Check.Visible"));
        assertTrue(p.binding(first).contains("2026"));
        assertTrue(p.binding(second).contains("all"));
    }

    @Test
    void anEmptyStateShowsItsButtonOnlyWithAnAction() {
        DetailAction clear = new DetailAction(ActionSlot.PRIMARY, KitText.clearFilters(), ActionLook.NORMAL, "clear",
                null, true, null);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        EmptyStatePainter.paint(cmd, events, "#Empty",
                new EmptyState(Picture.texture(TEXTURE), KitText.nothingMatches(), KitText.nothingMatchesLine(), clear),
                EventData.of("Action", "clear"));
        EmptyStatePainter.paint(cmd, events, "#Blank",
                new EmptyState(Picture.NONE, Message.raw("No quests yet"), null, null), null);
        Painted p = Painted.of(cmd, events);
        assertTrue(p.set("#Empty #ETitle.TextSpans").contains(KitText.key("nothing_matches")));
        assertTrue(p.shown("#Empty #ELine.Visible"));
        assertTrue(p.shown("#Empty #EAction.Visible"));
        assertTrue(p.set("#Empty #EAction #Label.TextSpans").contains(KitText.key("clear_filters")));
        assertTrue(p.binding("#Empty #EAction").contains("clear"));
        assertTrue(p.set("#Empty #EPic #IcoTex.AssetPath").contains(TEXTURE));
        assertFalse(p.shown("#Blank #ELine.Visible"));
        assertFalse(p.shown("#Blank #EAction.Visible"));
        assertNull(p.binding("#Blank #EAction"));
    }

    @Test
    void aKeepsakeStillToEarnShowsItsOwnLayerUnderTheScrim() {
        UICommandBuilder cmd = new UICommandBuilder();
        TilePainter.keepsakes(cmd, "#Shelf", List.of(new KeepsakeTile("2026", Message.raw("2026"),
                Message.raw("Still to earn"), Picture.NONE, KeepsakeState.TO_EARN, false, null)));
        Painted p = Painted.of(cmd);
        assertTrue(p.shown("#Shelf[0] #Keep #KeepToEarn.Visible"));
        assertFalse(p.shown("#Shelf[0] #Keep #KeepEarned.Visible"));
        assertTrue(p.shown("#Shelf[0] #Keep #Scrim.Visible"));
    }

    @Test
    void aHeaderStatInAToneReadsInItsTonesText() {
        UICommandBuilder cmd = new UICommandBuilder();
        StatPainter.paint(cmd, "#Stat2", new Stat(Message.raw("3"), Message.raw("In progress"), Tone.ACTIVE));
        assertTrue(Painted.of(cmd).set("#Stat2 #Value.Style.TextColor").contains(ZigTokens.TONE_ACTIVE_TEXT));
    }

    @Test
    void anEmptyStatesButtonShowsWithoutABindingWhenThePageBindsItElsewhere() {
        DetailAction clear = new DetailAction(ActionSlot.PRIMARY, KitText.clearFilters(), ActionLook.NORMAL, "clear",
                null, true, null);
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        EmptyStatePainter.paint(cmd, events, "#Empty", new EmptyState(Picture.NONE, KitText.nothingMatches(), null,
                clear), null);
        Painted p = Painted.of(cmd, events);
        assertTrue(p.shown("#Empty #EAction.Visible"));
        assertTrue(p.has("#Empty #EAction #Label.TextSpans"));
        assertEquals(0, p.bindingCount(), "a repaint never binds the live button again");
    }

    @Test
    void noTooltipClearsTheLastOne() {
        UICommandBuilder cmd = new UICommandBuilder();
        KitPaint.tooltip(cmd, "#X", null);
        KitPaint.tooltip(cmd, "#Y", Message.raw("Shown"));
        Painted p = Painted.of(cmd);
        assertEquals("{\"0\": \"\"}", p.set("#X.TooltipText"));
        assertTrue(p.set("#Y.TooltipText").contains("Shown"));
    }

    @Test
    void aPillsOwnFillIsClampedAndCarriesTheInkThatReadsOnIt() {
        UICommandBuilder cmd = new UICommandBuilder();
        PillPainter.paint(cmd, "#Dark", new Pill(Message.raw("Hallows"), Tone.LIVE, "#c0582a"));
        PillPainter.paint(cmd, "#Gold", new Pill(Message.raw("Harvest"), Tone.LIVE, "#e8a93b"));
        PillPainter.paint(cmd, "#Murky", new Pill(Message.raw("Night"), Tone.LIVE, "#101010"));
        PillPainter.paint(cmd, "#Bad", new Pill(Message.raw("Oops"), Tone.WAITING, "orange"));
        Painted p = Painted.of(cmd);
        assertTrue(p.set("#Dark.Background").contains("#c0582a"));
        assertTrue(p.set("#Dark #Label.Style.TextColor").contains(ZigTokens.INK_BRIGHT), "white reads on it");
        assertTrue(p.set("#Gold #Label.Style.TextColor").contains(ZigTokens.INK_DARK), "white does not read on gold");
        assertTrue(p.set("#Murky.Background").contains(ZigTokens.ACCENT), "a fill too dark for the row is clamped");
        assertTrue(p.set("#Bad.Background").contains(ZigTokens.SURFACE_SCRIM), "not a colour: the tone pill");
        assertTrue(p.set("#Bad #Label.Style.TextColor").contains(ZigTokens.TONE_WAITING_TEXT));
        assertTrue(p.shown("#Bad #Dot.Visible"));
        assertFalse(p.shown("#Dark #Dot.Visible"));
    }
}
