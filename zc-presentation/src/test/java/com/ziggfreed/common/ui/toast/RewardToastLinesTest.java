package com.ziggfreed.common.ui.toast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * The chip-to-toast bridge: rows mirror chips, icons carry quantity one (the chip's own label
 * already says how many), and a list past the renderer's row budget spends its last row on the
 * caller's overflow line rather than silently cutting.
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
    void theSpecEntryPointReadsThroughTheConsumersOwnSource() {
        // The generic reading can name nothing in a bare JVM, so the consumer source is the whole
        // answer here - exactly the seam every chip surface already passes through.
        List<ToastLine> lines = RewardToastLines.lines(
                List.of(RewardSpec.of("Test_Kind", Map.of("Amount", "5"))),
                spec -> RewardChip.text(Msg.raw("five somethings")), null);
        assertEquals(1, lines.size());
        assertEquals("five somethings", lines.get(0).text().getFormattedMessage().rawText);
    }
}
