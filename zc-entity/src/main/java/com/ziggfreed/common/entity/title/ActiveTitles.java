package com.ziggfreed.common.entity.title;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.ziggfreed.common.util.RecordFile;
import com.ziggfreed.common.util.SafeLog;

/**
 * The process-wide record of each player's titles, for a reader that is not on that player's world
 * thread: a menu or a leaderboard naming players who may stand in another world, whose entity stores
 * it may not touch, or who are not online at all. It answers the same whether the player is online or
 * not, so a title on a row never tells a viewer who is online.
 *
 * <p>Per player it keeps three things: the title they show, every title they have earned, and the
 * username they last connected with (so an admin command can name a player who is offline). For an
 * online player it mirrors {@link ZigTitleComponent}: seeded at connect from the saved record and
 * refreshed by every changed write through the title write path, so a row reads the live choice. A
 * player who leaves keeps their entry. The whole record is written to its file ({@link #DEFAULT_FILE})
 * a moment after a change, on the flusher {@link #persistTo} was given, and at once when a player
 * leaves, so a restart names every player with the title they last chose. A file from before the
 * earned set and usernames were kept reads them as empty, and each player enters them at their next
 * connect.
 *
 * <p>Single-process: a fleet sharing one database keeps one per server, each corrected at the
 * player's next connect there. Lock-free reads from any thread; writes to the file are serialized.
 */
public final class ActiveTitles {

    /** The record's file, beside the library's owner files. */
    public static final Path DEFAULT_FILE = Paths.get("mods", "ziggfreedcommon", "shown-titles.json");

    /** How long a change waits before the record is written, so a burst of connects writes once. */
    public static final long FLUSH_DELAY_SECONDS = 2L;

    private static final Map<UUID, String> BY_PLAYER = new ConcurrentHashMap<>();

    /** Each player's earned ids, lower-cased, as an unmodifiable sorted set; a player with none has no row. */
    private static final Map<UUID, Set<String>> EARNED = new ConcurrentHashMap<>();

    /** The username each player last connected with, as the engine spelt it. */
    private static final Map<UUID, String> USERNAMES = new ConcurrentHashMap<>();

    /** A lower-cased username to the player who last connected with it. */
    private static final Map<String, UUID> BY_USERNAME = new ConcurrentHashMap<>();

    /** Keeps {@link #USERNAMES} and {@link #BY_USERNAME} agreeing that a name has one holder; reads take no lock. */
    private static final Object NAMES_LOCK = new Object();

    private static final RecordFile FILE = RecordFile.builder("title", "the shown titles", new RecordFile.Body() {
                @Override
                @Nonnull
                public JsonObject write() {
                    return writeDoc();
                }

                @Override
                public void read(@Nonnull JsonObject doc, int version) {
                    readDoc(doc);
                }

                @Override
                public void forget() {
                    forgetAll();
                }
            })
            .lostWrite("a restart may show an offline player an older title until they connect again")
            .build();

    /**
     * On-disk shape: a player's id to the lower-cased id of the title they show, to the ids they have
     * earned, and to the username they last connected with. The last two may be absent (a file written
     * before they were kept), and read as empty.
     */
    private static final class Doc {
        Map<String, String> shownTitles;
        Map<String, List<String>> earnedTitles;
        Map<String, String> usernames;
    }

    private ActiveTitles() {
    }

    /** The lower-cased id of the title {@code playerId} shows, online or not, or null for none or unknown. */
    @Nullable
    public static String of(@Nullable UUID playerId) {
        return playerId == null ? null : BY_PLAYER.get(playerId);
    }

    /**
     * Every title {@code playerId} has earned, online or not: lower-cased, sorted, unmodifiable, and
     * empty for a player with none or one this record has never seen.
     */
    @Nonnull
    public static Set<String> earned(@Nullable UUID playerId) {
        Set<String> earned = playerId == null ? null : EARNED.get(playerId);
        return earned == null ? Set.of() : earned;
    }

