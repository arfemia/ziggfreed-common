package com.ziggfreed.common.ui.toast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * The chip-to-toast bridge: rows mirror chips, icons carry quantity one (the chip's own label
 * already says how many), a list past the renderer's row budget spends its last row on the
 * caller's overflow line rather than silently cutting, and a lootable's rolled rows form a section
 * only beside another reward.
 */
class RewardToastLinesTest {

    private static List<RewardChip> chips(int count) {
        List<RewardChip> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(RewardChip.of("Item_" + i, Msg.raw("chip " + i)));
        }
        return out;
    }

    @Test
    void aChipBecomesOneRowAndItsIconCarriesQuantityOne() {
        List<ToastLine> lines = RewardToastLines.fromChips(List.of(
                RewardChip.of("Tool_Pickaxe_Crude", Msg.raw("+500 Mining XP")),
                RewardChip.text(Msg.raw("Special Reward"))), null);

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).hasIcon());
        assertEquals("Tool_Pickaxe_Crude", lines.get(0).iconItemId());
        assertEquals(1, lines.get(0).quantity(),
                "the label already says how many; a badge would say it twice");
        assertFalse(lines.get(1).hasIcon(), "a text chip stays a text row");
    }

    @Test
    void aListThatFitsIsUntouched() {
        assertEquals(ToastRenderer.MAX_LINES,
                RewardToastLines.fromChips(chips(ToastRenderer.MAX_LINES), null).size());
    }

    @Test
    void overflowSpendsTheLastRowOnTheCallersLine() {
        int total = ToastRenderer.MAX_LINES + 3;
        List<ToastLine> lines = RewardToastLines.fromChips(chips(total),
                dropped -> Msg.raw("+" + dropped + " more"));

        assertEquals(ToastRenderer.MAX_LINES, lines.size());
        ToastLine last = lines.get(ToastRenderer.MAX_LINES - 1);
        assertFalse(last.hasIcon(), "the overflow line is text only");
        assertEquals("+" + (total - (ToastRenderer.MAX_LINES - 1)) + " more",
                last.text().getFormattedMessage().rawText);
    }

    @Test
    void aNullOverflowSimplyTruncates() {
        List<ToastLine> lines = RewardToastLines.fromChips(chips(ToastRenderer.MAX_LINES + 3), null);
        assertEquals(ToastRenderer.MAX_LINES, lines.size());
        assertEquals("chip " + (ToastRenderer.MAX_LINES - 1),
                lines.get(ToastRenderer.MAX_LINES - 1).text().getFormattedMessage().rawText);
    }

    @Test
    void aNarrowerBudgetFoldsTheRestIntoOneMoreRow() {
        List<ToastLine> lines = RewardToastLines.fromChips(chips(3), 1,
                dropped -> Msg.raw("+" + dropped + " more"));

        assertEquals(2, lines.size(), "the budgeted row plus the overflow row");
        assertEquals("chip 0", lines.get(0).text().getFormattedMessage().rawText);
        assertEquals("+2 more", lines.get(1).text().getFormattedMessage().rawText);

        assertEquals(3, RewardToastLines.fromChips(chips(3), 3, null).size(),
                "a list that fits its budget is untouched");
        assertEquals(1, RewardToastLines.fromChips(chips(3), 1, null).size(),
                "a null overflow simply truncates to the budget");
    }

    @Test
    void aBudgetPastThePanelStillFitsThePanel() {
        List<ToastLine> lines = RewardToastLines.fromChips(
                chips(ToastRenderer.MAX_LINES + 3), 99, dropped -> Msg.raw("+" + dropped + " more"));
        assertEquals(ToastRenderer.MAX_LINES, lines.size(),
                "the panel's own budget is the ceiling whatever a caller asks for");
    }

    @Test
    void theSpecEntryPointReadsThroughTheConsumersOwnSource() {
        // The generic reading can name nothing in a bare JVM, so the consumer source is the whole
        // answer here - exactly the seam every chip surface already passes through.
        List<ToastLine> lines = RewardToastLines.lines(
                List.of(RewardSpec.of("Test_Kind", Map.of("Amount", "5"))),
                spec -> RewardChip.text(Msg.raw("five somethings")), null);
        assertEquals(1, lines.size());
        assertEquals("five somethings", lines.get(0).text().getFormattedMessage().rawText);
    }

    private static RewardSpec named(String kind, String name) {
        return RewardSpec.of(kind, "Name", name);
    }

    private static String said(ToastLine line) {
        return line.text().getFormattedMessage().rawText;
    }

    @Test
    void aLootablesRowsFormASectionOnlyBesideAnotherReward() {
        Function<RewardSpec, ToastLine> row = spec -> ToastLine.text(Msg.raw(spec.paramOr("Name", "?")));
        IntFunction<Message> more = dropped -> Msg.raw("+" + dropped + " more");
        RewardSpec bundleA = named("Lootable", "Bundle A");
        RewardSpec bundleB = named("Lootable", "Bundle B");
        RewardSpec xp = named("Test_Xp", "100 XP");
        RewardSpec a1 = named("Item", "a1").withOrigin(bundleA);
        RewardSpec a2 = named("Item", "a2").withOrigin(bundleA);
        RewardSpec a3 = named("Item", "a3").withOrigin(bundleA);
        RewardSpec b1 = named("Item", "b1").withOrigin(bundleB);

        // One lootable alone reads flat, exactly as before.
        List<ToastLine> alone = RewardToastLines.linesBy(List.of(a1, a2), row, more);
        assertEquals(List.of("a1", "a2"), alone.stream().map(RewardToastLinesTest::said).toList());
        assertTrue(alone.stream().noneMatch(l -> l.heading() || l.indented()), "no header, no indent");

        // A lootable beside XP: the static row first, whatever the receipt order, then the section.
        List<ToastLine> mixed = RewardToastLines.linesBy(List.of(a1, xp), row, more);
        assertEquals(List.of("100 XP", "Bundle A", "a1"), mixed.stream().map(RewardToastLinesTest::said).toList());
        assertFalse(mixed.get(0).heading() || mixed.get(0).indented(), "the static row stays plain");
        assertTrue(mixed.get(1).heading() && !mixed.get(1).indented(), "the bundle's own reading heads it");
        assertTrue(mixed.get(2).indented() && !mixed.get(2).heading(), "its rolled rows sit beneath");

        // Two lootables: two headed sections.
        List<ToastLine> two = RewardToastLines.linesBy(List.of(a1, b1), row, more);
        assertEquals(List.of("Bundle A", "a1", "Bundle B", "b1"),
                two.stream().map(RewardToastLinesTest::said).toList());
        assertEquals(2, two.stream().filter(ToastLine::heading).count());

        // A heading counts against the budget but never ends the list, and "+N more" counts rows only.
        List<RewardSpec> receipt = List.of(xp, a1, a2, a3);
        List<ToastLine> cut = RewardToastLines.linesBy(receipt, row, 2, more);
        assertEquals(List.of("100 XP", "+3 more"), cut.stream().map(RewardToastLinesTest::said).toList(),
                "the heading left without its rows goes too, and is not counted as dropped");
        List<ToastLine> cutInside = RewardToastLines.linesBy(receipt, row, 3, more);
        assertEquals(List.of("100 XP", "Bundle A", "a1", "+2 more"),
                cutInside.stream().map(RewardToastLinesTest::said).toList());
    }
}
