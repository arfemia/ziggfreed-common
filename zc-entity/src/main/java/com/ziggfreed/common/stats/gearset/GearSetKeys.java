package com.ziggfreed.common.stats.gearset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The modifier KEY scheme the gear-set engine writes under: {@code zigset:<setId>:<tierIndex>:<offset>}.
 *
 * <p>A tier is addressed by its POSITION in the set's {@code Bonuses} list, not by its counts, because
 * two tiers may share a count ({@code Armor 4} with an effect, and {@code Armor 4, Held 1} with the
 * blade's stats). The offset is the modifier's position inside the tier's array for that stat, the
 * engine's own per-array-position keying. Every key this engine ever writes or sweeps starts with
 * {@link #PREFIX}, so a sweep can never reach the bridge's own {@code ziggfreedcommon:} keys or a
 * consumer's.
 */
public final class GearSetKeys {

    /** What every gear-set modifier key starts with. */
    public static final String PREFIX = "zigset:";

    private GearSetKeys() {
    }

    /** A set id as every key, table and event spells it: trimmed and lower-cased, the fold's own form. */
    @Nonnull
    public static String setId(@Nonnull String authored) {
        return authored.trim().toLowerCase(Locale.ROOT);
    }

    /** One tier of one set, the unit a modifier prefix, an applied effect and a notice are keyed by. */
    public record TierRef(@Nonnull String setId, int tierIndex) {

        public TierRef {
            setId = GearSetKeys.setId(setId);
        }

        /** The key prefix this tier's modifiers are written under, up to and including the last colon. */
        @Nonnull
        public String prefix() {
            return PREFIX + setId + ':' + tierIndex + ':';
        }
    }

    /** True when {@code key} is one of this engine's, whichever set or tier it belongs to. */
    public static boolean isOurs(@Nullable String key) {
        return key != null && key.startsWith(PREFIX);
    }
}
