package com.ziggfreed.common.almanac;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.util.io.FileUtil;
import com.ziggfreed.common.util.SafeLog;

/**
 * The server's own Almanac totals: for every tally key a player's record holds ({@link AlmanacKeys}),
 * what every player together has counted, so a season's tile can say "8,431 on this server" beside the
 * reader's own figure. Counts only: the file names no player.
 *
 * <p>Kept the leaderboard way: {@link #add} changes the in-memory map at once (safe from the world thread
 * the moment arrives on) and asks for one debounced flush, which writes {@code <dataDir>/}
 * {@value #FILE_NAME} atomically through {@link FileUtil#writeStringAtomic} with the previous write kept as
 * {@code .bak}. A file that will not read falls back to the {@code .bak}, then to empty; a server with no
 * data folder counts in memory and writes nothing. Nothing is counted while the Almanac is switched off.
 */
public final class ServerTallies {

    /** The totals' file, in the library's data folder. */
    public static final String FILE_NAME = "almanac-server-tallies.json";

    /** How long a flush waits for more counts to ride along. */
    private static final long FLUSH_DEBOUNCE_MS = 3000L;

    /** Runs a task once, after a delay; the production one is the server's scheduler. */
    @FunctionalInterface
    public interface Scheduler {
        void later(@Nonnull Runnable task, long delayMs);
    }

    private static final ServerTallies SHARED = new ServerTallies(ServerTallies::onServerScheduler);

    private final Scheduler scheduler;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ConcurrentHashMap<String, Long> totals = new ConcurrentHashMap<>();
    private final AtomicBoolean flushPending = new AtomicBoolean(false);
    @Nullable private volatile Path file;

    /** The file's shape: a version for a later reader, and the totals by key. */
    private static final class Dto {
        int version = 1;
        Map<String, Long> totals;
    }

    public ServerTallies(@Nonnull Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** The server's one set of totals, which the moment listener adds to and the page reads. */
    @Nonnull
    public static ServerTallies shared() {
        return SHARED;
    }

    /** Point the totals at {@code dataDir} and read what an earlier run saved there. Once, at setup. */
    public void init(@Nullable Path dataDir) {
        totals.clear();
        if (dataDir == null) {
            file = null;
            return;
        }
        Path target = dataDir.resolve(FILE_NAME);
        file = target;
        load(target);
    }

    /** What every player together has counted under {@code key}; 0 for a key nothing counted. */
    public long get(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        Long total = totals.get(fold(key));
        return total == null ? 0L : total;
    }

    /** Add {@code amount} under {@code key}. A blank key, an amount of zero or less, or the Almanac off adds nothing. */
    public void add(@Nullable String key, long amount) {
        if (key == null || key.isBlank() || amount <= 0L || !AlmanacSwitch.isOn()) {
            return;
        }
        totals.merge(fold(key), amount, Long::sum);
        scheduleFlush();
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

    /** Write the totals now. A server with no data folder writes nothing. */
    public void flushNow() {
        Path target = file;
        if (target == null) {
            return;
        }
        try {
            Dto dto = new Dto();
            dto.totals = new TreeMap<>(totals);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            FileUtil.writeStringAtomic(target, gson.toJson(dto), true);
        } catch (Throwable t) {
            SafeLog.warn("[almanac] the server totals could not be written to " + target + ": " + t.getMessage());
        }
    }

    private void scheduleFlush() {
        if (file == null || !flushPending.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.later(() -> {
                flushPending.set(false);
                flushNow();
            }, FLUSH_DEBOUNCE_MS);
        } catch (Throwable t) {
            flushPending.set(false);
        }
    }

    private void load(@Nonnull Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try {
            populate(gson.fromJson(Files.readString(target, StandardCharsets.UTF_8), Dto.class));
            return;
        } catch (Throwable t) {
            SafeLog.warn("[almanac] " + target + " will not read (" + t.getMessage() + "), so its backup is read");
        }
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        try {
            if (Files.exists(backup)) {
                populate(gson.fromJson(Files.readString(backup, StandardCharsets.UTF_8), Dto.class));
            }
        } catch (Throwable t) {
            totals.clear();
            SafeLog.warn("[almanac] " + backup + " will not read either, so the server totals start empty: "
                    + t.getMessage());
        }
    }

    private void populate(@Nullable Dto dto) {
        totals.clear();
        if (dto == null || dto.totals == null) {
            return;
        }
        for (Map.Entry<String, Long> entry : dto.totals.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null && entry.getValue() > 0L) {
                totals.merge(fold(entry.getKey()), entry.getValue(), Long::sum);
            }
        }
    }

    @Nonnull
    private static String fold(@Nonnull String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    /** The server's scheduler; in a JVM with no server the flush is skipped (the caller resets its flag). */
    private static void onServerScheduler(@Nonnull Runnable task, long delayMs) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }
}