    /**
     * The username {@code playerId} last connected with, or null when this record has never seen them
     * or another player has connected with that name since (a name has one holder, the latest).
     */
    @Nullable
    public static String usernameOf(@Nullable UUID playerId) {
        return playerId == null ? null : USERNAMES.get(playerId);
    }

    /**
     * The player who last connected as {@code username}, matched whatever the casing, or null. When two
     * players have used one name, the one who connected with it last answers, after a restart too.
     */
    @Nullable
    public static UUID idOf(@Nullable String username) {
        String key = nameKey(username);
        return key == null ? null : BY_USERNAME.get(key);
    }

    /** Record what {@code playerId} shows now; a null or blank id records that they show nothing. */
    public static void put(@Nonnull UUID playerId, @Nullable String titleId) {
        if (putShown(playerId, titleId)) {
            FILE.changed();
        }
    }

    /**
     * Record everything this record keeps about {@code playerId} at once: the title they show (null for
     * none), every title they have earned, and the username they connected with (null or blank keeps the
     * one already recorded). The connect seed and every changed write come through here.
     */
    public static void refresh(@Nonnull UUID playerId, @Nullable String username, @Nullable String shownTitleId,
            @Nullable Collection<String> earnedTitleIds) {
        boolean changed = putShown(playerId, shownTitleId);
        changed |= putEarned(playerId, earnedTitleIds);
        changed |= putUsername(playerId, username);
        if (changed) {
            FILE.changed();
        }
    }

    /**
     * Keep the record in {@code target} (null: in memory only) and read what it holds, replacing what
     * memory held. A change is then written a moment later through {@code flushOn} (null: only when
     * {@link #flush} is called). Called once from the library's setup, before any player connects.
     */
    public static void persistTo(@Nullable Path target, @Nullable Executor flushOn) {
        FILE.persistTo(target, flushOn);
    }

    /** Write the record to its file now, if anything changed since the last write. Any thread. */
    public static void flush() {
        FILE.flush();
    }

    /** Forget everybody in memory, never in the file: a test, or a restart's first step. */
    public static void clear() {
        forgetAll();
        FILE.clean();
    }

    private static boolean putShown(@Nonnull UUID playerId, @Nullable String titleId) {
        String shown = normalize(titleId);
        String before = shown == null ? BY_PLAYER.remove(playerId) : BY_PLAYER.put(playerId, shown);
        return !Objects.equals(before, shown);
    }

    private static boolean putEarned(@Nonnull UUID playerId, @Nullable Collection<String> titleIds) {
        Set<String> earned = normalizedSet(titleIds);
        Set<String> before = earned.isEmpty() ? EARNED.remove(playerId) : EARNED.put(playerId, earned);
        return !Objects.equals(before == null ? Set.of() : before, earned);
    }

    /**
     * Give {@code username} to {@code playerId}. A name is held by one player at a time: another player
     * who held it (any case) loses their entry, so the latest connect wins in memory and in the file,
     * and across a restart. True when anything changed.
     */
    private static boolean putUsername(@Nonnull UUID playerId, @Nullable String username) {
        String key = nameKey(username);
        if (key == null) {
            return false;
        }
        String name = username.trim();
        synchronized (NAMES_LOCK) {
            String before = USERNAMES.put(playerId, name);
            String beforeKey = nameKey(before);
            if (beforeKey != null && !beforeKey.equals(key)) {
                BY_USERNAME.remove(beforeKey, playerId);
            }
            UUID heldBy = BY_USERNAME.put(key, playerId);
            String held = heldBy == null || heldBy.equals(playerId) ? null : USERNAMES.get(heldBy);
            boolean dropped = held != null && key.equals(nameKey(held)) && USERNAMES.remove(heldBy, held);
            return dropped || !name.equals(before);
        }
    }

