package com.ziggfreed.common.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/**
 * Paints tier cards ({@code Pages/ZigTierCard.ui}, the game's Chapter card): a host laid out Left is cleared and one
 * card appended per {@link TierCard}, each ONE button (R1180), addressed {@code host[i] #Card}. An open card shows its
 * tag, its title under the game's gold text gradient, its copper line, its week, what it pays (the head, the pay slots
 * through {@link TilePainter#paySlots} into its {@code #PayGrid}, the note), its pay mark ({@link PayMarkPainter}) and
 * its go-in plate, carries its pay line as its own tooltip, and is bound to {@code binding}'s answer; it lights gold
 * under the cursor by its style alone ({@code ZigTierCardStyle}). A card shown only to be read
 * ({@link TierCard#toRead()}, no go-in word: a week board's) is the same template with nothing to click: unbound, and
 * swapped to {@code ZigTierCardReadStyle}, the plain tile in every state, so it never lights gold. A locked card draws
 * its title in the dim ink, its week in the muted ink and its pay pictures dimmed, shows the padlock and the lock's
 * lines in place of the mark and the plate, and is switched off ({@code .Disabled}): the style's empty Memories tile,
 * no click, no tooltip, no binding. Nothing inside a card carries a tooltip, so a click anywhere on an open card
 * reaches it.
 */
public final class TierCardPainter {

    /** One appended card. */
    public static final String TEMPLATE = "Pages/ZigTierCard.ui";

    /** A card's size and the gap to the next one, as its template writes them. */
    public static final int WIDTH = 248;
    public static final int HEIGHT = 576;
    public static final int GAP = 24;

    /** One card and its gap: a row of n cards is n steps wide, less the last gap. */
    public static final int STEP = WIDTH + GAP;

    /** How many pay slots a card draws: two rows of two, the card's {@code #PayGrid}. The tooltip names them all. */
    public static final int PAYS_SHOWN = 4;

    private TierCardPainter() {
    }

    /**
     * Clear {@code host} and paint {@code cards} into it, binding each open card that has a go-in word to
     * {@code binding}'s answer (null leaves it unbound); a card shown only to be read is never bound.
     *
     * @return each card's button selector, in order
     */
    @Nonnull
    public static List<String> paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String host, @Nonnull List<TierCard> cards, @Nonnull Function<TierCard, EventData> binding) {
        cmd.clear(host);
        List<String> selectors = new ArrayList<>(cards.size());
        for (int i = 0; i < cards.size(); i++) {
            TierCard card = cards.get(i);
            cmd.append(host, TEMPLATE);
            String c = KitPaint.child(host, i) + " #Card";
            paintCard(cmd, c, card);
            EventData data = card.locked() || card.toRead() ? null : binding.apply(card);
            if (data != null) {
                events.addEventBinding(CustomUIEventBindingType.Activating, c, data);
            }
            selectors.add(c);
        }
        return selectors;
    }

    /** One appended card's parts, at its button {@code c}. */
    private static void paintCard(@Nonnull UICommandBuilder cmd, @Nonnull String c, @Nonnull TierCard card) {
        boolean locked = card.locked();
        KitPaint.optional(cmd, c + " #Tag", card.tag());
        cmd.set(c + " #TitleMask.Visible", !locked);
        cmd.set(c + " #TitleDim.Visible", locked);
        KitPaint.text(cmd, c + (locked ? " #TitleDim" : " #Title"), card.title());
        KitPaint.optional(cmd, c + " #Sub", card.sub());
        if (card.week() != null) {
            KitPaint.text(cmd, c + " #Week", card.week());
        }
        if (locked) {
            cmd.set(c + " #Week.Style.TextColor", ZigTokens.INK_MUTED);
        }

        List<PaySlot> pays = shown(card.pays());
        boolean paying = !pays.isEmpty();
        KitPaint.optional(cmd, c + " #PaysHead", paying ? card.paysHead() : null);
        cmd.set(c + " #PayGrid.Visible", paying);
        if (paying) {
            TilePainter.paySlots(cmd, c + " #PayGrid", pays, locked);
        }
        KitPaint.optional(cmd, c + " #PayNote", paying ? card.payNote() : null);

        PayMark mark = locked ? null : card.mark();
        cmd.set(c + " #Mark.Visible", mark != null);
        if (mark != null) {
            PayMarkPainter.paint(cmd, c + " #Mark", mark);
        }
        boolean go = !locked && card.go() != null;
        cmd.set(c + " #GoPlate.Visible", go);
        if (go) {
            KitPaint.text(cmd, c + " #GoLabel", card.go());
        }

        cmd.set(c + " #LockBlock.Visible", locked);
        if (locked) {
            KitPaint.optional(cmd, c + " #LockLine", card.lock().line());
            KitPaint.optional(cmd, c + " #LockHow", card.lock().how());
            cmd.set(c + ".Disabled", true);
            return;
        }
        if (card.toRead()) {
            // Nothing to click: the same card on the plain tile in every state, no gold under the cursor.
            ZigStyles.apply(cmd, c + ".Style", ZigStyles.Name.TIER_CARD_READ, null);
        }
        if (card.tooltip() != null) {
            KitPaint.tooltip(cmd, c, card.tooltip());
        }
    }

    /** The pay slots a card draws: the first {@link #PAYS_SHOWN}. */
    @Nonnull
    static List<PaySlot> shown(@Nullable List<PaySlot> pays) {
        if (pays == null || pays.isEmpty()) {
            return List.of();
        }
        return pays.size() <= PAYS_SHOWN ? pays : pays.subList(0, PAYS_SHOWN);
    }
}
