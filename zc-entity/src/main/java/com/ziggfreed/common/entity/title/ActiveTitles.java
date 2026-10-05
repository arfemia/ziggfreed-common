package com.ziggfreed.common.entity.title;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.util.io.FileUtil;
import com.ziggfreed.common.util.SafeLog;

/**
 * Who shows which title, for a reader that is not on that player's world thread: a menu or a
 * leaderboard naming players who may stand in another world, whose entity stores it may not touch,
 * or who are not online at all. It answers the same whether the player is online or not, so a title
 * on a row never tells a viewer who is online.
 *
 * <p>For an online player it mirrors {@link ZigTitleComponent#activeTitle()}: seeded at connect from
 * the saved record and refreshed by every changed write through the title write path, so a row reads
 * the live choice. A player who leaves keeps their entry. The whole record is written to its file
 * ({@link #DEFAULT_FILE}) a moment after a change, on the flusher {@link #persistTo} was given, and at
 * once when a player leaves, so a restart names every player with the title they last chose. A
 * player who chose a title before the record existed enters it at their next connect.
 *
 * <p>Single-process: a fleet sharing one database keeps one per server, each corrected at the
 * player's next connect there. Lock-free reads from any thread; writes to the file are serialized.
 */
public final class ActiveTitles {

    /** The record's file, beside the library's owner files. */
    public static final Path DEFAULT_FILE = Paths.get("mods", "ziggfreedcommon", "shown-titles.json");

    /** How long a change waits before the record is written, so a burst of connects writes once. */
    public static final long FLUSH_DELAY_SECONDS = 2L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Map<UUID, String> BY_PLAYER = new ConcurrentHashMap<>();

    /** A change memory holds that the file does not yet. */
    private static final AtomicBoolean DIRTY = new AtomicBoolean();

    /** A flush is already waiting on the flusher, so a change needs to ask for no other. */
    private static final AtomicBoolean FLUSH_ASKED = new AtomicBoolean();

    private static final Object WRITE_LOCK = new Object();

    @Nullable
    private static volatile Path file;

    @Nullable
    private static volatile Executor flusher;

    /** On-disk shape: a player's id to the lower-cased id of the title they show. */
    private static final class Doc {
        Map<String, String> shownTitles;
    }

    private ActiveTitles() {
    }

    /** The lower-cased id of the title {@code playerId} shows, online or not, or null for none or unknown. */
    @Nullable
    public static String of(@Nullable UUID playerId) {
        return playerId == null ? null : BY_PLAYER.get(playerId);
    }

    /** Record what {@code playerId} shows now; a null or blank id records that they show nothing. */
    public static void put(@Nonnull UUID playerId, @Nullable String titleId) {
        String shown = normalize(titleId);
        String before = shown == null ? BY_PLAYER.remove(playerId) : BY_PLAYER.put(playerId, shown);
        if (!Objects.equals(before, shown)) {
            changed();
        }
    }

    /**
     * Keep the record in {@code target} (null: in memory only) and read what it holds, replacing what
     * memory held. A change is then written a moment later through {@code flushOn} (null: only when
     * {@link #flush} is called). Called once from the library's setup, before any player connects.
     */
    public static void persistTo(@Nullable Path target, @Nullable Executor flushOn) {
        synchronized (WRITE_LOCK) {
            file = target;
            flusher = flushOn;
            FLUSH_ASKED.set(false);
            DIRTY.set(false);
            BY_PLAYER.clear();
            if (target != null) {
                load(target);
            }
        }
    }

    /** Write the record to its file now, if anything changed since the last write. Any thread. */
    public static void flush() {
        synchronized (WRITE_LOCK) {
            Path target = file;
            if (target == null || !DIRTY.getAndSet(false)) {
                return;
            }
            Doc doc = new Doc();
            doc.shownTitles = new TreeMap<>();
            for (Map.Entry<UUID, String> e : BY_PLAYER.entrySet()) {
                doc.shownTitles.put(e.getKey().toString(), e.getValue());
            }
            try {
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                FileUtil.writeStringAtomic(target, GSON.toJson(doc), true);
            } catch (Throwable t) {
                DIRTY.set(true);
                SafeLog.warn("[title] could not write " + target + ", so a restart may show an offline player"
                        + " an older title until they connect again: " + t.getMessage());
            }
        }
    }

    /** Forget everybody in memory, never in the file: a test, or a restart's first step. */
    public static void clear() {
        BY_PLAYER.clear();
        DIRTY.set(false);
    }

    private static void changed() {
        DIRTY.set(true);
        Executor later = flusher;
        if (later == null || file == null || !FLUSH_ASKED.compareAndSet(false, true)) {
            return;
        }
        try {
            later.execute(ActiveTitles::askedFlush);
        } catch (Throwable t) {
            // No flusher to wait on (a scheduler already shut down): write it now instead.
            FLUSH_ASKED.set(false);
            flush();
        }
    }

    private static void askedFlush() {
        FLUSH_ASKED.set(false);
        flush();
    }

    /** Read {@code target}, else the copy the atomic write kept before it, else start empty. */
    private static void load(@Nonnull Path target) {
        if (read(target)) {
            return;
        }
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        if (read(backup)) {
            SafeLog.warn("[title] " + target + " is missing or torn, so the shown titles come from "
                    + backup.getFileName());
        }
    }

    /** True when {@code source} was there and read whole into memory. */
    private static boolean read(@Nonnull Path source) {
        if (!Files.isRegularFile(source)) {
            return false;
        }
        try {
            Doc doc = GSON.fromJson(Files.readString(source), Doc.class);
            BY_PLAYER.clear();
            if (doc != null && doc.shownTitles != null) {
                for (Map.Entry<String, String> e : doc.shownTitles.entrySet()) {
                    UUID playerId = playerIdOf(e.getKey());
                    String shown = normalize(e.getValue());
                    if (playerId != null && shown != null) {
                        BY_PLAYER.put(playerId, shown);
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            BY_PLAYER.clear();
            SafeLog.warn("[title] could not read " + source + ": " + t.getMessage());
            return false;
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

    @Nullable
    private static String normalize(@Nullable String titleId) {
        return titleId == null || titleId.isBlank() ? null : titleId.trim().toLowerCase(Locale.ROOT);
    }
}
