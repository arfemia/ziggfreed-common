package com.ziggfreed.common.ui.rows;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
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
 * <p><b>Two-line rows are OPT-IN.</b> The sub-Label overload
 * ({@link #render(UICommandBuilder, String, int, List, String)}) also paints each row's optional
 * {@link SummaryRow#subText()} into a second Label the consumer names, hiding that Label on a row
 * that carries no second line. A caller that omits the sub-Label id NEVER touches such a Label,
 * which is the whole reason it is a parameter rather than a fixed {@code #Sub} convention: a
 * command written against a selector the consumer's {@code .ui} does not declare CRASHES the
 * client, so a consumer whose row slots are single-line must be able to keep them that way.
 *
 * <p>Transport-agnostic (no consumer / HUD imports beyond the shared engine UI builder types), so
 * any surface that can push a {@code UICommandBuilder} - a HUD partial update, a page {@code
 * sendUpdate}, a full {@code build()} - reuses it. Nothing here assumes a HUD.
 */
public final class SummaryRowRenderer {

    private SummaryRowRenderer() {
    }

    /**
     * Render {@code rows} as SINGLE-line rows (no sub Label is ever addressed) into {@code
     * rowSelectorPrefix + 0 .. rowSelectorPrefix + (maxSlots-1)}, hiding unused slots, and return
     * the overflow count ({@code max(0, rows.size() - maxSlots)}) so the caller can populate its
     * own keyed "+N more" label.
     */
    public static int render(@Nonnull UICommandBuilder cmd, @Nonnull String rowSelectorPrefix,
            int maxSlots, @Nonnull List<SummaryRow> rows) {
        return render(cmd, rowSelectorPrefix, maxSlots, rows, null);
    }

    /**
     * As {@link #render(UICommandBuilder, String, int, List)}, additionally painting each row's
     * optional {@link SummaryRow#subText()} into the {@code subLabelId} Label within that row slot
     * (e.g. {@code "#Sub"}), which is SHOWN only for a row that carries a second line and hidden
     * otherwise - so a slot reused across pushes can never keep a stale second line. Pass {@code
     * null} to leave every sub Label untouched (the single-line form above); the consumer's {@code
     * .ui} must declare the named Label in every row slot, since a command against a missing
     * selector crashes the client.
     */
    public static int render(@Nonnull UICommandBuilder cmd, @Nonnull String rowSelectorPrefix,
            int maxSlots, @Nonnull List<SummaryRow> rows, @Nullable String subLabelId) {
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
            if (subLabelId != null) {
                String subSel = rowSel + " " + subLabelId;
                Message subText = row.subText();
                cmd.set(subSel + ".Visible", subText != null);
                if (subText != null) {
                    cmd.set(subSel + ".TextSpans", subText);
                }
            }
        }
        return Math.max(0, rows.size() - shown);
    }

    /**
     * Convenience: also sets an overflow label's visibility + text when {@code overflowSelector} is
     * given. Renders SINGLE-line rows; a caller that wants second lines uses the {@code subLabelId}
     * overload of {@link #render} and sets its own overflow label from the returned count.
     */
    public static void renderWithOverflow(@Nonnull UICommandBuilder cmd, @Nonnull String rowSelectorPrefix,
            int maxSlots, @Nonnull List<SummaryRow> rows, @Nullable String overflowSelector,
            @Nullable Message overflowText) {
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
