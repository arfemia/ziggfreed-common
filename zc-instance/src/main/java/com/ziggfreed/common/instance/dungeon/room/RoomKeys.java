package com.ziggfreed.common.instance.dungeon.room;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * A room's scoped keys, pure: a key authored with the suffix {@code @room} or {@code @run}
 * ({@code "MatchKey": "Door@room"}) becomes that room's or that run's own token, so two copies of one room in a run,
 * or two parties in one copy, never answer each other (the engine's tags and signals reach every volume of a world
 * that carries the key, within the sender's {@code Radius}). A key without the suffix stays world-wide.
 *
 * <p><b>Which keys.</b> Every string of a volume's, group's or point's encoded JSON is read, and every key of its
 * maps: a {@code MatchKey}, {@code MatchValue}, {@code TagKey}, {@code TagValue}, {@code CompareTagKey},
 * {@code SignalKeys} and {@code SignalValues} entry, a {@code Tags} key and value, and any field a pack's own Type
 * names a tag in. A {@code Key=Value} form (a tag filter) has each side read on its own. The suffix is read without
 * regard to case. Field names and every other string (block ids, lang keys) never end in the suffix, so they are left
 * alone.
 *
 * <p><b>The tokens.</b> {@code @room} becomes {@code @<lane>.<run>.<room>} and {@code @run} becomes
 * {@code @<lane>.<run>}: unique in the copy while the run lives, and the same few strings again in the next copy of
 * that name, so the engine's process-wide tag table grows only to lanes times runs a lane serves times rooms.
 */
public final class RoomKeys {

    /** The suffix a key scoped to its room ends with. */
    public static final String ROOM = "@room";

    /** The suffix a key scoped to its run ends with. */
    public static final String RUN = "@run";

    private RoomKeys() {
    }

    /**
     * The replacements for one room of one run.
     *
     * @param room what {@code @room} becomes
     * @param run  what {@code @run} becomes
     */
    public record Tokens(@Nonnull String room, @Nonnull String run) {

        /** {@code text} with its scoped key, or each side of its {@code Key=Value}, rewritten; else as given. */
        @Nonnull
        public String scoped(@Nonnull String text) {
            int eq = text.indexOf('=');
            if (eq >= 0) {
                return word(text.substring(0, eq)) + '=' + word(text.substring(eq + 1));
            }
            return word(text);
        }

        @Nonnull
        private String word(@Nonnull String word) {
            String trimmed = word.trim();
            if (endsWith(trimmed, ROOM)) {
                return trimmed.substring(0, trimmed.length() - ROOM.length()) + room;
            }
            if (endsWith(trimmed, RUN)) {
                return trimmed.substring(0, trimmed.length() - RUN.length()) + run;
            }
            return word;
        }
    }

    /** The tokens of room {@code room} of run {@code run} in lane {@code lane}. */
    @Nonnull
    public static Tokens tokens(int lane, int run, int room) {
        String runToken = "@" + lane + "." + run;
        return new Tokens(runToken + "." + room, runToken);
    }

    /** Whether {@code key} (or the key side of a {@code Key=Value}) carries a scope suffix. */
    public static boolean isScoped(@Nullable String key) {
        if (key == null) {
            return false;
        }
        int eq = key.indexOf('=');
        String word = (eq >= 0 ? key.substring(0, eq) : key).trim();
        return endsWith(word, ROOM) || endsWith(word, RUN);
    }

    /**
     * {@code value} with every scoped key in it rewritten for {@code tokens}: a string as {@link Tokens#scoped}, an
     * array element by element, a document key by key and value by value (its keys keep their order). A document or
     * array comes back new; {@code value} is not changed.
     */
    @Nonnull
    public static BsonValue rewrite(@Nonnull BsonValue value, @Nonnull Tokens tokens) {
        if (value.isString()) {
            String text = value.asString().getValue();
            String scoped = tokens.scoped(text);
            return scoped.equals(text) ? value : new BsonString(scoped);
        }
        if (value.isArray()) {
            BsonArray out = new BsonArray();
            for (BsonValue element : value.asArray()) {
                out.add(rewrite(element, tokens));
            }
            return out;
        }
        if (value.isDocument()) {
            BsonDocument out = new BsonDocument();
            for (Map.Entry<String, BsonValue> entry : value.asDocument().entrySet()) {
                out.put(tokens.scoped(entry.getKey()), rewrite(entry.getValue(), tokens));
            }
            return out;
        }
        return value;
    }

    /** {@link #rewrite} of a document, as a document. */
    @Nonnull
    public static BsonDocument rewrite(@Nonnull BsonDocument document, @Nonnull Tokens tokens) {
        return rewrite((BsonValue) document, tokens).asDocument();
    }

    private static boolean endsWith(@Nonnull String word, @Nonnull String suffix) {
        return word.length() > suffix.length()
                && word.regionMatches(true, word.length() - suffix.length(), suffix, 0, suffix.length());
    }
}
