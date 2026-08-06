package com.ziggfreed.common.codec;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;

/**
 * The ONE native-tag match codec leaf: a {@code {"<tagFamily>": ["<value>", ...]}} map read against
 * an item's own native raw tags, plus the ANY-of matcher every consumer evaluates it with. Embed
 * the {@link #CODEC} wherever an asset codec authors a tag gate/matcher instead of re-declaring
 * the map codec and re-implementing the match loop per consumer.
 *
 * <p><b>Match semantics (ANY-of, at both levels):</b> the subject matches when ANY authored family
 * key names a tag the item actually carries AND ANY value listed under that key is present on the
 * item (case-insensitive). So {@code {"Family": ["Dagger", "Sword"]}} passes for either family, and
 * an unauthored/empty map never matches on its own - each consumer decides what "no tag route
 * authored" means for its own gate.
 *
 * <p><b>Overlay granularity:</b> the map is ONE leaf. A native {@code Parent} child (or a
 * consumer-side overlay) authoring the tag map replaces the whole map, never a single tag family -
 * the engine's own map codec carries no per-key inherit, and the alternative (a hand-rolled
 * per-key merge) would make an authored empty family impossible to express.
 */
public final class TagMatch {

    /**
     * The shared tag-map codec instance (stateless, safe to reference from several owning codecs):
     * a native tag family name to its accepted values.
     */
    public static final Codec<Map<String, String[]>> CODEC =
            new MapCodec<>(new ArrayCodec<>(Codec.STRING, String[]::new), LinkedHashMap::new);

    private TagMatch() {
    }

    /**
     * ANY-of match of the authored {@code required} families/values against an item's {@code actual}
     * native raw tags. Null/empty on either side is no match (the caller decides whether an
     * unauthored tag route means "gate open" or "route not taken").
     */
    public static boolean matches(@Nullable Map<String, String[]> required,
            @Nullable Map<String, String[]> actual) {
        if (required == null || required.isEmpty() || actual == null || actual.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String[]> req : required.entrySet()) {
            String[] have = actual.get(req.getKey());
            if (have == null || req.getValue() == null) {
                continue;
            }
            for (String want : req.getValue()) {
                if (want == null) {
                    continue;
                }
                for (String h : have) {
                    if (want.equalsIgnoreCase(h)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** True when {@code tags} authors at least one family (the "this route is taken" test). */
    public static boolean isAuthored(@Nullable Map<String, String[]> tags) {
        return tags != null && !tags.isEmpty();
    }

    /** An empty, immutable tag map - the safe stand-in when an item's live tags are unresolvable. */
    @Nonnull
    public static Map<String, String[]> empty() {
        return Map.of();
    }
}
