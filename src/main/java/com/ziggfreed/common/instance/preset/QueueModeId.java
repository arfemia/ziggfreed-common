package com.ziggfreed.common.instance.preset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The three fixed ways a player can enter an instance preset's matchmaking, surfaced as
 * the cards on the {@code PlayModePage}. The IDENTITY (and its launch behaviour) is fixed
 * - a consumer's {@code PlayModeHandler} maps each to a distinct lobby path - but WHICH
 * modes are offered, their icon, order, and label are authored per preset
 * ({@link QueueModeSet}).
 *
 * <ul>
 *   <li>{@link #PUBLIC} - the shared preset queue; backfills with strangers.</li>
 *   <li>{@link #PARTY} - opens the party manager; the owner queues the group (public backfill or sealed-private).</li>
 *   <li>{@link #SOLO} - launches immediately, alone, with no fill window.</li>
 * </ul>
 */
public enum QueueModeId {
    PUBLIC,
    PARTY,
    SOLO;

    /** Parse a case-insensitive authored/event id; {@code null} on blank/unknown. */
    @Nullable
    public static QueueModeId fromString(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        switch (s.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "PUBLIC":
                return PUBLIC;
            case "PARTY":
                return PARTY;
            case "SOLO":
                return SOLO;
            default:
                return null;
        }
    }

    /** The lowercase wire id used in UI event data ({@code "public"}/{@code "party"}/{@code "solo"}). */
    @Nonnull
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
