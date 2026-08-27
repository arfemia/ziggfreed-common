package com.ziggfreed.common.commerce.page;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.ItemCost;
import com.ziggfreed.common.currency.CurrencyCatalog;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.NumberFormatter;

/**
 * The two repeatable things every commerce screen paints: a CHIP (a picture and a number, side by
 * side) and a LINE (a picture and a sentence, stacked).
 *
 * <p>A wallet reading, a price, a reward and a refusal are all one of those two, so both pages
 * append the same two templates through this one class rather than each growing its own idea of what
 * a price looks like. It is where the balance strip, the cost strip and the detail lines converge,
 * which is what stops a price reading one way on a storefront and another on a board.
 *
 * <p><b>A balance is never afford-coloured and a price always is.</b> A wallet reading is not
 * weighed against anything - it is simply what the player has - while a price exists precisely to be
 * compared to it, so the red on a short component is the whole reason the strip is worth drawing.
 */
public final class CommerceChips {

    /** A picture and a number, laid out along a row. */
    public static final String CHIP_TEMPLATE = "Pages/ZigCommerceChip.ui";

    /** A picture and a sentence, stacked down a panel. */
    public static final String LINE_TEMPLATE = "Pages/ZigDetailLine.ui";

    /** A balance reading: gold, because it is a statement rather than a comparison. */
    public static final String COLOR_BALANCE = "#ffd97a";

    /** A price component the subject can cover. */
    public static final String COLOR_AFFORDABLE = "#c6d4e4";

    /** A price component they cannot. */
    public static final String COLOR_SHORT = "#ff6b6b";

    /** An ordinary line of a detail panel. */
    public static final String COLOR_LINE = "#c6d4e4";

    /** A line that says why something is out of reach. */
    public static final String COLOR_REFUSAL = "#ff9944";

    /** A step or a component that is already satisfied. */
    public static final String COLOR_DONE = "#7affa0";

    /** One chip: an optional picture, a composed line, and the colour that line reads in. */
    public record Chip(@Nullable String iconItemId, @Nonnull Message label, @Nonnull String color) {
    }

    private CommerceChips() {
    }

    // ==================== chips ====================

    /**
     * Paint {@code chips} into {@code container}, at most {@code max} of them (zero or less for all).
     * The container is CLEARED first, so a re-render for a different selection cannot collide with
     * the chips of the last one.
     */
    public static void render(@Nonnull UICommandBuilder cmd, @Nonnull String container,
            @Nonnull List<Chip> chips, int max) {
        cmd.clear(container);
        int limit = max > 0 ? Math.min(max, chips.size()) : chips.size();
        for (int i = 0; i < limit; i++) {
            Chip chip = chips.get(i);
            cmd.append(container, CHIP_TEMPLATE);
            String sel = container + "[" + i + "]";
            cmd.set(sel + " #ChipText.TextSpans", chip.label());
            cmd.set(sel + " #ChipText.Style.TextColor", chip.color());
            applyIcon(cmd, sel + " #ChipIconSlot", sel + " #ChipIcon", chip.iconItemId());
        }
    }

    /**
     * What the subject is carrying, one chip per wallet a storefront or board authored, in authored
     * order. A wallet no layer defines is skipped rather than drawn as a zero, since a reading for
     * something that does not exist is worse than no reading.
     */
    @Nonnull
    public static List<Chip> balances(@Nonnull CurrencyEngine currencies, @Nonnull Subject subject,
            @Nonnull Collection<String> currencyIds, @Nullable CurrencyText.Source names) {
        List<Chip> out = new ArrayList<>();
        CurrencyCatalog catalog = currencies.catalog();
        for (String id : currencyIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            CurrencyDef def = catalog.get(id);
            if (def == null) {
                continue;
            }
            long balance = currencies.balance(subject, def);
            out.add(new Chip(CurrencyText.iconOf(def), Msg.raw(NumberFormatter.grouped(balance)),
                    COLOR_BALANCE));
        }
        return out;
    }

