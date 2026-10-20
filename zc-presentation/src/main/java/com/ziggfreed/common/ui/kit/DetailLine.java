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
 *
 * <p>{@code quality} is the game's item quality id a reward card shows the line as ({@code Rare}): its slot, word and
 * colour on an item's card in place of the item's own quality, its word on any other card ({@link TilePainter}); null
 * for none, where an item's card shows the item's own quality and any other card no word. Every other painter leaves
 * it alone.
 */
public record DetailLine(@Nonnull Picture picture, @Nonnull Message text, @Nullable Message count, @Nullable Pill tag,
        @Nonnull Tick tick, @Nullable String selectId, boolean current, @Nullable Message tooltip,
        @Nullable String quality) {

    public DetailLine {
        Objects.requireNonNull(text, "text");
        picture = picture == null ? Picture.NONE : picture;
        tick = tick == null ? Tick.NONE : tick;
        selectId = selectId == null || selectId.isBlank() ? null : selectId;
        quality = quality == null || quality.isBlank() ? null : quality;
    }

    /** A line naming no quality, the common case. */
    public DetailLine(@Nonnull Picture picture, @Nonnull Message text, @Nullable Message count, @Nullable Pill tag,
            @Nonnull Tick tick, @Nullable String selectId, boolean current, @Nullable Message tooltip) {
        this(picture, text, count, tag, tick, selectId, current, tooltip, null);
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
        return new DetailLine(picture, text, count, tag, tick, selectId, current, tooltip, quality);
    }

    /** This line shown as the item quality {@code quality} names on a reward card (null or blank for none). */
    @Nonnull
    public DetailLine withQuality(@Nullable String quality) {
        return new DetailLine(picture, text, count, tag, tick, selectId, current, tooltip, quality);
    }

    /**
     * This line opening {@code selectId} (the page's binding carries it; null or blank for none), so a click anywhere
     * on it reaches the page. A picture's own words are left off a line that opens something, while an item picture
     * keeps its item tooltip ({@link DetailPainter}).
     */
    @Nonnull
    public DetailLine withSelect(@Nullable String selectId) {
        return new DetailLine(picture, text, count, tag, tick, selectId, current, tooltip, quality);
    }
}
