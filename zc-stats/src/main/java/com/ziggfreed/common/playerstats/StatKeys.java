package com.ziggfreed.common.playerstats;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.counter.Counters;
import com.ziggfreed.common.counter.PackedTallies;

/**
 * The ONE way a tally is named in the player stat store. A stat's grand total is its id
 * ({@code Mob_Kills}); one line of its breakdown is {@code <stat>/<thing>} ({@code Mob_Kills/Skeleton}),
 * the counter package's own category key ({@link Counters#key}), so the store reads like every other
 * tally bag in the library and the {@code ziggfreedcommon:counter} factor reads it with no translation.
 * Keys are matched without regard to case and kept in the spelling they were first written in.
 *
 * <p>A stat id may not carry {@code /} (the breakdown join), {@code @} (the season-year mark the
 * {@code Seasons} field's {@code <event>@<year>/<stat key>} keys use),
 * {@code |} (the save string's pair join) or {@code :} (its value join), and is never blank:
 * {@link #usableStatId} is the one check. A thing may carry anything but {@code |}; a thing that
 * cannot be saved costs only its breakdown line, never the total.
 */
public final class StatKeys {

    private StatKeys() {
    }

    /** Can {@code statId} name a stat without breaking a key or the save string? */
    public static boolean usableStatId(@Nullable String statId) {
        if (statId == null || statId.isBlank()) {
            return false;
        }
        String trimmed = statId.trim();
        return trimmed.indexOf('/') < 0 && trimmed.indexOf('@') < 0 && trimmed.indexOf(':') < 0
                && PackedTallies.usableKey(trimmed);
    }

    /** Can {@code thing} name one breakdown line? Not blank and savable. */
    public static boolean usableThing(@Nullable String thing) {
        return thing != null && !thing.isBlank() && PackedTallies.usableKey(thing);
    }

    /** The grand total's key: the stat id itself, trimmed. */
    @Nonnull
    public static String total(@Nonnull String statId) {
        return statId.trim();
    }

    /** One breakdown line's key, {@code <stat>/<thing>}; the bare total for a blank thing. */
    @Nonnull
    public static String line(@Nonnull String statId, @Nullable String thing) {
        return thing == null || thing.isBlank() ? total(statId) : Counters.key(total(statId), thing.trim());
    }
}
