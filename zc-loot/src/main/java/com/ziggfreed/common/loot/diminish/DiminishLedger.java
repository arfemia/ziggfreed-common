package com.ziggfreed.common.loot.diminish;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.ziggfreed.common.util.RecordFile;

/**
 * How many times each player has earned each diminishing reward in its current window, kept across a
 * restart: per player and key, the window it was counted in and the clears in it. A count from an
 * earlier window reads as none, and the file keeps only current windows (it is pruned on load and on
 * every write).
 *
 * <p>Written only through {@code ClaimStore.award} and {@code awardOrTopUp}, which count a clear only
 * when a payout is written (a new Claim, or a top-up of a held one), so a second write {@code award} refuses
 * never counts; read for a page through {@code ClaimStore.nextShare} ({@link #next}), which writes nothing.
 *
 * <p>A payout that names an occasion counts once per occasion ({@link #count(UUID, String, DiminishSpec, String,
 * long)}): every payout of one key naming the occasion the count last counted, in the same window, pays at the step
 * that first one counted and counts nothing more, so several chests of one run count as one clear. A count keeps
 * the occasion it was last counted for as {@code occasion}, an additive field: a file written before it reads every
 * count as naming none, and the version does not move for it.
 *
 * <p>A rolling window ({@link DiminishSpec#isRolling}) keeps the times of the clears still inside its span as
 * {@code times}, another additive field (at most as many as the spec has steps, since a clear past the last step
 * pays the last step whatever came before), and its count is how many of them are younger than the span: each
 * falls out on its own as its time passes, with no reset at a boundary. A count recorded under one window word reads
 * as none under another, as a daily count reads under a weekly spec.
 *
 * <p>The file is kept through zc-core's {@code util/RecordFile} and carries a {@code "version"}; a file
 * declaring a newer version than this build reads is left unread, and unwritten, with one warning, a torn file
 * reads its {@code .bak}, and a file this build cannot read at all, backup included (a damaged or cut-short
 * one), is left unread and unwritten too, so one bad read never wipes the counts on disk. A missing or empty
 * file is a fresh start, not a refusal. A write that fails keeps the count in memory.
 */
public final class DiminishLedger {

    /** The one file shape this build reads and writes. */
    static final int SCHEMA_VERSION = 1;

    private static final DiminishLedger SHARED = new DiminishLedger("claims-diminish");

    /** How often a ledger kept only in memory sweeps out the counts whose window has passed. */
    static final long MEMORY_PRUNE_MS = 60_000L;

    private final String fileName;
    /** Whether {@link #init} gave the ledger a file: a ledger kept in memory prunes on a cadence, never per count. */
    private boolean persisted;
    /** When a ledger kept in memory last swept. */
    private long lastPruneMs;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ConcurrentHashMap<UUID, Map<String, Count>> counts = new ConcurrentHashMap<>();
    /** The file, kept the one library way: a newer or unreadable file is never overwritten by this build. */
    private final RecordFile file;

    /** Gson-friendly persisted shape of one key's count. */
    private static final class Count {
        String window;
        long period;
        int clears;
        /** Appended: the occasion the last clear was counted for, trimmed; absent for none. */
        String occasion;
        /** Appended: a rolling window's clear times still inside its span, oldest first; absent for a calendar one. */
        long[] times;
    }

    /** The file's leaves; its {@code version} is the record file's. */
    private static final class Dto {
        Map<String, Map<String, Count>> players;
    }

