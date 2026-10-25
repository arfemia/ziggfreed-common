package com.ziggfreed.common.almanac;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.HytaleServer;
import com.ziggfreed.common.counter.Counters;
import com.ziggfreed.common.util.DataFileMove;
import com.ziggfreed.common.util.RecordFile;

/**
 * The server's own Almanac totals: for every tally key a player's record holds ({@link AlmanacKeys}),
 * what every player together has counted, so a season's tile can say "8,431 on this server" beside the
 * reader's own figure. Counts only: the file names no player.
 *
 * <p>Kept the leaderboard way: {@link #add} changes the in-memory map at once (safe from the world thread
 * the moment arrives on) and asks for one debounced flush, which writes {@value #FILE_NAME} through zc-core's
 * {@code util/RecordFile} (the engine's atomic write, the previous write kept as {@code .bak}, a
 * {@code version} leaf). The library's stop calls {@link #flushNow} once more, so the counts of the last
 * seconds before a server stops are kept too: a write carries only what changed since the last one and
 * writes nothing otherwise, and writes run one at a time (the engine's writer shares one temp file per
 * target), so the stop, a late debounced write and a second stop never collide or rewrite the same totals.
 * A file that will not read falls back to the {@code .bak}, then to empty (the unreadable file kept aside
 * as {@code .unreadable}); a file from a newer build is left unread and unwritten; a server with no data
 * folder counts in memory and writes nothing. Nothing is counted while the Almanac is switched off.
 *
 * <p>The file sits beside the library's owner files ({@code mods/ziggfreedcommon/}). The 2.2.0 builds
 * before its release kept it in the library's data folder; {@link #init(Path, Path)} moves it across
 * once, through {@link DataFileMove}.
 */
public final class ServerTallies {

    /** The totals' file, beside the library's owner files. */
    public static final String FILE_NAME = "almanac-server-tallies.json";

    /** How long a flush waits for more counts to ride along. */
    private static final long FLUSH_DEBOUNCE_MS = 3000L;

    private static final String LOG_TAG = "almanac";

    /** Runs a task once, after a delay; the production one is the server's scheduler. */
    @FunctionalInterface
    public interface Scheduler {
        void later(@Nonnull Runnable task, long delayMs);
    }

    private static final ServerTallies SHARED = new ServerTallies(ServerTallies::onServerScheduler);

    private final Scheduler scheduler;
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<String, Long> totals = new ConcurrentHashMap<>();
    /** The totals' file: one debounced write at a time, a failed one kept for the next. */
    private final RecordFile file;

    /** The file's shape: the totals by key (the record file adds the version). */
    private static final class Dto {
        Map<String, Long> totals;
    }

    public ServerTallies(@Nonnull Scheduler scheduler) {
        this.scheduler = scheduler;
        this.file = RecordFile.builder(LOG_TAG, "the server totals", new RecordFile.Body() {
                    @Override
                    @Nonnull
                    public JsonObject write() {
                        Dto dto = new Dto();
                        dto.totals = new TreeMap<>(totals);
                        return gson.toJsonTree(dto).getAsJsonObject();
                    }

                    @Override
                    public void read(@Nonnull JsonObject doc, int version) {
                        populate(gson.fromJson(doc, Dto.class));
                    }

                    @Override
                    public void forget() {
                        totals.clear();
                    }
                })
                .lostWrite("a restart may lose the latest counts")
                .build();
    }

    /** The server's one set of totals, which the moment listener adds to and the page reads. */
    @Nonnull
    public static ServerTallies shared() {
        return SHARED;
    }

    /** Point the totals at {@code dataDir} and read what an earlier run saved there. Once, at setup. */
    public void init(@Nullable Path dataDir) {
        init(dataDir, null);
    }

    /**
     * Point the totals at {@code homeDir} and read what an earlier run saved, moving the file there first
     * when a load finds it only in {@code oldDir}, the folder it sat in before ({@link DataFileMove}: a
     * file in both keeps the new one, and a move that fails reads and writes the old file this run). A null
     * {@code homeDir} counts in memory and writes nothing. Once, at setup.
     */
    public void init(@Nullable Path homeDir, @Nullable Path oldDir) {
        Path target = homeDir == null ? null : DataFileMove.settle(LOG_TAG, homeDir, oldDir, FILE_NAME).file();
        file.persistTo(target, task -> scheduler.later(task, FLUSH_DEBOUNCE_MS));
    }

    /** What every player together has counted under {@code key}; 0 for a key nothing counted. */
    public long get(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        Long total = totals.get(Counters.fold(key));
        return total == null ? 0L : total;
    }

    /** Add {@code amount} under {@code key}. A blank key, an amount of zero or less, or the Almanac off adds nothing. */
    public void add(@Nullable String key, long amount) {
        if (key == null || key.isBlank() || amount <= 0L || !AlmanacSwitch.isOn()) {
            return;
        }
        totals.merge(Counters.fold(key), amount, Long::sum);
        // Marked after the count lands, so a write that takes the mark also carries the count.
        file.changed();
    }

    /**
     * Add what rose between two readings of one player's record: each key's growth from {@code before} to
     * {@code after}, so the server counts exactly what the player's record counted, attendance included
     * (once per player per season, as the record marks it).
     */
    public void addGrowth(@Nonnull Map<String, Long> before, @Nonnull Map<String, Long> after) {
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            Long was = before.get(entry.getKey());
            long grown = (entry.getValue() == null ? 0L : entry.getValue()) - (was == null ? 0L : was);
            add(entry.getKey(), grown);
        }
    }

    /**
     * Write the totals now if anything was counted since the last write: the debounced write, and the
     * library's stop ({@code AlmanacBootstrap.shutdown}). Nothing new, or no data folder, writes nothing.
     * One write at a time, from any thread; a write that fails is logged, never thrown, and the next one
     * tries again.
     */
    public void flushNow() {
        file.flush();
    }

    private void populate(@Nullable Dto dto) {
        totals.clear();
        if (dto == null || dto.totals == null) {
            return;
        }
        for (Map.Entry<String, Long> entry : dto.totals.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null && entry.getValue() > 0L) {
                totals.merge(Counters.fold(entry.getKey()), entry.getValue(), Long::sum);
            }
        }
    }

    /** The server's scheduler; in a JVM with no server it throws and the record file writes at once. */
    private static void onServerScheduler(@Nonnull Runnable task, long delayMs) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }
}
