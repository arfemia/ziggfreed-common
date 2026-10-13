package com.ziggfreed.common.instance.leaderboard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.util.io.FileUtil;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.util.DataFileMove;
import com.ziggfreed.common.util.SafeLog;

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
 *
 * <p><b>At a stop</b>: the debounce may never run before the server exits, so whoever owns a
 * board calls {@link #flushNow} from its own {@code shutdown()} (the library does for the
 * encounter board). A write carries only what changed since the last one and writes nothing
 * otherwise, writes run one at a time (the engine's writer shares one temp file per target),
 * and a write that fails keeps the change for the next, so a stop, a late debounced write and
 * a second stop never collide or rewrite the same rows.
 *
 * <p><b>Stat writes</b>: besides a play's {@link #record}, a board takes bare tallies
 * ({@link #addStats}, {@link #raiseStats}, {@link #lowerStats}, {@link #setStats}), which move only
 * the counter bag, the name and the time, never the play count or a best. A board of windows
 * (a week's bucket, a month's) drops the old ones through {@link #retain}, and a one-time import
 * into a board claims an id in its {@link #importOnce imports} list, saved with its rows.
 */
public final class Leaderboard {

    private static final long FLUSH_DEBOUNCE_SECONDS = 3L;

    private final String fileName;
    private final Gson gson;
    /** bucket key -> uuid -> entry. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<UUID, LeaderboardEntry>> buckets = new ConcurrentHashMap<>();
    /** Every one-time import this board has taken, by claim id. */
    private final Set<String> imports = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean flushPending = new AtomicBoolean(false);
    /** A row memory holds that the file does not yet. */
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    /** One write at a time. */
    private final Object writeLock = new Object();
    private final Executor flusher;
    @Nullable private volatile Path file;

    /**
     * On-disk shape: bucket(string) -> uuid(string) -> entry, plus the claimed imports (absent on a
     * board that took none, so a board without them keeps the shape it always had).
     */
    private static final class Dto {
        Map<String, Map<String, LeaderboardEntry>> buckets;
        @Nullable List<String> imports;
    }

    /** @param name the board file base name (e.g. {@code "leaderboard"} -> {@code leaderboard.json}). */
    public Leaderboard(@Nonnull String name) {
        this(name, onServerScheduler(FLUSH_DEBOUNCE_SECONDS), false);
    }

    /**
     * As {@link #Leaderboard(String)}, with the debounced write handed to {@code flushOn} instead of
     * the server's scheduler (three seconds later): a test's own executor, or a consumer's.
     */
    public Leaderboard(@Nonnull String name, @Nonnull Executor flushOn) {
        this(name, flushOn, false);
    }

    /**
     * A board written {@code debounceSeconds} after the first change it holds, as compact JSON when
     * {@code compact}: a board every player writes all the time (the stat board, at 10 seconds) waits
     * longer and spends fewer bytes on a write than one a finished fight writes now and then.
     */
    public Leaderboard(@Nonnull String name, long debounceSeconds, boolean compact) {
        this(name, onServerScheduler(Math.max(1L, debounceSeconds)), compact);
    }

    /** As {@link #Leaderboard(String, long, boolean)}, with the debounced write handed to {@code flushOn}. */
    public Leaderboard(@Nonnull String name, @Nonnull Executor flushOn, boolean compact) {
        this.fileName = name.endsWith(".json") ? name : name + ".json";
        this.flusher = flushOn;
        this.gson = compact ? new Gson() : new GsonBuilder().setPrettyPrinting().create();
    }

    /** Resolve the data file under {@code dataDir} and load any existing board. Call once at setup. */
    public void init(@Nullable Path dataDir) {
        init(dataDir, null);
    }

    /**
     * As {@link #init(Path)} for a board whose file moved folders: a load that finds it only in
     * {@code oldDir}, the folder it sat in before, moves it (and its {@code .bak}) to {@code dataDir}
     * first; a file in both keeps the one in {@code dataDir} and leaves the old alone; a move that fails
     * reads and writes the old file this session ({@link DataFileMove}). Call once at setup.
     */
    public void init(@Nullable Path dataDir, @Nullable Path oldDir) {
        if (dataDir == null) {
            warn("leaderboard '" + fileName + "': no data directory; persistence disabled this session.");
            return;
        }
        this.file = DataFileMove.settle("leaderboard", dataDir, oldDir, fileName).file();
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
     * As {@link #record(String, UUID, String, int, int, boolean)} but also merges the given per-stat
     * deltas into the entry's counter bag (keys are consumer-chosen; avoid the reserved
     * {@link LeaderboardEntry#TOTAL_POINTS}). A null or empty {@code statDeltas} records no stats.
     * Total points accrues by {@code score} on either overload.
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
            if (win && timeSeconds > 0 && (e.bestTimeSeconds <= 0 || timeSeconds < e.bestTimeSeconds)) {
                e.bestTimeSeconds = timeSeconds;
            }
            // Copy on write: a flush or a page may be walking the bag this entry holds right now.
            CounterMap next = nextBag(e);
            next.add(LeaderboardEntry.TOTAL_POINTS, score);
            next.mergeSums(statDeltas);
            e.counters = next;
            return e;
        });
        // Marked after the row lands, so a write that takes the mark also carries the row.
        dirty.set(true);
        scheduleFlush();
    }

    // ==================== stat writes ====================

    /** How a stat write moves each tally it names. */
    private enum StatMove {
        /** Add the value: a count. */
        ADD,
        /** Keep the higher: a best that grows. */
        RAISE,
        /** Keep the lower, any value beating none: a best that shrinks, such as a fastest time. */
        LOWER,
        /** Write the value outright, zero clearing it: a gauge read from elsewhere. */
        SET
    }

    /**
     * Add each of {@code deltas} to the player's tallies in {@code bucket}. Unlike {@link #record} it
     * bumps no play count and touches no best: only the counter bag, the name (when one is given) and
     * the time. A null or empty map writes nothing. Safe from any thread.
     */
    public void addStats(@Nonnull String bucket, @Nonnull UUID uuid, @Nullable String name,
                         @Nullable Map<String, Long> deltas) {
        writeStats(bucket, uuid, name, deltas, StatMove.ADD);
    }

    /** As {@link #addStats}, keeping the higher of each held tally and its value: a best that grows. */
    public void raiseStats(@Nonnull String bucket, @Nonnull UUID uuid, @Nullable String name,
                           @Nullable Map<String, Long> values) {
        writeStats(bucket, uuid, name, values, StatMove.RAISE);
    }

    /**
     * As {@link #addStats}, keeping the lower of each held tally and its value: a best that shrinks,
     * such as a fastest time. An absent tally takes the value; a value of zero or less is no record
     * and writes nothing, since a bag never holds a zero.
     */
    public void lowerStats(@Nonnull String bucket, @Nonnull UUID uuid, @Nullable String name,
                           @Nullable Map<String, Long> values) {
        writeStats(bucket, uuid, name, values, StatMove.LOWER);
    }

    /**
     * As {@link #addStats}, writing each value outright: a gauge read from elsewhere (a level, a
     * native record). A zero clears the tally.
     */
    public void setStats(@Nonnull String bucket, @Nonnull UUID uuid, @Nullable String name,
                         @Nullable Map<String, Long> values) {
        writeStats(bucket, uuid, name, values, StatMove.SET);
    }

    private void writeStats(@Nonnull String bucket, @Nonnull UUID uuid, @Nullable String name,
                            @Nullable Map<String, Long> values, @Nonnull StatMove move) {
        if (bucket.isBlank() || values == null || values.isEmpty()) {
            return;
        }
        ConcurrentHashMap<UUID, LeaderboardEntry> b = buckets.computeIfAbsent(bucket, k -> new ConcurrentHashMap<>());
        b.compute(uuid, (k, existing) -> {
            LeaderboardEntry e = existing != null ? existing : new LeaderboardEntry();
            e.lastUpdatedMs = System.currentTimeMillis();
            if (name != null && !name.isBlank()) {
                e.name = name;
            }
            // Copy on write: a flush or a page may be walking the bag this entry holds right now.
            CounterMap next = nextBag(e);
            for (Map.Entry<String, Long> v : values.entrySet()) {
                String key = v.getKey();
                Long value = v.getValue();
                if (key == null || key.isBlank() || value == null) {
                    continue;
                }
                move(next, key, value, move);
            }
            e.counters = next;
            return e;
        });
        dirty.set(true);
        scheduleFlush();
    }

    /**
     * A copy of {@code e}'s bag for a write to change and then swap in whole. A bag an entry has
     * published is never changed in place, so a flush serializing it and a page summing it, both off the
     * bucket's lock, never meet a write mid-way; the entry's field is volatile, so the swap is seen.
     */
    @Nonnull
    private static CounterMap nextBag(@Nonnull LeaderboardEntry e) {
        CounterMap held = e.counters;
        return held == null ? new CounterMap() : held.copy();
    }

    private static void move(@Nonnull CounterMap bag, @Nonnull String key, long value, @Nonnull StatMove move) {
        switch (move) {
            case ADD -> bag.add(key, value);
            case RAISE -> bag.highWater(key, value);
            case LOWER -> {
                long held = bag.get(key);
                if (value > 0L && (held <= 0L || value < held)) {
                    bag.set(key, value);
                }
            }
            case SET -> bag.set(key, value);
        }
    }

    // ==================== windows and imports ====================

    /**
     * Keep only the newest {@code keep} buckets whose key starts with {@code prefix}, newest by the
     * key's own order (a window key that sorts by time, such as {@code weekly@2026-W42}); every older
     * one is dropped. A {@code keep} below one keeps one. Returns how many buckets it dropped.
     */
    public int retain(@Nonnull String prefix, int keep) {
        return retain(prefix, keep, Comparator.naturalOrder());
    }

    /** As {@link #retain(String, int)}, newest by {@code order} (oldest first), for keys that do not sort as text. */
    public int retain(@Nonnull String prefix, int keep, @Nonnull Comparator<String> order) {
        List<String> matching = new ArrayList<>();
        for (String key : buckets.keySet()) {
            if (key.startsWith(prefix)) {
                matching.add(key);
            }
        }
        int kept = Math.max(1, keep);
        if (matching.size() <= kept) {
            return 0;
        }
        matching.sort(order);
        int drop = matching.size() - kept;
        for (int i = 0; i < drop; i++) {
            buckets.remove(matching.get(i));
        }
        dirty.set(true);
        scheduleFlush();
        return drop;
    }

    /** True when this board has taken the one-time import {@code claimId}. */
    public boolean imported(@Nonnull String claimId) {
        return imports.contains(claimId);
    }

    /**
     * Run the one-time import {@code claimId} unless this board has taken it, then claim it, saved with
     * the rows: true only for the call that ran it. Imports run one at a time, so of two callers on one
     * id exactly one runs. The claim lands after {@code writes} return, so a file write carrying the
     * claim carries the rows too; a throw from {@code writes} claims nothing and reaches the caller, and
     * the next call runs it again, so an import writes absolute values ({@link #setStats}), which a
     * second run leaves as they were, rather than adding.
     */
    public boolean importOnce(@Nonnull String claimId, @Nonnull Runnable writes) {
        if (claimId.isBlank()) {
            return false;
        }
        synchronized (imports) {
            if (imports.contains(claimId)) {
                return false;
            }
            writes.run();
            imports.add(claimId);
        }
        dirty.set(true);
        scheduleFlush();
        return true;
    }

    /** A snapshot of one bucket (uuid -> best entry). Never null. */
    @Nonnull
    public Map<UUID, LeaderboardEntry> forBucket(@Nonnull String bucket) {
        Map<UUID, LeaderboardEntry> b = buckets.get(bucket);
        return b == null ? Map.of() : Map.copyOf(b);
    }

    /**
     * A GLOBAL aggregate across the given buckets: each player's entries merged into one
     * (sum every cumulative tally plus {@code plays}, max {@code bestScore}, min winning
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

    /**
     * Merge {@code src} into a fresh copy of {@code dst} for the cross-bucket aggregate. Each field
     * keeps the rule its meaning demands: a BEST takes the better of the two, a play count adds, and
     * every cumulative tally (total points included) adds through the counter bag's own summing.
     */
    @Nonnull
    private static LeaderboardEntry mergeInto(@Nonnull LeaderboardEntry dst, @Nonnull LeaderboardEntry src) {
        LeaderboardEntry e = new LeaderboardEntry();
        e.bestScore = Math.max(dst.bestScore, src.bestScore);
        e.plays = dst.plays + src.plays;
        e.lastUpdatedMs = Math.max(dst.lastUpdatedMs, src.lastUpdatedMs);
        e.name = src.lastUpdatedMs >= dst.lastUpdatedMs ? orOther(src.name, dst.name) : orOther(dst.name, src.name);
        int dt = dst.bestTimeSeconds;
        int st = src.bestTimeSeconds;
        e.bestTimeSeconds = dt <= 0 ? st : (st <= 0 ? dt : Math.min(dt, st));
        e.counters().mergeSums(dst.counters);
        e.counters().mergeSums(src.counters);
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
        imports.clear();
        if (dto != null && dto.imports != null) {
            for (String claim : dto.imports) {
                if (claim != null && !claim.isBlank()) {
                    imports.add(claim);
                }
            }
        }
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
                flusher.execute(() -> {
                    flushPending.set(false);
                    flushNow();
                });
            } catch (Throwable t) {
                flushPending.set(false); // no server scheduler (unit JVM) -> skip persistence
            }
        }
    }

    /** The server's scheduler, {@code seconds} later; in a JVM with no server it throws and the caller skips the flush. */
    @Nonnull
    private static Executor onServerScheduler(long seconds) {
        return task -> HytaleServer.SCHEDULED_EXECUTOR.schedule(task, seconds, TimeUnit.SECONDS);
    }

    /**
     * Write the board now if anything was recorded since the last write: the debounced write, and
     * the owner's stop. Nothing new, or no data directory, writes nothing. One write at a time, from
     * any thread; a write that fails is logged, never thrown, and the next one tries again.
     */
    public void flushNow() {
        synchronized (writeLock) {
            Path f = file;
            if (f == null || !dirty.getAndSet(false)) {
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
                if (!imports.isEmpty()) {
                    List<String> claims = new ArrayList<>(imports);
                    claims.sort(Comparator.naturalOrder());
                    dto.imports = claims;
                }
                if (f.getParent() != null) {
                    Files.createDirectories(f.getParent());
                }
                FileUtil.writeStringAtomic(f, gson.toJson(dto), true);
            } catch (Throwable t) {
                dirty.set(true);
                warn("leaderboard '" + fileName + "' flush failed: " + t.getMessage());
            }
        }
    }

    private static void warn(@Nonnull String msg) {
        SafeLog.warn(msg);
    }
}
