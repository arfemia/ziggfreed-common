package com.ziggfreed.common.track.boost;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.ziggfreed.common.track.gain.GainPlans;
import com.ziggfreed.common.util.RecordFile;
import com.ziggfreed.common.util.SafeLog;

/**
 * The server boosts running, kept across a restart in {@value #FILE_NAME} beside the library's owner files
 * ({@code mods/ziggfreedcommon/}). Read once at boot ({@link #onBoot}, through {@link #init}); every start and clear
 * rewrites it at once through zc-core's {@code util/RecordFile} (the engine's atomic write with the previous write
 * kept as {@code .bak}, a {@code version} leaf), since a server boost starts rarely. A start that ran out is dropped
 * at the next write; one read back after a restart runs on to its own end, the clock having kept counting. A file
 * that will not read falls back to its {@code .bak}, then to none running (the unreadable file kept aside as
 * {@code .unreadable}); a file from a newer build is left unread and unwritten; a server with no folder keeps them in
 * memory only. Reads are lock-free; writes one at a time.
 *
 * <p>A consumer carrying server boosts over from an older system of its own hands them to {@link #seed}, which may
 * come before the boot's read: what it seeds before then waits and joins what the file kept, so neither replaces the
 * other.
 */
final class ServerBoosts {

    /** The file, beside the library's owner files. */
    public static final String FILE_NAME = "server-boosts.json";

    private static final String LOG_TAG = "boost";

    private static final Gson GSON = RecordFile.gson();

    private static final ServerBoosts SHARED = new ServerBoosts();

    @Nonnull private volatile List<BoostRun> runs = List.of();

    /** Has the boot's read run ({@link #init})? Until it has, a seed waits in {@link #pending}. */
    private boolean read;

    /** What a seed handed over before the boot's read, joined to the file's starts at {@link #init}. */
    @Nonnull private final List<BoostRun> pending = new ArrayList<>();

    /** Who waits to hear whether the boot's read wrote what they seeded before it. */
    @Nonnull private final List<Consumer<Boolean>> pendingWritten = new ArrayList<>();

    /** The file: the starts still running when it is read, every start kept when it is written. */
    private final RecordFile file = RecordFile.builder(LOG_TAG, "the server boosts", new RecordFile.Body() {
                @Override
                @Nonnull
                public JsonObject write() {
                    return toDoc(runs);
                }

                @Override
                public void read(@Nonnull JsonObject doc, int version) {
                    long now = System.currentTimeMillis();
                    List<BoostRun> live = new ArrayList<>();
                    for (BoostRun run : fromDoc(doc)) {
                        if (run.liveAt(now)) {
                            live.add(run);
                        }
                    }
                    runs = List.copyOf(live);
                }

                @Override
                public void forget() {
                    runs = List.of();
                }
            })
            .lostWrite("a restart may lose them")
            .build();

    /** The file's shape: every start kept (the record file adds the version). */
    static final class Dto {
        List<Run> runs;
    }

    /** One start as the file keeps it; {@code track} only when the start names one. */
    static final class Run {
        String boost;
        double percent;
        long startedAt;
        long endsAt;
        String by;
        String track;
    }

    private ServerBoosts() {
    }

    /** The server's one list. */
    @Nonnull
    public static ServerBoosts shared() {
        return SHARED;
    }

    /**
     * The boot's read, on {@code BootEvent} ({@link BoostBootstrap} hangs it): the list pointed at the owner folder
     * ({@link BoostOwnerLayers#directory}, a consumer's {@code setDirectory} at its own setup included) and what an
     * earlier run kept there read back. The engine dispatches it once the universe is ready and the game port is
     * bound, before the boot's commands run, so a joining player's first gain comes after it; every gain plan moves
     * after the read all the same, so a plan built before it (another subject's, a world ticking early) never keeps
     * the server's boosts out.
     */
    static void onBoot(@Nonnull BootEvent event) {
        SHARED.init(BoostOwnerLayers.directory());
        GainPlans.invalidateAll();
    }

    /**
     * Point the list at {@code dir} and read what an earlier run kept there; null keeps them in memory. What a seed
     * handed over before this joins the starts read, and the file is written with them.
     */
    public synchronized void init(@Nullable Path dir) {
        file.persistTo(dir == null ? null : dir.resolve(FILE_NAME), null);
        read = true;
        List<Consumer<Boolean>> waiting = List.copyOf(pendingWritten);
        pendingWritten.clear();
        if (pending.isEmpty()) {
            tell(waiting, holdsFile());
            return;
        }
        List<BoostRun> loaded = new ArrayList<>(runs);
        long now = System.currentTimeMillis();
        boolean added = false;
        for (BoostRun run : pending) {
            // A start the file already keeps (a carry asked again after a crash) is never kept twice.
            if (run.liveAt(now) && !loaded.contains(run)) {
                loaded.add(run);
                added = true;
            }
        }
        pending.clear();
        runs = List.copyOf(loaded);
        boolean written = added ? save() : holdsFile();
        tell(waiting, written);
    }

