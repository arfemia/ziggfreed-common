package com.ziggfreed.common.instance.result;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * One labelled score line on a {@link PlayerResultRow}: a client-resolved label
 * {@link Message} + a raw value + how to {@link ColumnFormat format} it. The consumer
 * builds these from its own per-player score (Kweebec maps each {@code PlayerScore}
 * component - time / damage-avoided / stuns - to a column); the page renders
 * "label : formatted-value" without knowing the game's scoring.
 */
public record ScoreColumn(@Nonnull Message label, long value, @Nonnull ColumnFormat format) {

    /** A grouped-number column (the common default). */
    @Nonnull
    public static ScoreColumn of(@Nonnull Message label, long value) {
        return new ScoreColumn(label, value, ColumnFormat.GROUPED);
    }

    /** The display string for {@link #value} per {@link #format}. */
    @Nonnull
    public String formatted() {
        return format.render(value);
    }
}
