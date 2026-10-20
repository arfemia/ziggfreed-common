package com.ziggfreed.common.track;

import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Whose climb on which track, the one key every read and write of track progress goes through
 * ({@link TrackStore}). A record, so a later key (a guild's, a ruleset's, a season's run) is the same shape.
 *
 * @param subject   who climbs: a player today ({@link Subject#player}); a group later, never a bare id
 * @param namespace the ruleset the climb belongs to ({@code ""} the default; reserved, R583): a second mode (a
 *                  hardcore ruleset) keeps its own climb beside the default one
 * @param track     the track id, lower-cased
 * @param run       a seasonal run ({@code ""} for none; reserved): the Almanac's {@code <event>@<year>}, which
 *                  is why a track id may never contain {@code @}
 */
public record TrackKey(@Nonnull Subject subject, @Nonnull String namespace, @Nonnull String track,
                       @Nonnull String run) {

    /** Who climbs: a kind of subject and its id within that kind. */
    public record Subject(@Nonnull String kind, @Nonnull String id) {

        /** The one subject kind filled today. */
        public static final String PLAYER = "player";

        public Subject {
            kind = kind.trim().toLowerCase(Locale.ROOT);
            id = id.trim();
        }

        /** A player, by their id. */
        @Nonnull
        public static Subject player(@Nonnull UUID playerId) {
            return new Subject(PLAYER, playerId.toString());
        }

        /** The player this subject is, or null for another kind or an id that is no player id. */
        @Nullable
        public UUID playerId() {
            if (!PLAYER.equals(kind)) {
                return null;
            }
            try {
                return UUID.fromString(id);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public TrackKey {
        namespace = normalize(namespace);
        track = normalize(track);
        run = normalize(run);
    }

    /** {@code playerId}'s climb on {@code trackId}, in the default namespace, outside any run. */
    @Nonnull
    public static TrackKey player(@Nonnull UUID playerId, @Nonnull String trackId) {
        return new TrackKey(Subject.player(playerId), "", trackId, "");
    }

    /** This key's subject, namespace and run, on the track {@code trackId}: how a derived track names a source's climb. */
    @Nonnull
    public TrackKey onTrack(@Nonnull String trackId) {
        return new TrackKey(subject, namespace, trackId, run);
    }

    /** The player this key's subject is, or null for another kind of subject. */
    @Nullable
    public UUID playerId() {
        return subject.playerId();
    }

    /** Is this the default climb (no namespace, no run)? */
    public boolean isDefault() {
        return namespace.isEmpty() && run.isEmpty();
    }

    /** A key part as every key holds it: trimmed and lower-cased, {@code ""} for none. */
    @Nonnull
    public static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
