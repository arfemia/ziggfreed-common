package com.ziggfreed.common.content;

import java.util.Locale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;

/**
 * When a change saved into the server content pack reaches players, so an editor can say so beside its
 * Save. It is a report of what the engine does with the change, never a switch.
 *
 * <ul>
 *   <li>{@link #NOW}: live for everyone once the save's completion lands: an asset written or removed
 *       (the store's own fold runs, as for any pack file), a new text key (pushed to every player
 *       online, and in every joining player's catalogue), a text left as it was.</li>
 *   <li>{@link #NEXT_JOIN}: in the server's catalogue at once, on a player's screen from their next join.</li>
 *   <li>{@link #RESTART}: after the server restarts. A text key the server already loaded keeps its
 *       first value until then (the engine's catalogue keeps the first definition of a key and never
 *       unloads one), so an edited or removed text shows the old words until a restart.</li>
 * </ul>
 */
public enum AppliesWhen {
    NOW,
    NEXT_JOIN,
    RESTART;

    private static final String PREFIX = "ziggfreedcommon.content.applies.";

    /** The sentence an editor shows beside its Save ("Saved. This applies after a restart."). */
    @Nonnull
    public Message notice() {
        return Msg.key(PREFIX + name().toLowerCase(Locale.ROOT));
    }

    /** The later of two: a save of several parts applies when its slowest part does. */
    @Nonnull
    public AppliesWhen orLater(@Nonnull AppliesWhen other) {
        return other.ordinal() > ordinal() ? other : this;
    }
}
