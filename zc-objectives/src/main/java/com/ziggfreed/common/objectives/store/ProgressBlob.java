package com.ziggfreed.common.objectives.store;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The packing used by every map on {@link ZigProgressComponent}: a whole map in and out of ONE
 * string, so the component's codec is a row of plain string leaves rather than a guess at a map
 * codec API.
 *
 * <p><b>Format.</b> Entries are joined with {@code |}, and each entry joins its key to its value
 * with {@code =}. Those two characters are therefore the only ones reserved by the format, which is
 * exactly what the progress stores' inherited {@code usesReservedDelimiter} defaults already reject
 * inside an id - so neither adapter has to override that method.
 *
 * <p><b>Base64 for opaque values.</b> A quest's progress payload is an opaque string the engine
 * packs itself and may legitimately contain {@code |} or {@code =}, so it travels base64-encoded.
 * Keys never do: they are ids, and an id carrying a reserved character is refused upstream.
 *
 * <p>Every method is total: a null, blank or malformed input yields an empty map or an empty string
 * rather than a throw, because this sits under a persistence path where a partial read must degrade
 * to "this player has no progress yet" rather than break their login.
 */
final class ProgressBlob {

    private static final char ENTRY_SEPARATOR = '|';
    private static final char PAIR_SEPARATOR = '=';

    private ProgressBlob() {
    }

    // ==================== string values ====================

    /** Pack {@code map} as {@code key=value|key=value}. An empty map packs to an empty string. */
    @Nonnull
    static String serializeStrings(@Nullable Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            append(out, key, value);
        }
        return out.toString();
    }

    /** Unpack what {@link #serializeStrings} wrote. */
    @Nonnull
    static Map<String, String> deserializeStrings(@Nullable String blob) {
        Map<String, String> map = new ConcurrentHashMap<>();
        forEachPair(blob, (key, value) -> map.put(key, value));
        return map;
    }

    // ==================== long values ====================

    /** Pack a {@code key -> long} map. */
    @Nonnull
    static String serializeLongs(@Nullable Map<String, Long> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> entry : map.entrySet()) {
            String key = entry.getKey();
            Long value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            append(out, key, Long.toString(value));
        }
        return out.toString();
    }

    /** Unpack a {@code key -> long} map. An unparseable number drops that entry. */
    @Nonnull
    static Map<String, Long> deserializeLongs(@Nullable String blob) {
        Map<String, Long> map = new ConcurrentHashMap<>();
        forEachPair(blob, (key, value) -> {
            try {
                map.put(key, Long.valueOf(Long.parseLong(value)));
            } catch (NumberFormatException ignored) {
                // A corrupted number reads as "no progress recorded", never as a login failure.
            }
        });
        return map;
    }

    // ==================== opaque values ====================

    /** Pack a map whose VALUES are opaque, base64-encoding each value. */
    @Nonnull
    static String serializeBase64Values(@Nullable Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            append(out, key, Base64.getEncoder()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
        return out.toString();
    }

    /** Unpack what {@link #serializeBase64Values} wrote. An undecodable value drops that entry. */
    @Nonnull
    static Map<String, String> deserializeBase64Values(@Nullable String blob) {
        Map<String, String> map = new ConcurrentHashMap<>();
        forEachPair(blob, (key, value) -> {
            try {
                map.put(key, new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // Same rule as a corrupted number: drop the entry, keep the player.
            }
        });
        return map;
    }

    // ==================== shared plumbing ====================

    /** A deep copy of {@code source} as a fresh concurrent map, for {@code clone()}. */
    @Nonnull
    static <V> Map<String, V> copy(@Nullable Map<String, V> source) {
        Map<String, V> out = new ConcurrentHashMap<>();
        if (source != null) {
            out.putAll(source);
        }
        return out;
    }

    /** An ordered snapshot, so a packed blob is stable enough to eyeball in a saved world. */
    @Nonnull
    static <V> Map<String, V> ordered(@Nonnull Map<String, V> source) {
        return new LinkedHashMap<>(source);
    }

    private static void append(@Nonnull StringBuilder out, @Nonnull String key, @Nonnull String value) {
        if (out.length() > 0) {
            out.append(ENTRY_SEPARATOR);
        }
        out.append(key).append(PAIR_SEPARATOR).append(value);
    }

    private interface PairSink {
        void accept(@Nonnull String key, @Nonnull String value);
    }

    private static void forEachPair(@Nullable String blob, @Nonnull PairSink sink) {
        if (blob == null || blob.isBlank()) {
            return;
        }
        for (String entry : blob.split("\\" + ENTRY_SEPARATOR)) {
            if (entry.isBlank()) {
                continue;
            }
            int split = entry.indexOf(PAIR_SEPARATOR);
            if (split <= 0 || split == entry.length() - 1) {
                continue;
            }
            sink.accept(entry.substring(0, split), entry.substring(split + 1));
        }
    }
}
