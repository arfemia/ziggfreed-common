package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.icon.IconSpec;

/**
 * What a row on the shared progress-bar panel looks like once every layer has had its say: the
 * {@link HudBarAsset} authored for the row (if any) over the {@link HudBarDisplay} its movement
 * came with, over the defaults written here. Every part is settled; the panel paints from this
 * and nothing else.
 *
 * <p>The precedence is the override's, because an authored file is a server owner's or a pack's
 * deliberate word about one row, and the reporting mod's display is what a row reads when nobody
 * has said anything about it. A row with no name from either layer shows its own id, so an
 * unnamed row is still a row rather than a blank line.
 */
public record HudBarLook(@Nonnull Message label, @Nullable IconSpec icon, @Nonnull String color, int order,
        long lingerMs) {

    /** The fill colour a row draws in when neither its override nor its display names one. */
    public static final String DEFAULT_COLOR = "#7fb2e0";

    /** Where a row that nothing ordered sorts: after every row that named a place. */
    public static final int DEFAULT_ORDER = 1000;

    /** How long a row stays up after its last move when nothing says otherwise. */
    public static final long DEFAULT_LINGER_MS = 5000L;

    /**
     * Fold {@code override} (null when nothing is authored for the row) over {@code display} over
     * the defaults, for the row moved under {@code id}.
     */
    @Nonnull
    public static HudBarLook resolve(@Nonnull String id, @Nullable HudBarAsset override,
            @Nonnull HudBarDisplay display) {
        HudBarDisplay folded = override == null ? display : override.display().over(display);
        Long linger = folded.lingerMs();
        return new HudBarLook(
                folded.label() != null ? folded.label() : Msg.raw(id),
                folded.icon(),
                folded.color() != null ? folded.color() : DEFAULT_COLOR,
                folded.order() != null ? folded.order() : DEFAULT_ORDER,
                linger != null && linger > 0 ? linger : DEFAULT_LINGER_MS);
    }
}