    /**
     * A price, one chip per component, each coloured by whether this subject can cover it right now.
     *
     * <p>Colouring per COMPONENT rather than per price is what makes a two-currency price legible:
     * the player sees which half they are short of instead of a whole row turning red.
     */
    @Nonnull
    public static List<Chip> price(@Nonnull Cost cost, @Nonnull CurrencyEngine currencies,
            @Nonnull Subject subject, @Nullable CurrencyText.Source names) {
        List<Chip> out = new ArrayList<>();
        CurrencyCatalog catalog = currencies.catalog();
        for (Map.Entry<String, Long> entry : cost.currencies().entrySet()) {
            String id = entry.getKey();
            long amount = entry.getValue() == null ? 0L : entry.getValue().longValue();
            CurrencyDef def = catalog.get(id);
            String icon = def == null ? null : CurrencyText.iconOf(def);
            boolean afford = currencies.canAfford(subject, id, amount);
            out.add(new Chip(icon, Msg.raw(NumberFormatter.grouped(amount)),
                    afford ? COLOR_AFFORDABLE : COLOR_SHORT));
        }
        for (ItemCost item : cost.items()) {
            if (item == null || item.isBlank()) {
                continue;
            }
            out.add(new Chip(item.item(), Msg.raw("x" + item.count()), COLOR_AFFORDABLE));
        }
        return out;
    }

    /** What one wallet is called beside how much of it a price wants, for a toast or a status line. */
    @Nonnull
    public static Message amountAndName(@Nonnull CurrencyEngine currencies, @Nonnull String currencyId,
            long amount, @Nullable CurrencyText.Source names) {
        CurrencyDef def = currencies.catalog().get(currencyId);
        Message name = def == null ? Msg.raw(currencyId) : CurrencyText.nameOf(def, names);
        return Msg.cat(Msg.raw(NumberFormatter.grouped(amount) + " "), name);
    }

    /** What one wallet is called, for a refusal that names the thing somebody is short of. */
    @Nonnull
    public static Message nameOf(@Nonnull CurrencyEngine currencies, @Nullable String currencyId,
            @Nullable CurrencyText.Source names) {
        if (currencyId == null || currencyId.isBlank()) {
            return Msg.raw("");
        }
        CurrencyDef def = currencies.catalog().get(currencyId);
        return def == null ? Msg.raw(currencyId) : CurrencyText.nameOf(def, names);
    }

    /** An item's own engine display name, for a refusal naming one. Never throws. */
    @Nonnull
    public static Message itemName(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Msg.raw("");
        }
        try {
            return new ItemStack(itemId, 1).getDisplayName();
        } catch (Throwable ignored) {
            return Msg.raw(itemId);
        }
    }

    // ==================== lines ====================

    /** Append one line row into {@code container} and answer its selector. */
    @Nonnull
    public static String appendLine(@Nonnull UICommandBuilder cmd, @Nonnull String container,
            int index) {
        cmd.append(container, LINE_TEMPLATE);
        return container + "[" + index + "]";
    }

    /** Fill an appended line: its sentence, its colour, and the picture beside it if any. */
    public static void setLine(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
            @Nonnull Message text, @Nonnull String color, @Nullable String iconItemId) {
        cmd.set(sel + " #LineText.TextSpans", text);
        cmd.set(sel + " #LineText.Style.TextColor", color);
        applyIcon(cmd, sel + " #LineIconSlot", sel + " #LineIcon", iconItemId);
    }

    /**
     * Show an item's picture in a slot, or hide the slot when there is nothing to show. A row with
     * no picture reads as its line alone rather than borrowing an unrelated item's art, which would
     * read as a promise of that item.
     */
    private static void applyIcon(@Nonnull UICommandBuilder cmd, @Nonnull String slotSelector,
            @Nonnull String gridSelector, @Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            cmd.set(slotSelector + ".Visible", false);
            return;
        }
        try {
            cmd.set(gridSelector + ".Slots", List.of(new ItemGridSlot(new ItemStack(itemId, 1))));
            cmd.set(slotSelector + ".Visible", true);
        } catch (Throwable ignored) {
            // An id nothing answers to costs the picture, never the row.
            cmd.set(slotSelector + ".Visible", false);
        }
    }
}
