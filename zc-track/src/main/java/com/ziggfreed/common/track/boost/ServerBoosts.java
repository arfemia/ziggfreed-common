package com.ziggfreed.common.track.boost;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.util.io.FileUtil;
import com.ziggfreed.common.track.gain.GainPlans;
import com.ziggfreed.common.util.SafeLog;

/**
 * The server boosts running, kept across a restart in {@value #FILE_NAME} beside the library's owner files
 * ({@code mods/ziggfreedcommon/}). Read once at boot ({@link #onBoot}, through {@link #init}); every start and clear
 * rewrites it at once, atomically through the engine's writer with the previous write kept as {@code .bak}, since a
 * server boost starts rarely. A start that ran out is dropped at the next write; one read back after a restart runs on to its
 * own end, the clock having kept counting. A file that will not read falls back to its {@code .bak}, then to none
 * running; a server with no folder keeps them in memory only. Reads are lock-free; writes one at a time.
 */
final class ServerBoosts {

    /** The file, beside the library's owner files. */
    public static final String FILE_NAME = "server-boosts.json";

    private static final String LOG_TAG = "boost";

    private static final ServerBoosts SHARED = new ServerBoosts();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Nonnull private volatile List<BoostRun> runs = List.of();
    @Nullable private volatile Path file;

    /** The file's shape: a version for a later reader, and every start kept. */
    static final class Dto {
        int version = 1;
        List<Run> runs;
    }

    /** One start as the file keeps it. */
    static final class Run {
        String boost;
        double percent;
        long startedAt;
        long endsAt;
        String by;
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

    /** Point the list at {@code dir} and read what an earlier run kept there; null keeps them in memory. */
    public synchronized void init(@Nullable Path dir) {
        Path target = dir == null ? null : dir.resolve(FILE_NAME);
        file = target;
        runs = target == null ? List.of() : load(target);
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

    /** {@code runs} as the file writes them. Pure. */
    @Nonnull
    static String encode(@Nonnull List<BoostRun> runs) {
        Dto dto = new Dto();
        dto.runs = new ArrayList<>(runs.size());
        for (BoostRun run : runs) {
            Run row = new Run();
            row.boost = run.boost();
            row.percent = run.percent();
            row.startedAt = run.startedAt();
            row.endsAt = run.endsAt();
            row.by = run.by();
            dto.runs.add(row);
        }
        return GSON.toJson(dto);
    }

    /** The starts {@code json} keeps, a row naming no boost left out; empty for none. Pure; throws on bad JSON. */
    @Nonnull
    static List<BoostRun> decode(@Nonnull String json) {
        Dto dto = GSON.fromJson(json, Dto.class);
        List<BoostRun> out = new ArrayList<>();
        if (dto == null || dto.runs == null) {
            return out;
        }
        for (Run row : dto.runs) {
            if (row != null && row.boost != null && !row.boost.isBlank()) {
                out.add(new BoostRun(row.boost, row.percent, row.startedAt, row.endsAt, row.by == null ? "" : row.by));
            }
        }
        return out;
    }

    private void save() {
        Path target = file;
        if (target == null) {
            return;
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            FileUtil.writeStringAtomic(target, encode(runs), true);
        } catch (Throwable t) {
            SafeLog.warn("[" + LOG_TAG + "] the server boosts could not be written to " + target
                    + ", so a restart may lose them: " + t.getMessage());
        }
    }

    @Nonnull
    private static List<BoostRun> load(@Nonnull Path target) {
        long now = System.currentTimeMillis();
        for (Path candidate : List.of(target, target.resolveSibling(target.getFileName() + ".bak"))) {
            if (!Files.exists(candidate)) {
                continue;
            }
            try {
                List<BoostRun> read = new ArrayList<>();
                for (BoostRun run : decode(Files.readString(candidate, StandardCharsets.UTF_8))) {
                    if (run.liveAt(now)) {
                        read.add(run);
                    }
                }
                return List.copyOf(read);
            } catch (Throwable t) {
                SafeLog.warn("[" + LOG_TAG + "] " + candidate + " will not read (" + t.getMessage() + ")");
            }
        }
        return List.of();
    }
}
