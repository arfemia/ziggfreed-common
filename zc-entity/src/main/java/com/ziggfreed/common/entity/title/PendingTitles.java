package com.ziggfreed.common.entity.title;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The queue of title changes owed to players who were not online when they were made: per player, one
 * ordered list of grants and revokes, paid in that order through zc-objectives' title write path at
 * the player's next player-ready, on their world thread. A title record lives on the player's own
 * entity, so a change made while they are away has to wait here for it.
 *
 * <p>Kept like {@link ActiveTitles} (the same {@link RecordFile}): memory answers, and the queue is
 * written to {@link #DEFAULT_FILE} a moment after a change and at once when a player leaves.
 * Single-process: a fleet sharing one database pays a change only on the server that queued it.
 * Lock-free reads from any thread.
 */
public final class PendingTitles {

    /** The queue's file, beside the record of shown titles. */
    public static final Path DEFAULT_FILE = Paths.get("mods", "ziggfreedcommon", "pending-titles.json");

    /** Which way a queued change goes. */
    public enum Kind {

        /** Unlock the title. */
        GRANT,

        /** Take the title away (and stop showing it). */
        REVOKE
    }

    /** One queued change: a grant or a revoke of one lower-cased title id. */
    public record Change(@Nonnull Kind kind, @Nonnull String titleId) {
    }

    /** Each player's queued changes, oldest first, as an unmodifiable list; a player with none has no row. */
    private static final Map<UUID, List<Change>> BY_PLAYER = new ConcurrentHashMap<>();

    private static final RecordFile FILE = new RecordFile("the queued title changes",
            "a restart may lose a title change queued for an offline player", new RecordFile.Body() {
                @Override
                @Nonnull
                public String write() {
                    return writeDoc();
                }

                @Override
                public void read(@Nonnull String text) {
                    readDoc(text);
                }

                @Override
                public void forget() {
                    BY_PLAYER.clear();
                }
            });

    /** On-disk shape: a player's id to their queued changes, oldest first. */
    private static final class Doc {
        Map<String, List<Row>> pending;
    }

    /** One queued change on disk: {@code op} is {@code grant} or {@code revoke}. */
    private static final class Row {
        String op;
        String title;
    }

    private PendingTitles() {
    }

    /**
     * Queue {@code kind} of {@code titleId} for {@code playerId}, after anything already queued. False,
     * with nothing queued, for a blank id or one carrying a character the save format reserves.
     */
    public static boolean add(@Nonnull UUID playerId, @Nonnull Kind kind, @Nullable String titleId) {
        String id = normalize(titleId);
        if (id == null) {
            return false;
        }
        Change change = new Change(kind, id);
        BY_PLAYER.compute(playerId, (key, queued) -> {
            List<Change> next = new ArrayList<>(queued == null ? List.of() : queued);
            next.add(change);
            return List.copyOf(next);
        });
        FILE.changed();
        return true;
    }

    /** Take everything queued for {@code playerId}, oldest first, leaving nothing queued; empty for none. */
    @Nonnull
    public static List<Change> drain(@Nullable UUID playerId) {
        List<Change> queued = playerId == null ? null : BY_PLAYER.remove(playerId);
        if (queued == null || queued.isEmpty()) {
            return List.of();
        }
        FILE.changed();
        return queued;
    }

    /** What is queued for {@code playerId}, oldest first, without taking it; empty for none. */
    @Nonnull
    public static List<Change> of(@Nullable UUID playerId) {
        List<Change> queued = playerId == null ? null : BY_PLAYER.get(playerId);
        return queued == null ? List.of() : queued;
    }

    /** True when anything is queued for {@code playerId}. */
    public static boolean has(@Nullable UUID playerId) {
        return !of(playerId).isEmpty();
    }

    /**
     * Keep the queue in {@code target} (null: in memory only) and read what it holds, replacing what
     * memory held. A change is then written a moment later through {@code flushOn} (null: only when
     * {@link #flush} is called). Called once from the library's setup, before any player connects.
     */
    public static void persistTo(@Nullable Path target, @Nullable Executor flushOn) {
        FILE.persistTo(target, flushOn);
    }

    /** Write the queue to its file now, if anything changed since the last write. Any thread. */
    public static void flush() {
        FILE.flush();
    }

    /** Forget every queued change in memory, never in the file: a test, or a restart's first step. */
    public static void clear() {
        BY_PLAYER.clear();
        FILE.clean();
    }

    @Nonnull
    private static String writeDoc() {
        Doc doc = new Doc();
        doc.pending = new TreeMap<>();
        for (Map.Entry<UUID, List<Change>> e : BY_PLAYER.entrySet()) {
            List<Row> rows = new ArrayList<>(e.getValue().size());
            for (Change change : e.getValue()) {
                Row row = new Row();
                row.op = change.kind().name().toLowerCase(Locale.ROOT);
                row.title = change.titleId();
                rows.add(row);
            }
            doc.pending.put(e.getKey().toString(), rows);
        }
        return RecordFile.GSON.toJson(doc);
    }

    private static void readDoc(@Nonnull String text) {
        Doc doc = RecordFile.GSON.fromJson(text, Doc.class);
        BY_PLAYER.clear();
        if (doc == null || doc.pending == null) {
            return;
        }
        for (Map.Entry<String, List<Row>> e : doc.pending.entrySet()) {
            UUID playerId = playerIdOf(e.getKey());
            if (playerId == null || e.getValue() == null) {
                continue;
            }
            List<Change> changes = new ArrayList<>();
            for (Row row : e.getValue()) {
                Kind kind = row == null ? null : kindOf(row.op);
                String id = row == null ? null : normalize(row.title);
                if (kind != null && id != null) {
                    changes.add(new Change(kind, id));
                }
            }
            if (!changes.isEmpty()) {
                BY_PLAYER.put(playerId, List.copyOf(changes));
            }
        }
    }

    @Nullable
    private static Kind kindOf(@Nullable String op) {
        if (op == null || op.isBlank()) {
            return null;
        }
        try {
            return Kind.valueOf(op.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Nullable
    private static UUID playerIdOf(@Nullable String key) {
        try {
            return key == null ? null : UUID.fromString(key.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The record's own reading of a usable id: trimmed, lower-cased, null for a blank or reserved one. */
    @Nullable
    private static String normalize(@Nullable String titleId) {
        return ZigTitleComponent.normalize(titleId);
    }
}
