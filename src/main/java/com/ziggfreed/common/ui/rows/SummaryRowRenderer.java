package com.ziggfreed.common.ui.rows;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Paints a {@link SummaryRow} list into a FIXED set of pre-declared {@code .ui} row slots (never
 * {@code cmd.append}), the HUD / page fixed-slot convention (a native objective HUD's {@code
 * #ObjRow0..N}): row {@code i} is addressed as {@code rowSelectorPrefix + i}, expected to contain
 * an {@code ItemGrid #Icon} child and a {@code Label #Name} child. The CONSUMER's {@code .ui}
 * declares those slots (each hidden by default); slots beyond the row count are hidden; slots
 * beyond {@code maxSlots} are dropped, with the caller responsible for rendering its own "+N more"
 * overflow row from the returned count.
 *
 * <p>Transport-agnostic (no consumer / HUD imports beyond the shared engine UI builder types), so
 * any surface that can push a {@code UICommandBuilder} - a HUD partial update, a page {@code
 * sendUpdate}, a full {@code build()} - reuses it. Nothing here assumes a HUD.
 */
public final class SummaryRowRenderer {

    private SummaryRowRenderer() {
    }

    /**
     * Render {@code rows} into {@code rowSelectorPrefix + 0 .. rowSelectorPrefix + (maxSlots-1)},
     * hiding unused slots, and return the overflow count ({@code max(0, rows.size() - maxSlots)})
     * so the caller can populate its own keyed "+N more" label.
     */
    public static int render(@Nonnull UICommandBuilder cmd, @Nonnull String rowSelectorPrefix,
            int maxSlots, @Nonnull List<SummaryRow> rows) {
        int shown = Math.min(rows.size(), maxSlots);
        for (int i = 0; i < maxSlots; i++) {
            String rowSel = rowSelectorPrefix + i;
            if (i >= shown) {
                cmd.set(rowSel + ".Visible", false);
                continue;
            }
            SummaryRow row = rows.get(i);
            cmd.set(rowSel + ".Visible", true);
            cmd.set(rowSel + " #Icon.Slots", List.of(new ItemGridSlot(new ItemStack(row.iconItemId(), 1))));
            cmd.set(rowSel + " #Name.TextSpans", row.text());
        }
        return Math.max(0, rows.size() - shown);
    }

    /** Convenience: also sets an overflow label's visibility + text when {@code overflowSelector} is given. */
    public static void renderWithOverflow(@Nonnull UICommandBuilder cmd, @Nonnull String rowSelectorPrefix,
            int maxSlots, @Nonnull List<SummaryRow> rows, @Nullable String overflowSelector,
            @Nullable com.hypixel.hytale.server.core.Message overflowText) {
        int overflow = render(cmd, rowSelectorPrefix, maxSlots, rows);
        if (overflowSelector == null) {
            return;
        }
        cmd.set(overflowSelector + ".Visible", overflow > 0);
        if (overflow > 0 && overflowText != null) {
            cmd.set(overflowSelector + ".TextSpans", overflowText);
        }
    }
}
