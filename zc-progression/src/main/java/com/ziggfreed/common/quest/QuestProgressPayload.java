package com.ziggfreed.common.quest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.ObjectiveProgressState;

/**
 * Packs one quest's whole objective-progress map into the single opaque string a
 * {@link QuestProgressStore} persists, and reads it back.
 *
 * <p>The format is {@code base64("objId:current/required,objId:current/required,...")}. Three
 * characters are therefore RESERVED inside an objective id - {@code ,} {@code :} and (by the
 * store's own convention) the record separators it may add - which is exactly what
 * {@link QuestProgressStore#usesReservedDelimiter} exists to reject at content-load time. Authoring
 * an id containing one silently truncates that objective's progress on the next round trip.
 *
 * <p>Both directions are total: an unreadable payload decodes to an EMPTY map rather than throwing,
 * so a corrupted entry costs one quest's progress and not the player's session.
 */
public final class QuestProgressPayload {

    private QuestProgressPayload() {
    }

    /** Pack the map. An empty or null map serializes to the empty string, which reads back empty. */
    @Nonnull
    public static String serialize(@Nullable Map<String, ObjectiveProgressState> progress) {
        if (progress == null || progress.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ObjectiveProgressState> entry : progress.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue().serialize());
        }
        return Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Unpack a {@link #serialize} payload, preserving authored order. Never throws: bad base64, a
     * pair with no separator, or a garbled count each drop that one entry (or the whole payload for
     * bad base64) and leave the rest readable.
     */
    @Nonnull
    public static Map<String, ObjectiveProgressState> deserialize(@Nullable String payload) {
        Map<String, ObjectiveProgressState> out = new LinkedHashMap<>();
        if (payload == null || payload.isEmpty()) {
            return out;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return out;
        }
        for (String pair : decoded.split(",")) {
            int colon = pair.indexOf(':');
            if (colon > 0 && colon < pair.length() - 1) {
                out.put(pair.substring(0, colon), ObjectiveProgressState.deserialize(pair.substring(colon + 1)));
            }
        }
        return out;
    }
}
