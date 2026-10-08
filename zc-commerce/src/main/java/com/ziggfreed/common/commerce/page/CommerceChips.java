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
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.icon.IconRenderer;
import com.ziggfreed.common.subject.Subject;

/**
 * The two repeatable things every commerce screen paints: a CHIP (a picture and how much, side by
 * side, as wide as its words) and a LINE (a picture and a sentence, stacked).
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

    /** A picture and how much, laid out along a row or stacked one per line. */
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

    /**
     * One chip: an optional picture, a composed line, the colour that line reads in, and what hovering
     * it says when the picture only stands for something else. A wallet's {@code Icon} is an item id
     * borrowed for its picture, so a wallet chip carries the wallet's name here and draws its picture
     * plain: an item slot would name the borrowed ITEM on hover. Null for an item price, whose item
     * slot and its own tooltip are the point.
     */
    public record Chip(@Nullable String iconItemId, @Nonnull Message label, @Nonnull String color,
            @Nullable Message tooltip) {

        /** A chip whose picture, when it has one, is the item itself. */
        public Chip(@Nullable String iconItemId, @Nonnull Message label, @Nonnull String color) {
            this(iconItemId, label, color, null);
        }
    }

    private CommerceChips() {
    }

    // ==================== chips ====================

    /**
     * Paint {@code chips} into {@code container}, at most {@code max} of them (zero or less for all).
     * The container is CLEARED first, so a re-render for a different selection cannot collide with
     * the chips of the last one. Each chip is as wide as its words, laid along a {@code Left}
     * container or stacked one per line in a {@code Top} one.
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
            if (chip.tooltip() == null) {
                applyIcon(cmd, sel + " #ChipIconSlot", sel + " #ChipIcon", chip.iconItemId());
                continue;
            }
            // A wallet: its picture drawn plain, and the chip's box names the wallet on hover.
            cmd.set(sel + " #ChipIcon.Visible", false);
            cmd.set(sel + " #ChipIconSlot.Visible",
                    IconRenderer.applyPlainIcon(cmd, sel + " #ChipIconSlot", chip.iconItemId(), null));
            UiText.setText(cmd, sel + " #ChipBox.TooltipText", chip.tooltip());
        }
    }

    /**
     * What the subject is carrying, one chip per wallet a storefront or board authored, in authored
     * order, each reading its wallet's amount line ("7 Hallow Sweets", {@link CurrencyText#amountOf})
     * with the wallet's name on hover. A wallet no layer defines is skipped rather than drawn as a
     * zero, since a reading for something that does not exist is worse than no reading.
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
            // A typed numeric param inside the line, so the player's own client decides the digits.
            out.add(new Chip(CurrencyText.iconOf(def), CurrencyText.amountOf(def, balance, names), COLOR_BALANCE,
                    CurrencyText.nameOf(def, names)));
        }
        return out;
    }

    /**
     * A price, one chip per component, each coloured by whether this subject can cover it right now.
     *
     * <p>Colouring per COMPONENT rather than per price is what makes a two-currency price legible:
     * the player sees which half they are short of instead of a whole row turning red. A wallet's
     * component reads its amount line ("6 Hallow Sweets"), so a price names what it costs in.
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
            out.add(new Chip(icon, def == null ? Msg.num(amount) : CurrencyText.amountOf(def, amount, names),
                    afford ? COLOR_AFFORDABLE : COLOR_SHORT,
                    def == null ? null : CurrencyText.nameOf(def, names)));
        }
        for (ItemCost item : cost.items()) {
            if (item == null || item.isBlank()) {
                continue;
            }
            out.add(new Chip(item.item(), Msg.raw("x" + item.count()), COLOR_AFFORDABLE));
        }
        return out;
    }

    /**
     * The full-registered id of the PRICE composition key ({@code "{0, number} {1}"}): an amount
     * beside a wallet's name with NO sign, the amount a typed numeric param so the player's own
     * client decides the digit grouping, the name a nested translated {@link Message}.
     */
    private static final String PRICE_AMOUNT_KEY = "ziggfreedcommon.commerce.price.amount_and_name";

    /**
     * The full-registered id of the REWARD-row composition key ({@code "+{0, number} {1}"}): the
     * same amount-beside-name reading with the leading plus a gain line carries and a price never
     * does. The two are deliberately separate keys, because a price and a reward must never share
     * wording.
     */
    private static final String REWARD_AMOUNT_KEY = "ziggfreedcommon.commerce.reward.amount_and_name";

    /**
     * "{@code <amount> <name>}", the PRICE reading: no sign, digits grouped by each player's own
     * client. NEVER compose this as {@code raw(NumberFormatter.grouped(amount)) + name}: that
     * bakes one server-side grouping into every locale, which is exactly the defect the shared
     * key exists to prevent.
     */
    @Nonnull
    public static Message priceAmount(long amount, @Nonnull Message name) {
        return Msg.key(PRICE_AMOUNT_KEY, amount, name);
    }

    /** "{@code +<amount> <name>}", the REWARD-row reading: the plus a gain line carries. */
    @Nonnull
    public static Message rewardAmount(long amount, @Nonnull Message name) {
        return Msg.key(REWARD_AMOUNT_KEY, amount, name);
    }

    /**
     * How much of one wallet a price wants, for a toast or a status line: the same reading as a price
     * chip ({@link CurrencyText#amountOf}, the wallet's own amount line when it ships one), so a
     * confirm line and the chip above it never word one price two ways.
     */
    @Nonnull
    public static Message amountAndName(@Nonnull CurrencyEngine currencies, @Nonnull String currencyId,
            long amount, @Nullable CurrencyText.Source names) {
        CurrencyDef def = currencyId.isBlank() ? null : currencies.catalog().get(currencyId);
        return def == null ? priceAmount(amount, nameOf(currencies, currencyId, names))
                : CurrencyText.amountOf(def, amount, names);
    }

    /**
     * As {@link #amountAndName}, in the REWARD-row reading ("+50 Bounty Tokens"): what a payout
     * line says a player gains, never what a price asks.
     */
    @Nonnull
    public static Message rewardAmountAndName(@Nonnull CurrencyEngine currencies,
            @Nonnull String currencyId, long amount, @Nullable CurrencyText.Source names) {
        return rewardAmount(amount, nameOf(currencies, currencyId, names));
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

    /** Fill an appended line that has no picture beside it: its sentence and its colour. */
    public static void setLine(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
            @Nonnull Message text, @Nonnull String color) {
        setLine(cmd, sel, text, color, null);
    }

    /**
     * Fill an appended line and the picture beside it. The picture is a spec rather than an item id
     * so a line can show a creature's own portrait as readily as an item, which an id alone cannot
     * express; a null one leaves the slot collapsed.
     */
    public static void setLine(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
            @Nonnull Message text, @Nonnull String color, @Nullable IconSpec icon) {
        cmd.set(sel + " #LineText.TextSpans", text);
        cmd.set(sel + " #LineText.Style.TextColor", color);
        cmd.set(sel + " #LineIconSlot.Visible", IconRenderer.applyIcon(cmd, sel, icon));
    }

    /**
     * Fill an appended line with one reward as {@code chip} reads it. The item it hands over draws in
     * the line's item slot with that item's own tooltip; a picture that only stands for the reward (a
     * wallet's icon, a reputation's) draws plain and names the reward on hover, never the item it
     * borrows.
     */
    public static void setRewardLine(@Nonnull UICommandBuilder cmd, @Nonnull String sel,
            @Nonnull RewardChip chip, @Nonnull String color) {
        if (chip.showsItem() || !chip.hasIcon()) {
            setLine(cmd, sel, chip.label(), color, chip.icon());
            return;
        }
        cmd.set(sel + " #LineText.TextSpans", chip.label());
        cmd.set(sel + " #LineText.Style.TextColor", color);
        cmd.set(sel + " " + IconRenderer.ITEM_ICON_ID + ".Visible", false);
        cmd.set(sel + " #LineIconSlot.Visible", IconRenderer.applyPlainIcon(cmd, sel, chip.iconItemId(), null));
        UiText.setText(cmd, sel + " #LineIconSlot.TooltipText", chip.tooltip());
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
