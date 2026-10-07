package com.ziggfreed.common.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * What a detail paint sends into an {@code @ZigDetailPage}: every header leaf both ways (the page is live and
 * repainted on each selection), pills and blocks cleared and appended again, lines with their tick, count, pill and
 * select target, the progress block, and the action bar's buttons by slot with their looks, never bound by a paint
 * ({@link DetailPainter#bindActionsOnce} binds them once). Tagged {@code engine-items}: a {@link UICommandBuilder}'s
 * static init reaches the engine's item codec.
 */
@Tag("engine-items")
class DetailPainterTest {

    private static final String HOST = "#Page";
    private static final String TEXTURE = "UI/Custom/Pages/Memories/MissingIcon.png";
    private static final String BLOCK0 = "#Page #DBlocks[0] #Block";
    private static final String LINE0 = BLOCK0 + " #Lines[0]";
    private static final String LINE1 = BLOCK0 + " #Lines[1]";

    @Test
    void theHeaderPaintsEveryLeafBothWays() {
        Painted p = paint(view());
        assertTrue(p.set(HOST + " #DTitle.TextSpans").contains("Geode Cracker"));
        assertTrue(p.set(HOST + " #DPic #IcoTex.AssetPath").contains(TEXTURE));
        assertTrue(p.shown(HOST + " #DMeta.Visible"));
        assertFalse(p.shown(HOST + " #DSubMeta.Visible"), "no sub-meta: hidden, so a repaint clears the last one");
        assertTrue(p.shown(HOST + " #DLead.Visible"));
        assertTrue(p.set(HOST + " #DLead.TextSpans").contains("Crack 15"));
    }

    @Test
    void pillsAreClearedAndAppendedAgainEachPaint() {
        Painted p = paint(view());
        assertTrue(p.clears().contains(HOST + " #DBadges"));
        assertEquals(2, p.appends().stream().filter(a -> a.equals(HOST + " #DBadges <- Pages/ZigPill.ui")).count());
        String pill = HOST + " #DBadges[0] #Pill";
        assertTrue(p.set(pill + " #Label.TextSpans").contains("Server first"));
        assertTrue(p.set(pill + " #Label.Style.TextColor").contains(ZigTokens.TONE_COLLECT_TEXT), "the tone's word");
        assertTrue(p.shown(pill + " #Dot.Visible"));
        assertTrue(p.set(pill + " #Dot.Background.Color").contains(ZigTokens.TONE_COLLECT_FILL));
        String own = HOST + " #DBadges[1] #Pill";
        assertTrue(p.set(own + ".Background").contains("#c0582a"), "a data accent fills its own pill");
        assertFalse(p.shown(own + " #Dot.Visible"));
        assertTrue(p.shown(HOST + " #DBadges.Visible"));

        Painted none = paint(bare());
        assertFalse(none.shown(HOST + " #DBadges.Visible"));
    }

    @Test
    void theToggleIsLabelledStyledAndTippedButNeverBoundByAPaint() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        DetailPainter.paint(cmd, events, HOST, view(), bindings(), null);
        Painted p = Painted.of(cmd, events);
        String toggle = HOST + " " + DetailPainter.TOGGLE;
        assertTrue(p.shown(toggle + ".Visible"));
        assertTrue(p.set(toggle + " #Label.TextSpans").contains("Unpin"));
        assertTrue(p.references(toggle + ".Style", ZigStyles.DOCUMENT, "ZigButtonPrimaryStyle"), "on reads primary");
        assertTrue(p.set(toggle + ".TooltipText").contains("Pinned achievements"));
        assertNull(p.binding(toggle));
        for (ActionSlot slot : ActionSlot.values()) {
            assertNull(p.binding(HOST + " " + slot.id()), "a live action button is bound once in build");
        }

        Painted none = paint(bare());
        assertFalse(none.shown(toggle + ".Visible"));
    }

    @Test
    void theProgressBlockReadsCountAndPercentOrItsOwnLabel() {
        Painted p = paint(view());
        assertTrue(p.shown(HOST + " #DProgress.Visible"));
        assertTrue(p.set(HOST + " #DProgress #Bar.Value").contains("0.5"));
        String count = p.set(HOST + " #DProgress #Count.TextSpans");
        assertTrue(count.contains(KitText.key("count")), "current / total as typed numbers: " + count);
        String percent = p.set(HOST + " #DProgress #Percent.TextSpans");
        assertTrue(percent.contains(KitText.key("percent")) && percent.contains("50"), percent);

        DetailView labelled = new DetailView(Picture.NONE, Message.raw("T"), null, null, List.of(), null,
                new Progress(2, 3), Message.raw("2 / 3 steps"), null, List.of(), List.of(), null);
        assertTrue(paint(labelled).set(HOST + " #DProgress #Count.TextSpans").contains("2 / 3 steps"));
        assertFalse(paint(bare()).shown(HOST + " #DProgress.Visible"));
    }

    @Test
    void blocksAndLinesAreAppendedIntoTheirHosts() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        DetailPainter.paint(cmd, events, HOST, view(), bindings(), null);
        Painted p = Painted.of(cmd, events);
        assertTrue(p.clears().contains(HOST + " #DBlocks"));
        assertTrue(p.appends().contains(HOST + " #DBlocks <- " + DetailPainter.BLOCK_TEMPLATE));
        assertEquals(2, p.appends().stream()
                .filter(a -> a.equals(BLOCK0 + " #Lines <- " + DetailPainter.LINE_TEMPLATE)).count());
        assertTrue(p.set(BLOCK0 + " #Head #HeadLabel.TextSpans").contains("Criteria"));
        assertFalse(p.has(BLOCK0 + " #Head #Label.TextSpans"), "the link's own #Label is never the header's");
        assertFalse(p.shown(BLOCK0 + " #Head #Meta.Visible"));

        assertTrue(p.set(LINE0 + " #LineText.TextSpans").contains("Crack geodes"));
        assertTrue(p.shown(LINE0 + " #LineIconSlot.Visible"));
        assertTrue(p.set(LINE0 + " #LineIconSlot #IcoTex.AssetPath").contains(TEXTURE));
        assertTrue(p.set(LINE0 + " #Count.TextSpans").contains("3 / 15"));
        assertTrue(p.shown(LINE0 + " #Tick.Visible"));
        assertTrue(p.shown(LINE0 + " #Tick #Current.Visible"));
        assertFalse(p.has(LINE0 + " #Tick #Done.Visible"), "only the line's own tick is shown");
        assertFalse(p.has(LINE0 + " #Tick #Current.Background.Color"), "a tick's tint is authored");
        assertTrue(p.set(LINE0 + " #LineText.Style.TextColor").contains(ZigTokens.INK_BRIGHT), "the current line");
        assertTrue(p.shown(LINE0 + " #Tag.Visible"));
        assertTrue(p.set(LINE0 + " #Tag #Label.TextSpans").contains("Waiting"));
        assertTrue(p.shown(LINE0 + " #LineSelect.Visible"));
        assertTrue(p.binding(LINE0 + " #LineSelect").contains("\"geode\""), "a selectable line is bound");

        assertFalse(p.shown(LINE1 + " #LineIconSlot.Visible"), "a line with nothing to draw hides its slot");
        assertFalse(p.shown(LINE1 + " #Count.Visible"));
        assertFalse(p.shown(LINE1 + " #Tag.Visible"));
        assertFalse(p.shown(LINE1 + " #Tick.Visible"));
        assertFalse(p.shown(LINE1 + " #LineSelect.Visible"));
        assertNull(p.binding(LINE1 + " #LineSelect"));
        assertFalse(p.has(LINE1 + " #LineText.Style.TextColor"));
        for (String selector : p.sets().keySet()) {
            if (selector.startsWith(LINE0) || selector.startsWith(LINE1)) {
                assertFalse(selector.contains("#IcoItem"), "a plain picture never touches the item grid: " + selector);
            }
        }
    }

    @Test
    void standaloneLinesAreClearedAppendedAndOnlyTheSelectableBound() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        DetailLine open = new DetailLine(Picture.texture(TEXTURE), Message.raw("Ghoul Breaker"), Message.raw("3 / 50"),
                null, Tick.NONE, "ghoul_breaker", false);
        DetailLine plain = DetailLine.of(Picture.NONE, Message.raw("A hidden achievement"));
        DetailPainter.lines(cmd, events, "#ToEarn", List.of(open, plain), l -> EventData.of("Open", l.selectId()));
        Painted p = Painted.of(cmd, events);
        assertTrue(p.clears().contains("#ToEarn"), "cleared first, so a repaint never doubles the list");
        assertEquals(2, p.appends().stream()
                .filter(a -> a.equals("#ToEarn <- " + DetailPainter.LINE_TEMPLATE)).count());
        assertTrue(p.set("#ToEarn[0] #LineText.TextSpans").contains("Ghoul Breaker"));
        assertTrue(p.set("#ToEarn[0] #Count.TextSpans").contains("3 / 50"));
        assertTrue(p.set("#ToEarn[0] #LineIconSlot #IcoTex.AssetPath").contains(TEXTURE));
        assertTrue(p.shown("#ToEarn[0] #LineSelect.Visible"));
        assertTrue(p.binding("#ToEarn[0] #LineSelect").contains("\"ghoul_breaker\""), "a selectable line is bound");
        assertFalse(p.shown("#ToEarn[1] #LineSelect.Visible"), "a line with no select id stays plain");
        assertNull(p.binding("#ToEarn[1] #LineSelect"));
    }

    @Test
    void aStandaloneLineWhoseBindingDeclinesIsNeitherShownSelectableNorBound() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        DetailLine open = new DetailLine(Picture.NONE, Message.raw("Ghoul Breaker"), null, null, Tick.NONE,
                "ghoul_breaker", false);
        DetailPainter.lines(cmd, events, "#ToEarn", List.of(open), l -> null);
        Painted p = Painted.of(cmd, events);
        assertFalse(p.shown("#ToEarn[0] #LineSelect.Visible"));
        assertNull(p.binding("#ToEarn[0] #LineSelect"));
    }

    @Test
    void aLineWithAPlainPictureCarriesItsOwnTooltipOnThePicture() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        DetailLine coins = DetailLine.of(Picture.texture(TEXTURE), Message.raw("Hallow Coins"))
                .withTooltip(Message.raw("Spend them at Old Jack's stall."));
        DetailPainter.lines(cmd, events, "#Rewards", List.of(coins), l -> null);
        Painted p = Painted.of(cmd, events);
        assertTrue(p.set("#Rewards[0] #LineIconSlot.TooltipText").contains("Old Jack"),
                "a currency or boost reward says what it is on hover, as its old card did");
    }

    @Test
    void aLineThatOpensSomethingOrShowsAnItemsOwnTooltipGetsNoTooltipOfItsOwn() {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        Message tip = Message.raw("Not shown");
        DetailLine opens = new DetailLine(Picture.texture(TEXTURE), Message.raw("Ghoul Breaker"), null, null,
                Tick.NONE, "ghoul_breaker", false).withTooltip(tip);
        DetailLine item = DetailLine.of(Picture.tooltipItem("No_Such_Item").or(Picture.texture(TEXTURE)),
                Message.raw("Hallow Sweets")).withTooltip(tip);
        DetailPainter.lines(cmd, events, "#L", List.of(opens, item), l -> EventData.of("Open", l.selectId()));
        Painted p = Painted.of(cmd, events);
        assertFalse(p.has("#L[0] #LineIconSlot.TooltipText"), "a tooltip on the picture would swallow the line's click");
        assertFalse(p.has("#L[1] #LineIconSlot.TooltipText"), "the item grid's own tooltip is the point");
    }

    @Test
    void aRewardLineKeepsTheItemGridForItsTooltip() {
        DetailLine reward = DetailLine.of(Picture.tooltipItem("No_Such_Item").or(Picture.texture(TEXTURE)),
                Message.raw("Hallow Sweets"));
        DetailView v = new DetailView(Picture.NONE, Message.raw("T"), null, null, List.of(), null, null, null, null,
                List.of(new DetailBlock("rewards", Message.raw("Rewards"), null, List.of(reward))), List.of(), null);
        Painted p = paint(v);
        assertTrue(p.has(LINE0 + " #LineIconSlot #IcoItem.Visible"), "the tooltip path decides the item grid");
        assertTrue(p.shown(LINE0 + " #LineIconSlot.Visible"), "the item or its texture draws");
    }

    @Test
    void theActionBarShowsEachSlotWithItsLook() {
        Painted p = paint(view());
        String primary = HOST + " #Primary";
        assertTrue(p.shown(primary + ".Visible"));
        assertTrue(p.set(primary + " #Label.TextSpans").contains("Collect"));
        assertTrue(p.references(primary + ".Style", ZigStyles.DOCUMENT, "ZigButtonCollectStyle"));
        assertFalse(p.shown(primary + ".Disabled"));
        String danger = HOST + " #Danger";
        assertTrue(p.references(danger + ".Style", ZigStyles.DOCUMENT, "ZigButtonDangerStyle"));
        assertTrue(p.shown(danger + ".Disabled"), "a disabled action greys out in place");
        assertTrue(p.set(danger + ".TooltipText").contains("Not now"));
        assertFalse(p.shown(HOST + " #Secondary.Visible"), "no action in a slot hides its button");
        assertTrue(p.set(HOST + " #DActions #Hint.TextSpans").contains("Earned on"));
        assertTrue(p.shown(HOST + " #DActions.Visible"));

        Painted none = paint(bare());
        assertFalse(none.shown(HOST + " #DActions.Visible"), "no action and no hint hides the bar");
    }

    @Test
    void aLooksStyleWinsOverItsSlots() {
        assertEquals(ZigStyles.Name.BUTTON_PRIMARY, DetailPainter.style(ActionSlot.PRIMARY, ActionLook.NORMAL));
        assertEquals(ZigStyles.Name.BUTTON_SECONDARY, DetailPainter.style(ActionSlot.SECONDARY, ActionLook.NORMAL));
        assertEquals(ZigStyles.Name.BUTTON_DANGER, DetailPainter.style(ActionSlot.DANGER, ActionLook.NORMAL));
        assertEquals(ZigStyles.Name.BUTTON_COLLECT, DetailPainter.style(ActionSlot.PRIMARY, ActionLook.COLLECT));
        assertEquals(ZigStyles.Name.BUTTON_DANGER, DetailPainter.style(ActionSlot.SECONDARY, ActionLook.DANGER));
    }

    @Test
    void theActionsAndTheToggleAreBoundOnceInBuild() {
        UIEventBuilder events = new UIEventBuilder();
        DetailPainter.bindActionsOnce(events, HOST,
                slot -> slot == ActionSlot.SECONDARY ? null : EventData.of("Action", slot.name()),
                EventData.of("Action", "toggle"));
        Painted p = Painted.of(new UICommandBuilder(), events);
        assertTrue(p.binding(HOST + " #Primary").contains("PRIMARY"));
        assertTrue(p.binding(HOST + " #Danger").contains("DANGER"));
        assertNull(p.binding(HOST + " #Secondary"), "a null binding leaves the slot unbound");
        assertTrue(p.binding(HOST + " #DToggle").contains("toggle"));
        assertEquals(3, p.bindingCount());
    }

    @Test
    void noPaintWritesABareTextProperty() {
        Painted p = paint(view());
        for (String selector : p.sets().keySet()) {
            assertFalse(selector.endsWith(".Text"), "text goes on .TextSpans: " + selector);
        }
    }

    @Test
    void anOffToggleReadsSecondary() {
        DetailView off = new DetailView(Picture.NONE, Message.raw("T"), null, null, List.of(),
                new DetailToggle(Message.raw("Pin"), false, "pin", null), null, null, null, List.of(), List.of(), null);
        Painted p = paint(off);
        String toggle = HOST + " " + DetailPainter.TOGGLE;
        assertTrue(p.references(toggle + ".Style", ZigStyles.DOCUMENT, "ZigButtonSecondaryStyle"));
        assertEquals("{\"0\": \"\"}", p.set(toggle + ".TooltipText"), "no tooltip clears the last one");
    }

    @Test
    void eachTickShowsItsOwnGlyph() {
        List<DetailLine> lines = List.of(tick(Tick.DONE), tick(Tick.AHEAD), tick(Tick.LOCKED));
        DetailView v = new DetailView(Picture.NONE, Message.raw("T"), null, null, List.of(), null, null, null, null,
                List.of(new DetailBlock("b", Message.raw("B"), null, lines)), List.of(), null);
        Painted p = paint(v);
        assertTrue(p.shown(BLOCK0 + " #Lines[0] #Tick #Done.Visible"));
        assertTrue(p.shown(BLOCK0 + " #Lines[1] #Tick #Ahead.Visible"));
        assertTrue(p.shown(BLOCK0 + " #Lines[2] #Tick #Locked.Visible"));
        assertFalse(p.has(BLOCK0 + " #Lines[0] #Tick #Locked.Visible"));
    }

    @Test
    void aSubMetaAndABlockMetaShowWhenGiven() {
        DetailView v = new DetailView(Picture.NONE, Message.raw("T"), null, Message.raw("First claimed by Ana"),
                List.of(), null, null, null, null,
                List.of(new DetailBlock("b", Message.raw("Objectives"), Message.raw("In order"), List.of())),
                List.of(), null);
        Painted p = paint(v);
        assertTrue(p.shown(HOST + " #DSubMeta.Visible"));
        assertTrue(p.set(HOST + " #DSubMeta.TextSpans").contains("First claimed by Ana"));
        assertTrue(p.shown(BLOCK0 + " #Head #Meta.Visible"));
        assertTrue(p.set(BLOCK0 + " #Head #Meta.TextSpans").contains("In order"));
        assertFalse(p.shown(HOST + " #DMeta.Visible"));
    }

    @Test
    void theFallbackFormHidesADisabledActionInsteadOfGreyingIt() {
        UICommandBuilder cmd = new UICommandBuilder();
        DetailPainter.paint(cmd, new UIEventBuilder(), HOST, view(), bindings(), null, false);
        Painted p = Painted.of(cmd);
        assertFalse(p.shown(HOST + " #Danger.Visible"), "the disabled Abandon hides");
        assertTrue(p.shown(HOST + " #Primary.Visible"));
        for (String selector : p.sets().keySet()) {
            assertFalse(selector.endsWith(".Disabled"), "the fallback never sends .Disabled: " + selector);
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Nonnull
    private static Painted paint(@Nonnull DetailView view) {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        DetailPainter.paint(cmd, events, HOST, view, bindings(), null);
        return Painted.of(cmd, events);
    }

    @Nonnull
    private static DetailView view() {
        DetailLine current = new DetailLine(Picture.texture(TEXTURE), Message.raw("Crack geodes"),
                Message.raw("3 / 15"), Pill.of(Message.raw("Waiting"), Tone.COLLECT), Tick.CURRENT, "geode", true);
        DetailLine plain = DetailLine.of(Picture.NONE, Message.raw("Return to Old Jack"));
        DetailBlock criteria = new DetailBlock("criteria", Message.raw("Criteria"), null, List.of(current, plain));
        DetailAction collect = new DetailAction(ActionSlot.PRIMARY, Message.raw("Collect"), ActionLook.COLLECT,
                "collect", null, true, null);
        DetailAction abandon = new DetailAction(ActionSlot.DANGER, Message.raw("Abandon"), ActionLook.NORMAL,
                "abandon", null, false, Message.raw("Not now"));
        return new DetailView(Picture.texture(TEXTURE), Message.raw("Geode Cracker 2026"),
                Message.raw("Seasons > Hallow's Eve"), null,
                List.of(Pill.of(Message.raw("Server first"), Tone.COLLECT),
                        new Pill(Message.raw("Hallow's Eve"), Tone.LIVE, "#c0582a")),
                new DetailToggle(Message.raw("Unpin"), true, "pin", Message.raw("Pinned achievements show first")),
                new Progress(5, 10), null, Message.raw("Crack 15 Cursed Geodes."), List.of(criteria),
                List.of(collect, abandon), Message.raw("Earned on 2026-10-01"));
    }

    @Nonnull
    private static DetailLine tick(@Nonnull Tick tick) {
        return new DetailLine(Picture.NONE, Message.raw(tick.name()), null, null, tick, null, false);
    }

    @Nonnull
    private static DetailView bare() {
        return new DetailView(Picture.NONE, Message.raw("Bare"), null, null, List.of(), null, null, null, null,
                List.of(), List.of(), null);
    }

    @Nonnull
    private static DetailBindings bindings() {
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
