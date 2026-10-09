package com.ziggfreed.common.quest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.ObjectiveProgressState;

/**
 * Packs everything the engine records about one quest for one player into the single opaque string a
 * {@link QuestProgressStore} persists, and reads it back.
 *
 * <p>The format is {@code base64("objId:current/required,objId:current/required,...")}, optionally
 * followed by {@code "|@site=<siteId>"} - the place the quest was taken from, for a quest that must
 * be brought back to it - and {@code "|@run=<year>#<number>"}, the run a once-a-run quest that does
 * not carry over was taken in; each header segment is read by its name. Three characters are
 * therefore RESERVED inside an objective id -
 * {@code ,} {@code :} and (by the store's own convention) the record separators it may add - which is
 * exactly what {@link QuestProgressStore#usesReservedDelimiter} exists to reject at content-load
 * time. Authoring an id containing one silently truncates that objective's progress on the next
 * round trip.
 *
 * <p><b>The site rides INSIDE the payload rather than beside it</b>, so every store that already
 * persists this one string carries it with no new field, no migration and no capability probe. A
 * payload written before the site existed simply has no {@code |} in it and reads back with a null
 * site, which is what makes an already-stored blob decode unchanged.
 *
 * <p>Both directions are total: an unreadable payload decodes to an EMPTY map rather than throwing,
 * so a corrupted entry costs one quest's progress and not the player's session. A trailing segment
 * this version does not recognise is skipped rather than treated as progress, which leaves room for
 * a later one to add another.
 */
public final class QuestProgressPayload {

    /** Separates the objective entries from the trailing header segment. Reserved in an id. */
    private static final char HEADER_SEPARATOR = '|';

    /** Names the header segment carrying the accepted-at site. */
    private static final String SITE_PREFIX = "@site=";

    /** Names the header segment carrying the run a non-carrying once-a-run quest was taken in. */
    private static final String RUN_PREFIX = "@run=";

    private QuestProgressPayload() {
    }

    /** Pack the map. An empty or null map serializes to the empty string, which reads back empty. */
    @Nonnull
    public static String serialize(@Nullable Map<String, ObjectiveProgressState> progress) {
        return serialize(progress, null);
    }

    /**
     * Pack the map together with the site the quest was accepted at. A null, blank, or unrecordable
     * site is simply left out, so the result is byte-identical to {@link #serialize(Map)} whenever
     * there is no site to carry. {@link #serialize(Map, String, PerRuns.RunKey)} with no run.
     */
    @Nonnull
    public static String serialize(@Nullable Map<String, ObjectiveProgressState> progress,
                                   @Nullable String acceptSite) {
        return serialize(progress, acceptSite, null);
    }

    /**
     * Pack the map with the site the quest was accepted at and the run it was taken in (a once-a-run quest whose
     * progress does not carry over). Each is left out when absent, so a payload with neither is byte-identical to
     * {@link #serialize(Map)}.
     */
    @Nonnull
    public static String serialize(@Nullable Map<String, ObjectiveProgressState> progress,
                                   @Nullable String acceptSite, @Nullable PerRuns.RunKey takenIn) {
        StringBuilder sb = new StringBuilder();
        if (progress != null) {
            for (Map.Entry<String, ObjectiveProgressState> entry : progress.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(entry.getKey()).append(':').append(entry.getValue().serialize());
            }
        }
        if (isRecordableSite(acceptSite)) {
            sb.append(HEADER_SEPARATOR).append(SITE_PREFIX).append(acceptSite.trim());
        }
        if (takenIn != null) {
            sb.append(HEADER_SEPARATOR).append(RUN_PREFIX).append(takenIn.year()).append('#').append(takenIn.number());
        }
        if (sb.length() == 0) {
            return "";
        }
        return Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Can this site id be written into a payload at all? It has to be non-blank and free of the
     * characters the format reserves, the same set an objective id is held to - a site carrying one
     * would be cut in half on the way back out, and a half id matches nothing.
     */
    public static boolean isRecordableSite(@Nullable String siteId) {
        if (siteId == null || siteId.isBlank()) {
            return false;
        }
        String trimmed = siteId.trim();
        for (int i = 0; i < QuestProgressStore.DEFAULT_RESERVED_CHARACTERS.length(); i++) {
            if (trimmed.indexOf(QuestProgressStore.DEFAULT_RESERVED_CHARACTERS.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Unpack a {@link #serialize} payload, preserving authored order. Never throws: bad base64, a
     * pair with no separator, or a garbled count each drop that one entry (or the whole payload for
     * bad base64) and leave the rest readable.
     */
    @Nonnull
    public static Map<String, ObjectiveProgressState> deserialize(@Nullable String payload) {
        Map<String, ObjectiveProgressState> out = new LinkedHashMap<>();
        String decoded = decode(payload);
        if (decoded == null) {
            return out;
        }
        for (String pair : entriesOf(decoded).split(",")) {
            int colon = pair.indexOf(':');
            if (colon > 0 && colon < pair.length() - 1) {
                out.put(pair.substring(0, colon), ObjectiveProgressState.deserialize(pair.substring(colon + 1)));
            }
        }
        return out;
    }

    /**
     * The site this quest was accepted at, or null when the payload carries none - which is every
     * payload written before a quest asked for one, and every accept from a surface that named no
     * place.
     */
    @Nullable
    public static String acceptSite(@Nullable String payload) {
        String site = header(payload, SITE_PREFIX);
        return site == null || site.isEmpty() ? null : site;
    }

    /**
     * The run a non-carrying once-a-run quest was taken in, or null when the payload names none: every payload of
     * a quest that carries over, and every one written before the stamp existed.
     */
    @Nullable
    public static PerRuns.RunKey takenIn(@Nullable String payload) {
        String run = header(payload, RUN_PREFIX);
        int mark = run == null ? -1 : run.indexOf('#');
        if (mark <= 0) {
            return null;
        }
        try {
            return new PerRuns.RunKey(Integer.parseInt(run.substring(0, mark)), Integer.parseInt(run.substring(mark + 1)));
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    /** The trimmed value of the header segment named {@code prefix}, or null when the payload carries none. */
    @Nullable
    private static String header(@Nullable String payload, @Nonnull String prefix) {
        String decoded = decode(payload);
        int separator = decoded == null ? -1 : decoded.indexOf(HEADER_SEPARATOR);
        if (separator < 0) {
            return null;
        }
        for (String segment : decoded.substring(separator + 1).split("\\" + HEADER_SEPARATOR)) {
            if (segment.startsWith(prefix)) {
                return segment.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    /** The decoded text, or null when there is nothing readable to work with. */
    @Nullable
    private static String decode(@Nullable String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Everything before the header segment: the objective entries, whatever follows them. */
    @Nonnull
    private static String entriesOf(@Nonnull String decoded) {
        int separator = decoded.indexOf(HEADER_SEPARATOR);
        return separator < 0 ? decoded : decoded.substring(0, separator);
    }
}