    @Nonnull
    private static JsonObject writeDoc() {
        Doc doc = new Doc();
        doc.shownTitles = new TreeMap<>();
        for (Map.Entry<UUID, String> e : BY_PLAYER.entrySet()) {
            doc.shownTitles.put(e.getKey().toString(), e.getValue());
        }
        doc.earnedTitles = new TreeMap<>();
        for (Map.Entry<UUID, Set<String>> e : EARNED.entrySet()) {
            doc.earnedTitles.put(e.getKey().toString(), new ArrayList<>(e.getValue()));
        }
        doc.usernames = new TreeMap<>();
        for (Map.Entry<UUID, String> e : USERNAMES.entrySet()) {
            doc.usernames.put(e.getKey().toString(), e.getValue());
        }
        return RecordFile.gson().toJsonTree(doc).getAsJsonObject();
    }

    private static void readDoc(@Nonnull JsonObject saved) {
        Doc doc = RecordFile.gson().fromJson(saved, Doc.class);
        forgetAll();
        if (doc == null) {
            return;
        }
        if (doc.shownTitles != null) {
            for (Map.Entry<String, String> e : doc.shownTitles.entrySet()) {
                UUID playerId = playerIdOf(e.getKey());
                String shown = normalize(e.getValue());
                if (playerId != null && shown != null) {
                    BY_PLAYER.put(playerId, shown);
                }
            }
        }
        if (doc.earnedTitles != null) {
            for (Map.Entry<String, List<String>> e : doc.earnedTitles.entrySet()) {
                UUID playerId = playerIdOf(e.getKey());
                Set<String> earned = normalizedSet(e.getValue());
                if (playerId != null && !earned.isEmpty()) {
                    EARNED.put(playerId, earned);
                }
            }
        }
        if (doc.usernames != null) {
            readUsernames(doc.usernames);
        }
    }

    /**
     * Read the saved usernames. The record writes each name under one player, so a file naming two
     * (written before that held, or edited by hand) keeps the player whose id sorts first, whatever
     * order the file lists them in, warns once, and is written again without the others.
     */
    private static void readUsernames(@Nonnull Map<String, String> saved) {
        Map<UUID, String> byPlayer = new TreeMap<>();
        for (Map.Entry<String, String> e : saved.entrySet()) {
            UUID playerId = playerIdOf(e.getKey());
            if (playerId != null && nameKey(e.getValue()) != null) {
                byPlayer.put(playerId, e.getValue());
            }
        }
        int clashes = 0;
        for (Map.Entry<UUID, String> e : byPlayer.entrySet()) {
            UUID heldBy = BY_USERNAME.get(nameKey(e.getValue()));
            if (heldBy != null && !heldBy.equals(e.getKey())) {
                clashes++;
                continue;
            }
            putUsername(e.getKey(), e.getValue());
        }
        if (clashes > 0) {
            SafeLog.warn("[title] the shown titles named " + clashes + " username(s) under more than one player;"
                    + " each name now finds the player whose id sorts first, until a player connects with it");
            FILE.changed();
        }
    }

    private static void forgetAll() {
        BY_PLAYER.clear();
        EARNED.clear();
        USERNAMES.clear();
        BY_USERNAME.clear();
    }

    /** {@code titleIds} lower-cased, blanks dropped, as an unmodifiable sorted set. */
    @Nonnull
    private static Set<String> normalizedSet(@Nullable Collection<String> titleIds) {
        if (titleIds == null || titleIds.isEmpty()) {
            return Set.of();
        }
        Set<String> sorted = new TreeSet<>();
        for (String titleId : titleIds) {
            String id = normalize(titleId);
            if (id != null) {
                sorted.add(id);
            }
        }
        return sorted.isEmpty() ? Set.of() : Collections.unmodifiableSet(sorted);
    }

    @Nullable
    private static UUID playerIdOf(@Nullable String key) {
        try {
            return key == null ? null : UUID.fromString(key.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    private static String normalize(@Nullable String titleId) {
        return titleId == null || titleId.isBlank() ? null : titleId.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String nameKey(@Nullable String username) {
        return username == null || username.isBlank() ? null : username.trim().toLowerCase(Locale.ROOT);
    }
}