    /**
     * Keep {@code carried}, starts an older system ran, as they are: no limit, wait or season asked, nothing said. Before
     * the boot's read they wait for it ({@link #init}); after it they join at once and the file is written. Those that
     * have ended are dropped, and one already kept (equal in every leaf) is never kept twice, so a carry asked again
     * keeps nothing new. How many were kept or wait.
     *
     * @param written told once whether the file now holds them (true when nothing was left to write): at once after
     *                the boot's read, at it before; false when the file could not be written, was refused (a newer
     *                build's) or the server keeps them in memory only. Null for nobody.
     */
    public synchronized int seed(@Nonnull Collection<BoostRun> carried, @Nullable Consumer<Boolean> written) {
        long now = System.currentTimeMillis();
        List<BoostRun> live = new ArrayList<>(carried.size());
        for (BoostRun run : carried) {
            if (run != null && run.liveAt(now) && !live.contains(run)) {
                live.add(run);
            }
        }
        if (!read) {
            pending.addAll(live);
            if (written != null) {
                pendingWritten.add(written);
            }
            return live.size();
        }
        List<BoostRun> next = live(now);
        int kept = 0;
        for (BoostRun run : live) {
            if (!next.contains(run)) {
                next.add(run);
                kept++;
            }
        }
        boolean saved;
        if (kept > 0) {
            runs = List.copyOf(next);
            saved = save();
        } else {
            saved = holdsFile();
        }
        tell(written == null ? List.of() : List.of(written), saved);
        return kept;
    }

    /** Tell each of {@code waiting} whether the file holds what they seeded; one that throws costs only itself. */
    private static void tell(@Nonnull List<Consumer<Boolean>> waiting, boolean written) {
        for (Consumer<Boolean> one : waiting) {
            try {
                one.accept(written);
            } catch (Throwable t) {
                SafeLog.warn("[" + LOG_TAG + "] a carried boost's follow-up failed: " + t.getMessage());
            }
        }
    }

    /** Every server start still running at {@code nowMs}, in the order they began. */
    @Nonnull
    public List<BoostRun> live(long nowMs) {
        List<BoostRun> held = runs;
        List<BoostRun> out = new ArrayList<>(held.size());
        for (BoostRun run : held) {
            if (run.liveAt(nowMs)) {
                out.add(run);
            }
        }
        return out;
    }

    /** Keep {@code run}, dropping what has ended, and write the file. */
    public synchronized void add(@Nonnull BoostRun run) {
        List<BoostRun> next = live(System.currentTimeMillis());
        next.add(run);
        runs = List.copyOf(next);
        save();
    }

    /** End every server start now and write the file; how many were running. */
    public synchronized int clear() {
        int running = live(System.currentTimeMillis()).size();
        runs = List.of();
        save();
        return running;
    }

    /** {@code runs} as the file writes them, without the version leaf the record file adds. Pure. */
    @Nonnull
    static String encode(@Nonnull List<BoostRun> runs) {
        return GSON.toJson(toDoc(runs));
    }

    /** The starts {@code json} keeps, a row naming no boost left out; empty for none. Pure; throws on bad JSON. */
    @Nonnull
    static List<BoostRun> decode(@Nonnull String json) {
        return fromDoc(JsonParser.parseString(json).getAsJsonObject());
    }

    @Nonnull
    private static JsonObject toDoc(@Nonnull List<BoostRun> runs) {
        Dto dto = new Dto();
        dto.runs = new ArrayList<>(runs.size());
        for (BoostRun run : runs) {
            Run row = new Run();
            row.boost = run.boost();
            row.percent = run.percent();
            row.startedAt = run.startedAt();
            row.endsAt = run.endsAt();
            row.by = run.by();
            row.track = run.track();
            dto.runs.add(row);
        }
        return GSON.toJsonTree(dto).getAsJsonObject();
    }

    @Nonnull
    private static List<BoostRun> fromDoc(@Nonnull JsonObject doc) {
        Dto dto = GSON.fromJson(doc, Dto.class);
        List<BoostRun> out = new ArrayList<>();
        if (dto == null || dto.runs == null) {
            return out;
        }
        for (Run row : dto.runs) {
            if (row != null && row.boost != null && !row.boost.isBlank()) {
                out.add(new BoostRun(row.boost, row.percent, row.startedAt, row.endsAt, row.by == null ? "" : row.by,
                        row.track));
            }
        }
        return out;
    }

    /** Whether the file holds what memory holds once nothing is left to write: a file kept, and not refused. */
    private boolean holdsFile() {
        return file.file() != null && !file.refused();
    }

    /** Write the file now; false when it could not be written, was refused, or the server keeps them in memory only. */
    private boolean save() {
        return file.writeNow();
    }
}