    public DiminishLedger(@Nonnull String name) {
        this.fileName = name.endsWith(".json") ? name : name + ".json";
        this.file = RecordFile.builder("claims", "the diminishing-return counts in " + fileName,
                        new RecordFile.Body() {
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
                                counts.clear();
                            }
                        })
                .version(SCHEMA_VERSION)
                .lostWrite("a restart may forget a clear counted this window")
                .refuseUnreadable()
                .build();
    }

    /** The library's one ledger, the one the library's claim store counts through. */
    @Nonnull
    public static DiminishLedger shared() {
        return SHARED;
    }

    /** Resolve the file under {@code dataDir} and load any counts still in their window. Call once at setup. */
    public synchronized void init(@Nullable Path dataDir) {
        if (dataDir == null) {
            return;
        }
        persisted = true;
        file.persistTo(dataDir.resolve(fileName), null);
        prune(System.currentTimeMillis());
    }

    /**
     * Count one clear of {@code key} for {@code player} under {@code spec} at {@code nowMs}, and answer the
     * multiplier that clear pays at: the step the clears already counted in this window reach. A count from an
     * earlier window, or under the other window, starts over. A payout naming no occasion: the
     * {@link #count(UUID, String, DiminishSpec, String, long)} form with none.
     */
    public synchronized double count(@Nonnull UUID player, @Nonnull String key, @Nonnull DiminishSpec spec,
            long nowMs) {
        return count(player, key, spec, null, nowMs);
    }

    /**
     * {@link #count(UUID, String, DiminishSpec, long)} for a payout that names an occasion (a run, say): a payout
     * counts once per occasion. When {@code occasion} is not blank and {@code player}'s count of {@code key} is
     * current (this window, this period), holds at least one clear and was last counted for the same occasion
     * (without regard to case), this answers the step that clear paid at and counts nothing, so nothing is written;
     * otherwise it counts one clear as the plain form does and keeps {@code occasion} (none for a blank one) as the
     * one the count was last counted for. A count only remembers its last occasion: a payout naming another occasion
     * counts, and one naming the first again after it counts again.
     *
     * @param occasion what the payout belongs to, matched without regard to case; null or blank for none
     */
    public synchronized double count(@Nonnull UUID player, @Nonnull String key, @Nonnull DiminishSpec spec,
            @Nullable String occasion, long nowMs) {
        Map<String, Count> mine = counts.computeIfAbsent(player, k -> new HashMap<>());
        String folded = fold(key);
        Count held = mine.get(folded);
        int prior = priorClears(held, spec, nowMs);
        String named = occasion == null || occasion.isBlank() ? null : occasion.trim();
        if (named != null && prior > 0 && held.occasion != null && named.equalsIgnoreCase(held.occasion.trim())) {
            return spec.multiplier(prior - 1);
        }
        Count next = new Count();
        next.window = spec.windowWord();
        next.occasion = named;
        if (spec.isRolling()) {
            next.times = withClear(held == null || !next.window.equals(held.window) ? null : held.times,
                    nowMs, spec.rollingMs(), spec.steps().length);
            next.clears = next.times.length;
        } else {
            next.period = spec.window().periodIndex(nowMs);
            next.clears = prior + 1;
        }
        mine.put(folded, next);
        flush(nowMs);
        return spec.multiplier(prior);
    }

    /**
     * How many clears {@code held} counted that still count under {@code spec} at {@code nowMs}: a calendar count
     * of the same window and period, or a rolling count's clears younger than its span; none for a count recorded
     * under another window word.
     */
    private static int priorClears(@Nullable Count held, @Nonnull DiminishSpec spec, long nowMs) {
        if (held == null || !spec.windowWord().equals(held.window)) {
            return 0;
        }
        if (spec.isRolling()) {
            return inSpan(held.times, nowMs, spec.rollingMs());
        }
        return held.period == spec.window().periodIndex(nowMs) ? held.clears : 0;
    }

    /** How many of {@code times} are younger than {@code spanMs} at {@code nowMs}. Pure. */
    static int inSpan(@Nullable long[] times, long nowMs, long spanMs) {
        if (times == null) {
            return 0;
        }
        int n = 0;
        for (long t : times) {
            if (nowMs - t < spanMs) {
                n++;
            }
        }
        return n;
    }

    /**
     * {@code times} with a clear at {@code nowMs} added, those older than {@code spanMs} dropped, and at most
     * {@code keep} kept (the newest): a clear past the last step pays the last step whatever the older ones were,
     * so older times change nothing. Oldest first. Pure.
     */
    @Nonnull
    static long[] withClear(@Nullable long[] times, long nowMs, long spanMs, int keep) {
        int cap = Math.max(1, keep);
        long[] live = new long[(times == null ? 0 : times.length) + 1];
        int n = 0;
        if (times != null) {
            for (long t : times) {
                if (nowMs - t < spanMs) {
                    live[n++] = t;
                }
            }
        }
        live[n++] = nowMs;
        int from = Math.max(0, n - cap);
        return Arrays.copyOfRange(live, from, n);
    }

    /**
     * The multiplier {@code player}'s next clear of {@code key} would pay at under {@code spec} at {@code nowMs}: the
     * step the clears already counted in this window reach, as {@link #count} would answer for a payout naming no
     * occasion, counting nothing and writing nothing. A count from an earlier window, or under the other window,
     * reads as none, so a new window answers the first step.
     */
    public synchronized double next(@Nonnull UUID player, @Nonnull String key, @Nonnull DiminishSpec spec,
            long nowMs) {
        Map<String, Count> mine = counts.get(player);
        Count held = mine == null ? null : mine.get(fold(key));
        return spec.multiplier(priorClears(held, spec, nowMs));
    }

    // ==================== persistence ====================

    private void readDoc(@Nonnull JsonObject doc) {
        Dto dto = gson.fromJson(doc, Dto.class);
        counts.clear();
        if (dto == null || dto.players == null) {
            return;
        }
        for (Map.Entry<String, Map<String, Count>> e : dto.players.entrySet()) {
            UUID player = uuid(e.getKey());
            if (player == null || e.getValue() == null) {
                continue;
            }
            Map<String, Count> mine = new HashMap<>();
            for (Map.Entry<String, Count> c : e.getValue().entrySet()) {
                if (c.getKey() != null && c.getValue() != null) {
                    mine.put(fold(c.getKey()), c.getValue());
                }
            }
            counts.put(player, mine);
        }
    }

    @Nonnull
    private JsonObject writeDoc() {
        Dto dto = new Dto();
        dto.players = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Count>> e : counts.entrySet()) {
            dto.players.put(e.getKey().toString(), new HashMap<>(e.getValue()));
        }
        return gson.toJsonTree(dto).getAsJsonObject();
    }

    /** Drop every count whose window has passed, and every player left with none. */
    private void prune(long nowMs) {
        Iterator<Map.Entry<UUID, Map<String, Count>>> players = counts.entrySet().iterator();
        while (players.hasNext()) {
            Map<String, Count> mine = players.next().getValue();
            mine.values().removeIf(c -> !current(c, nowMs));
            if (mine.isEmpty()) {
                players.remove();
            }
        }
    }

    /**
     * Whether {@code count} still counts: counted in its calendar window's current period, or holding a clear younger
     * than its rolling span; a window word no build reads never does.
     */
    private static boolean current(@Nonnull Count count, long nowMs) {
        DiminishSpec.Window window = DiminishSpec.Window.byWord(count.window);
        if (window != null) {
            return count.period == window.periodIndex(nowMs);
        }
        long span = DiminishSpec.spanMs(count.window);
        return span > 0L && inSpan(count.times, nowMs, span) > 0;
    }

    /**
     * Prune and write every count now; a refused file, or no data folder, writes nothing. A ledger kept in memory
     * alone (no {@link #init} folder, a track source's moments) sweeps at most once a {@link #MEMORY_PRUNE_MS}, since
     * a count past its window already reads as none and a sweep per count would walk every count of every player.
     */
    private void flush(long nowMs) {
        if (persisted) {
            prune(nowMs);
            file.writeNow();
            return;
        }
        if (nowMs - lastPruneMs >= MEMORY_PRUNE_MS || nowMs < lastPruneMs) {
            lastPruneMs = nowMs;
            prune(nowMs);
        }
    }

    @Nonnull
    private static String fold(@Nonnull String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static UUID uuid(@Nullable String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
