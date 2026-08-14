package com.ziggfreed.common.commerce;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The packing behind every leaf on {@link CommerceComponent}: a whole map in and out of ONE string,
 * so the component's codec is a row of plain string leaves rather than a guess at a map codec API.
 *
 * <p><b>Format.</b> Entries are joined with {@code |} and each entry joins its key to its value with
 * the FIRST {@code =}. Those two characters are the only ones the format reserves. Anything that may
 * itself contain either travels base64-encoded, and base64 output is exactly the alphabet this
 * format does not reserve - its own {@code =} padding is safe because a pair splits on the first
 * {@code =}, never the last.
 *
 * <p>Every method is total: a null, blank or malformed input yields an empty map or an empty string
 * rather than a throw. This sits under a persistence path, where a partial read has to degrade to
 * "this player has bought nothing yet" rather than break their login.
 *
 * <p><b>Why this is not shared with the progression component's packing.</b> That one lives inside
 * {@code zc-objectives}, a module this one may not depend on: both sit at the TOP of the graph as
 * peers, so neither may import the other. The two grammars are deliberately identical, and the day a
 * third module wants one, the lift target is {@code zc-core} rather than a third copy.
 */
final class CommerceBlob {

    private static final char ENTRY_SEPARATOR = '|';
    private static final char PAIR_SEPARATOR = '=';

    /** Separates the members of a packed SET. Base64 output never contains one. */
    private static final char SET_SEPARATOR = ',';

    private CommerceBlob() {
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
        forEachPair(blob, map::put);
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
            append(out, key, Long.toString(value.longValue()));
        }
        return out.toString();
    }

    /** Unpack a {@code key -> long} map. An unparseable number drops that entry. */
    @Nonnull
    static Map<String, Long> deserializeLongs(@Nullable String blob) {
        Map<String, Long> map = new ConcurrentHashMap<>();
        forEachPair(blob, (key, value) -> {
            try {
                map.put(key, Long.valueOf(Long.parseLong(value.trim())));
            } catch (NumberFormatException ignored) {
                // A corrupted number reads as "nothing recorded", never as a login failure.
            }
        });
        return map;
    }

    // ==================== sets of ids ====================

    /**
     * Pack a SET of ids as one value: EACH ID base64-encoded, joined with a comma.
     *
     * <p>Encoding each id rather than the joined text is the whole point. Base64 output carries only
     * {@code A-Za-z0-9+/=}, so no id can contain the comma that separates them, nor the {@code |}
     * that separates the entries of whatever frame this value sits inside - and the {@code =}
     * padding is harmless, because a pair splits on its FIRST {@code =}. Encoding the joined text
     * instead would protect the outer frame and leave an id carrying the inner separator splitting
     * itself in two, which is precisely the bug this shape exists to make impossible.
     */
    @Nonnull
    static String serializeSet(@Nullable Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(SET_SEPARATOR);
            }
            out.append(Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8)));
        }
        return out.toString();
    }

    /** Unpack what {@link #serializeSet} wrote. An undecodable id costs that id alone. */
    @Nonnull
    static Set<String> deserializeSet(@Nullable String value) {
        Set<String> ids = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return ids;
        }
        for (String encoded : value.trim().split(String.valueOf(SET_SEPARATOR), -1)) {
            if (encoded.isBlank()) {
                continue;
            }
            try {
                ids.add(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException notBase64) {
                // Same rule as a corrupted number: drop the entry, keep the player.
            }
        }
        return ids;
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
