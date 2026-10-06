package com.ziggfreed.common.ui;

import javax.annotation.Nonnull;

import com.ziggfreed.common.ui.kit.Tone;

/**
 * The ONE status-colour vocabulary shared progression and commerce surfaces paint state with, so a
 * quest row, an achievement row and an NPC quest list all say "ready" / "in progress" / "locked"
 * in the same colour instead of each page keeping its own near-miss hexes.
 *
 * <p>Seven tones, each a meaning rather than a widget:
 * <ul>
 *   <li>{@link #READY} - something positive is here: hand it in, or it is done.</li>
 *   <li>{@link #COLLECT} - rewards are waiting to be collected.</li>
 *   <li>{@link #AVAILABLE} - can be started or taken right now.</li>
 *   <li>{@link #IN_PROGRESS} - being worked on.</li>
 *   <li>{@link #SOFT_BLOCK} - not actionable here or yet (requirements, wrong place); nothing is
 *       wrong, it just is not open.</li>
 *   <li>{@link #LIMITED} - capped or waiting out a clock; it comes back on its own.</li>
 *   <li>{@link #LOCKED} - hard-refused.</li>
 * </ul>
 *
 * <p>Each answers to the kit's {@link Tone} of the same meaning and reads its text colour from there (the kit's
 * {@code ZigTokens}, held to {@code Common/ZigTokens.ui}), so these names and the kit's documents are one palette.
 * A page pushes {@link #hex()} onto a label's {@code .Style.TextColor} or a dot's {@code .Background}; nothing here
 * touches the UI itself. A page on the kit swaps a state style instead ({@link Tone#stateStyle()}).
 */
public enum StatusTones {

    READY(Tone.DONE),
    COLLECT(Tone.COLLECT),
    AVAILABLE(Tone.AVAILABLE),
    IN_PROGRESS(Tone.ACTIVE),
    SOFT_BLOCK(Tone.BLOCKED),
    LIMITED(Tone.WAITING),
    LOCKED(Tone.DANGER);

    private final Tone tone;

    StatusTones(@Nonnull Tone tone) {
        this.tone = tone;
    }

    /** The tone's colour as a six-digit {@code #rrggbb} hex: its kit tone's text colour. */
    @Nonnull
    public String hex() {
        return tone.textHex();
    }

    /** The kit tone this status answers to. */
    @Nonnull
    public Tone tone() {
        return tone;
    }
}
