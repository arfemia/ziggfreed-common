package com.ziggfreed.common.ui;

import javax.annotation.Nonnull;

/**
 * The ONE status-colour vocabulary shared progression and commerce surfaces paint state with, so a
 * quest row, an achievement row and an NPC quest list all say "ready" / "in progress" / "locked"
 * in the same colour instead of each page keeping its own near-miss hexes.
 *
 * <p>Six tones, each a meaning rather than a widget:
 * <ul>
 *   <li>{@link #READY} - something positive is here: collect it, hand it in, or it is done.</li>
 *   <li>{@link #AVAILABLE} - can be started or taken right now.</li>
 *   <li>{@link #IN_PROGRESS} - being worked on.</li>
 *   <li>{@link #SOFT_BLOCK} - not actionable here or yet (requirements, wrong place); nothing is
 *       wrong, it just is not open.</li>
 *   <li>{@link #LIMITED} - capped or waiting out a clock; it comes back on its own.</li>
 *   <li>{@link #LOCKED} - hard-refused.</li>
 * </ul>
 *
 * <p>A page pushes {@link #hex()} onto a label's {@code .Style.TextColor} or a dot's
 * {@code .Background}; nothing here touches the UI itself.
 */
public enum StatusTones {

    READY("#4aff7f"),
    AVAILABLE("#ffaa4a"),
    IN_PROGRESS("#4a9eff"),
    SOFT_BLOCK("#96a9be"),
    LIMITED("#c47fff"),
    LOCKED("#ff6b6b");

    private final String hex;

    StatusTones(@Nonnull String hex) {
        this.hex = hex;
    }

    /** The tone's colour as a six-digit {@code #rrggbb} hex. */
    @Nonnull
    public String hex() {
        return hex;
    }
}
