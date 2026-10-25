package com.ziggfreed.common.loot.diminish;

import java.nio.file.Path;
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
 * <p>Read and written only through {@code ClaimStore.award} and {@code awardOrTopUp}, which count a clear only
 * when a payout is written (a new Claim, or a top-up of a held one), so a second write {@code award} refuses
 * never counts.
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

    private final String fileName;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ConcurrentHashMap<UUID, Map<String, Count>> counts = new ConcurrentHashMap<>();
    /** The file, kept the one library way: a newer or unreadable file is never overwritten by this build. */
    private final RecordFile file;

    /** Gson-friendly persisted shape of one key's count. */
    private static final class Count {
        String window;
        long period;
        int clears;
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
        file.persistTo(dataDir.resolve(fileName), null);
        prune(System.currentTimeMillis());
    }

    /**
     * Count one clear of {@code key} for {@code player} under {@code spec} at {@code nowMs}, and answer the
     * multiplier that clear pays at: the step the clears already counted in this window reach. A count from an
     * earlier window, or under the other window, starts over.
     */
    public synchronized double count(@Nonnull UUID player, @Nonnull String key, @Nonnull DiminishSpec spec,
            long nowMs) {
        DiminishSpec.Window window = spec.window();
        long period = window.periodIndex(nowMs);
        Map<String, Count> mine = counts.computeIfAbsent(player, k -> new HashMap<>());
        String folded = fold(key);
        Count held = mine.get(folded);
        int prior = held != null && window.word().equals(held.window) && held.period == period ? held.clears : 0;
        Count next = new Count();
        next.window = window.word();
        next.period = period;
        next.clears = prior + 1;
        mine.put(folded, next);
        flush(nowMs);
        return spec.multiplier(prior);
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

    /** Whether {@code count} was counted in its window's current period; a window no build reads never is. */
    private static boolean current(@Nonnull Count count, long nowMs) {
        DiminishSpec.Window window = DiminishSpec.Window.byWord(count.window);
        return window != null && count.period == window.periodIndex(nowMs);
    }

    /** Prune and write every count now; a refused file, or no data folder, writes nothing. */
    private void flush(long nowMs) {
        prune(nowMs);
        file.writeNow();
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
