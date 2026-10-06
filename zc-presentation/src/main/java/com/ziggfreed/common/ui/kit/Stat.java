package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/**
 * A header stat ({@code @ZigStat}): the figure, its caption, and a tone ({@link Tone#COLLECT} reads gold, as points
 * do; {@link Tone#NEUTRAL} is plain).
 */
public record Stat(@Nonnull Message value, @Nonnull Message label, @Nonnull Tone tone) {

    public Stat {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(label, "label");
        tone = tone == null ? Tone.NEUTRAL : tone;
    }
}
