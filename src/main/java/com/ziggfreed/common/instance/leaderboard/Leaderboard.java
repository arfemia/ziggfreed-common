package com.ziggfreed.common.instance.leaderboard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.util.io.FileUtil;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * A generic, mod-agnostic, bucketed, UUID-keyed leaderboard persisted as JSON - the
 * reusable lift of Kweebec's {@code score/Leaderboard} (the PARADIGM the user asked to
 * generalize). A consumer constructs one per named board, picks a BUCKET STRATEGY by
 * passing any String bucket key ({@code String.valueOf(partySize)}, a preset id, or
 * {@code "global"}), and records a best score + best winning time per player.
 *
 * <p><b>Durability + threading</b> (preserved verbatim from Kweebec): {@link #record}
 * mutates the in-memory {@link ConcurrentHashMap} immediately (safe from a world-thread
 * resolve path) and schedules a DEBOUNCED atomic flush off-thread
 * ({@link FileUtil#writeStringAtomic} temp-file rename + {@code .bak} fallback), so the
 * caller never blocks on disk and concurrent records coalesce into one write. A corrupt
 * file degrades to the {@code .bak}, then to an empty board.
 */
public final class Leaderboard {

    private static final long FLUSH_DEBOUNCE_SECONDS = 3L;

    private final String fileName;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    /** bucket key -> uuid -> entry. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<UUID, LeaderboardEntry>> buckets = new ConcurrentHashMap<>();
    private final AtomicBoolean flushPending = new AtomicBoolean(false);
    @Nullable private volatile Path file;

    /** On-disk shape: bucket(string) -> uuid(string) -> entry. */
    private static final class Dto {
        Map<String, Map<String, LeaderboardEntry>> buckets;
    }

    /** @param name the board file base name (e.g. {@code "leaderboard"} -> {@code leaderboard.json}). */
    public Leaderboard(@Nonnull String name) {
        this.fileName = name.endsWith(".json") ? name : name + ".json";
    }

    /** Resolve the data file under {@code dataDir} and load any existing board. Call once at setup. */
    public void init(@Nullable Path dataDir) {
        if (dataDir == null) {
            warn("leaderboard '" + fileName + "': no data directory; persistence disabled this session.");
            return;
        }
        this.file = dataDir.resolve(fileName);
        load();
    }

    /**
     * Record a player's result into {@code bucket}: bump plays, store the latest name,
     * keep the higher score, and (for a win) keep the lower completion time. Schedules a
     * debounced flush. Safe to call from the world-thread resolve path.
     */
    public void record(@Nonnull String bucket, @Nonnull UUID uuid, @Nullable String name,
                       int score, int timeSeconds, boolean win) {
        record(bucket, uuid, name, score, timeSeconds, win, null);
    }

    /**
     * As {@link #record(String, UUID, String, int, int, boolean)} but also accrues the cumulative
     * {@code totalPoints} (by {@code score}) and merges the given per-stat deltas into the entry's
     * stat counters (keys are consumer-chosen). A null/empty {@code statDeltas} records no stats.
     */
    public void record(@Nonnull String bucket, @Nonnull UUID uuid, @Nullable String name,
                       int score, int timeSeconds, boolean win, @Nullable Map<String, Long> statDeltas) {
        if (bucket.isBlank()) {
            return;
        }
        ConcurrentHashMap<UUID, LeaderboardEntry> b = buckets.computeIfAbsent(bucket, k -> new ConcurrentHashMap<>());
        b.compute(uuid, (k, existing) -> {
            LeaderboardEntry e = existing != null ? existing : new LeaderboardEntry();
            e.plays++;
            e.lastUpdatedMs = System.currentTimeMillis();
            if (name != null && !name.isBlank()) {
                e.name = name;
            }
            if (score > e.bestScore) {
                e.bestScore = score;
            }
            e.totalPoints += score;
            if (win && timeSeconds > 0 && (e.bestTimeSeconds <= 0 || timeSeconds < e.bestTimeSeconds)) {
                e.bestTimeSeconds = timeSeconds;
            }
            if (statDeltas != null && !statDeltas.isEmpty()) {
                if (e.stats == null) {
                    e.stats = new ConcurrentHashMap<>();
                }
                for (Map.Entry<String, Long> d : statDeltas.entrySet()) {
                    if (d.getKey() != null && d.getValue() != null) {
                        e.stats.merge(d.getKey(), d.getValue(), Long::sum);
                    }
                }
            }
            return e;
        });
        scheduleFlush();
    }

    /** A snapshot of one bucket (uuid -> best entry). Never null. */
    @Nonnull
    public Map<UUID, LeaderboardEntry> forBucket(@Nonnull String bucket) {
        Map<UUID, LeaderboardEntry> b = buckets.get(bucket);
        return b == null ? Map.of() : Map.copyOf(b);
    }

    /**
     * A GLOBAL aggregate across the given buckets: each player's entries merged into one
     * (sum {@code totalPoints}/{@code plays}/each stat, max {@code bestScore}, min winning
     * {@code bestTimeSeconds}, latest name). The "both granularities" seam: per-bucket stays
     * {@link #forBucket}; this is the lifetime view (Kweebec's Stats tab sums every difficulty x
     * party-size bucket of a mode). Never null.
     */
    @Nonnull
    public Map<UUID, LeaderboardEntry> forBuckets(@Nonnull Collection<String> bucketKeys) {
        Map<UUID, LeaderboardEntry> out = new LinkedHashMap<>();
        for (String key : bucketKeys) {
            ConcurrentHashMap<UUID, LeaderboardEntry> b = buckets.get(key);
            if (b == null) {
                continue;
            }
            for (Map.Entry<UUID, LeaderboardEntry> pe : b.entrySet()) {
                out.merge(pe.getKey(), pe.getValue(), Leaderboard::mergeInto);
            }
        }
        return out;
    }

    /** Merge {@code src} into a fresh copy of {@code dst} for the cross-bucket aggregate. */
    @Nonnull
    private static LeaderboardEntry mergeInto(@Nonnull LeaderboardEntry dst, @Nonnull LeaderboardEntry src) {
        LeaderboardEntry e = new LeaderboardEntry();
        e.bestScore = Math.max(dst.bestScore, src.bestScore);
        e.plays = dst.plays + src.plays;
        e.totalPoints = dst.totalPoints + src.totalPoints;
        e.lastUpdatedMs = Math.max(dst.lastUpdatedMs, src.lastUpdatedMs);
        e.name = src.lastUpdatedMs >= dst.lastUpdatedMs ? orOther(src.name, dst.name) : orOther(dst.name, src.name);
        int dt = dst.bestTimeSeconds;
        int st = src.bestTimeSeconds;
        e.bestTimeSeconds = dt <= 0 ? st : (st <= 0 ? dt : Math.min(dt, st));
        if ((dst.stats != null && !dst.stats.isEmpty()) || (src.stats != null && !src.stats.isEmpty())) {
            e.stats = new HashMap<>();
            if (dst.stats != null) {
                e.stats.putAll(dst.stats);
            }
            if (src.stats != null) {
                for (Map.Entry<String, Long> s : src.stats.entrySet()) {
                    e.stats.merge(s.getKey(), s.getValue(), Long::sum);
                }
            }
        }
        return e;
    }

    @Nullable
    private static String orOther(@Nullable String a, @Nullable String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** All bucket keys that currently hold entries. */
    @Nonnull
    public Set<String> bucketKeys() {
        return Set.copyOf(buckets.keySet());
    }

    // ==================== persistence ====================

    private void load() {
        Path f = file;
        if (f == null || !Files.exists(f)) {
            return;
        }
        try {
            populate(gson.fromJson(Files.readString(f), Dto.class));
            return;
        } catch (Throwable t) {
            warn(fileName + " unreadable (" + t.getMessage() + "); trying .bak");
        }
        try {
            Path bak = f.resolveSibling(f.getFileName().toString() + ".bak");
            if (Files.exists(bak)) {
                populate(gson.fromJson(Files.readString(bak), Dto.class));
            }
        } catch (Throwable t) {
            warn(fileName + " .bak unreadable; starting empty: " + t.getMessage());
        }
    }

    private void populate(@Nullable Dto dto) {
        buckets.clear();
        if (dto == null || dto.buckets == null) {
            return;
        }
        for (Map.Entry<String, Map<String, LeaderboardEntry>> be : dto.buckets.entrySet()) {
            ConcurrentHashMap<UUID, LeaderboardEntry> bucket = new ConcurrentHashMap<>();
            if (be.getValue() != null) {
                for (Map.Entry<String, LeaderboardEntry> pe : be.getValue().entrySet()) {
                    if (pe.getValue() == null) {
                        continue;
                    }
                    try {
                        bucket.put(UUID.fromString(pe.getKey()), pe.getValue());
                    } catch (Throwable ignored) {
                        // skip a malformed uuid key
                    }
                }
            }
            buckets.put(be.getKey(), bucket);
        }
    }

    private void scheduleFlush() {
        if (file == null) {
            return;
        }
        if (flushPending.compareAndSet(false, true)) {
            try {
                HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                    flushPending.set(false);
                    flushNow();
                }, FLUSH_DEBOUNCE_SECONDS, TimeUnit.SECONDS);
            } catch (Throwable t) {
                flushPending.set(false); // no server scheduler (unit JVM) -> skip persistence
            }
        }
    }

    private void flushNow() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            Dto dto = new Dto();
            dto.buckets = new HashMap<>();
            for (Map.Entry<String, ConcurrentHashMap<UUID, LeaderboardEntry>> be : buckets.entrySet()) {
                Map<String, LeaderboardEntry> bucket = new HashMap<>();
                for (Map.Entry<UUID, LeaderboardEntry> pe : be.getValue().entrySet()) {
                    bucket.put(pe.getKey().toString(), pe.getValue());
                }
                dto.buckets.put(be.getKey(), bucket);
            }
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            FileUtil.writeStringAtomic(f, gson.toJson(dto), true);
        } catch (Throwable t) {
            warn("leaderboard '" + fileName + "' flush failed: " + t.getMessage());
        }
    }

    private static void warn(@Nonnull String msg) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("%s", msg);
        } catch (Throwable ignored) {
        }
    }
}
