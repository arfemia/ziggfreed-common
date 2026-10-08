package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.loot.reward.RewardChip;

/**
 * One line of a detail block: a picture, the text, an optional count at the end ("3 / 5"), an optional pill
 * ("Waiting"), the tick glyph at the start, {@code selectId} when the line opens something (the page's binding
 * carries it), {@code current} for the line that is the reader's own place (a ladder's current tier), and
 * {@code tooltip}, words shown on hovering the line's picture when the picture says nothing of its own (a currency
 * or boost reward). The tooltip is left off a line that opens something, since it would take the line's click,
 * and off an item picture that keeps its item tooltip ({@link Picture#tooltipItem}).
 */
public record DetailLine(@Nonnull Picture picture, @Nonnull Message text, @Nullable Message count, @Nullable Pill tag,
        @Nonnull Tick tick, @Nullable String selectId, boolean current, @Nullable Message tooltip) {

    public DetailLine {
        Objects.requireNonNull(text, "text");
        picture = picture == null ? Picture.NONE : picture;
        tick = tick == null ? Tick.NONE : tick;
        selectId = selectId == null || selectId.isBlank() ? null : selectId;
    }

    /** A line with no tooltip of its own, the common case. */
    public DetailLine(@Nonnull Picture picture, @Nonnull Message text, @Nullable Message count, @Nullable Pill tag,
            @Nonnull Tick tick, @Nullable String selectId, boolean current) {
        this(picture, text, count, tag, tick, selectId, current, null);
    }

    /** A plain line: a picture and its text. */
    @Nonnull
    public static DetailLine of(@Nonnull Picture picture, @Nonnull Message text) {
        return new DetailLine(picture, text, null, null, Tick.NONE, null, false);
    }

    /**
     * One reward's line, {@code tag} at its end: the item it hands over keeps that item's own tooltip
     * ({@link Picture#tooltipItem}); a picture that only stands for the reward (a wallet's icon, a reputation's)
     * draws plain and says the chip's own tooltip on hover, never the name of the item it borrows; no picture
     * reads as the line alone.
     */
    @Nonnull
    public static DetailLine reward(@Nonnull RewardChip chip, @Nullable Pill tag) {
        Picture picture = !chip.hasIcon() ? Picture.NONE
                : chip.showsItem() ? Picture.tooltipItem(chip.iconItemId()) : Picture.item(chip.iconItemId());
        return new DetailLine(picture, chip.label(), null, tag, Tick.NONE, null, false,
                chip.showsItem() ? null : chip.tooltip());
    }

    /** This line with {@code tooltip} on its picture. */
    @Nonnull
    public DetailLine withTooltip(@Nullable Message tooltip) {
        return new DetailLine(picture, text, count, tag, tick, selectId, current, tooltip);
    }
}
