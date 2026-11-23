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
 * <p>{@code quality} is the game's item quality id a reward card shows the line as ({@code Rare}): its slot art, word
 * and colour, on an item's card in place of the item's own quality and on any other card alike ({@link TilePainter});
 * null for none, where an item's card shows the item's own quality and any other card none. On a reward card the
 * line's {@code tag} is the word over its picture saying where the reward came from ("Boss"). Every other painter
 * leaves {@code quality} alone.
 *
 * <p>{@code sub} is a line under the text (a bullet's line under its name, {@link #bullet}), drawn a step smaller in
 * the body ink, one line; null for none, and the line stays one line high.
 */
public record DetailLine(@Nonnull Picture picture, @Nonnull Message text, @Nullable Message count, @Nullable Pill tag,
        @Nonnull Tick tick, @Nullable String selectId, boolean current, @Nullable Message tooltip,
        @Nullable String quality, @Nullable Message sub) {

    public DetailLine {
        Objects.requireNonNull(text, "text");
        picture = picture == null ? Picture.NONE : picture;
        tick = tick == null ? Tick.NONE : tick;
        selectId = selectId == null || selectId.isBlank() ? null : selectId;
        quality = quality == null || quality.isBlank() ? null : quality;
    }

    /** A line with no line under its text, the common case. */
    public DetailLine(@Nonnull Picture picture, @Nonnull Message text, @Nullable Message count, @Nullable Pill tag,
            @Nonnull Tick tick, @Nullable String selectId, boolean current, @Nullable Message tooltip,
            @Nullable String quality) {
        this(picture, text, count, tag, tick, selectId, current, tooltip, quality, null);
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
     * A bullet: the Chapter look's diamond ({@link Picture#BULLET}) before {@code name}, in the bullet name style, and
     * {@code line} under it (null for none), as a gate lists this week's omens.
     */
    @Nonnull
    public static DetailLine bullet(@Nonnull Message name, @Nullable Message line) {
        return new DetailLine(Picture.BULLET, name, null, null, Tick.NONE, null, false, null, null, line);
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
        return new DetailLine(picture, text, count, tag, tick, selectId, current, tooltip, quality, sub);
    }

    /** This line shown as the item quality {@code quality} names on a reward card (null or blank for none). */
    @Nonnull
    public DetailLine withQuality(@Nullable String quality) {
        return new DetailLine(picture, text, count, tag, tick, selectId, current, tooltip, quality, sub);
    }

    /**
     * This line opening {@code selectId} (the page's binding carries it; null or blank for none), so a click anywhere
     * on it reaches the page. A picture's own words are left off a line that opens something, while an item picture
     * keeps its item tooltip ({@link DetailPainter}).
     */
    @Nonnull
    public DetailLine withSelect(@Nullable String selectId) {
        return new DetailLine(picture, text, count, tag, tick, selectId, current, tooltip, quality, sub);
    }

    /** This line with {@code sub} under its text (null for none). */
    @Nonnull
    public DetailLine withSub(@Nullable Message sub) {
        return new DetailLine(picture, text, count, tag, tick, selectId, current, tooltip, quality, sub);
    }
}
