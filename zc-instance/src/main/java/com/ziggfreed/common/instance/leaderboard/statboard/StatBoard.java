package com.ziggfreed.common.instance.leaderboard.statboard;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.leaderboard.Leaderboard;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.Occurrences;
import com.ziggfreed.common.util.SafeLog;

/**
 * The stat board: every player's ranked stats, one bucket per period window, in
 * {@code mods/ziggfreedcommon/stats-board.json}. It is a rank index, never the record: the store that
 * keeps a player's stats writes here as they move (through {@link #add}, {@link #raise}, {@link #lower}
 * and {@link #setAllTime}), re-syncs {@code all_time} from its own record when the player is ready, and sets
 * what it reads live (native readings) then and as the board opens for that player ({@link #refresh}).
 *
 * <p><b>Buckets.</b> {@code all_time}, plus the running window of every period ({@link StatPeriod}):
 * {@code weekly@2026-W42}, {@code monthly@2026-10}, {@code range@<id>} while its range runs, and
 * {@code season@<event>:2026} for each live run a season follows (asked of zc-core's {@code Occurrences}).
 * A new window is the reset; when a week, a month or an event's run turns over, the board keeps that
 * period's newest {@code Keep} windows (of a season, that event's runs) and drops the rest. While
 * {@link StatClock} runs off the server's clock, a write lands in
 * {@code all_time} alone and nothing is dropped. Only a line some section names is written
 * ({@link StatSectionConfig#ranked}), so an entry carries just what Records can show.
 *
 * <p><b>Threading.</b> Each write lands in memory at once and the file follows on a 10-second debounce
 * (compact JSON), from any thread; the library writes it once more at its stop. Nothing is written
 * before {@link #install}, or while the {@link StatBoardSource} says the board is off.
 */
public final class StatBoard {

    /** The board's file name, beside the library's other owner files. */
    public static final String FILE = "stats-board";

    /** How long the file waits after a change, so a busy server writes it a few times a minute at most. */
    public static final long DEBOUNCE_SECONDS = 10L;

    @Nullable private static volatile Leaderboard board;
    @Nonnull private static volatile StatBoardSource source = StatBoardSource.NONE;
    /**
     * Each kind of window's prefix (a week's or a month's period, a season's event) -> the window it last
     * wrote, so a turnover is seen once.
     */
    private static final Map<String, String> lastWindow = new ConcurrentHashMap<>();
    /** Whether a season's failed calendar read has been said, so the log says it once. */
    private static final AtomicBoolean WARNED_SEASON = new AtomicBoolean();

    private StatBoard() {
    }

    /** Open the board in {@code dir} and load what it holds. Called once at setup. */
    public static void install(@Nonnull Path dir) {
        Leaderboard opened = new Leaderboard(FILE, DEBOUNCE_SECONDS, true);
        opened.init(dir);
        use(opened);
    }

    /** Run on {@code opened} (null for none): the install, and a test's own board. */
    public static void use(@Nullable Leaderboard opened) {
        lastWindow.clear();
        board = opened;
    }

    /** The board, or null before it is installed. */
    @Nullable
    public static Leaderboard board() {
        return board;
    }

    /** Fill the seam with the store that keeps the stats (null puts back {@link StatBoardSource#NONE}). */
    public static void setSource(@Nullable StatBoardSource filled) {
        source = filled == null ? StatBoardSource.NONE : filled;
    }

    /** What the board knows of the stats it ranks. */
    @Nonnull
    public static StatBoardSource source() {
        return source;
    }

    /** A counted stat moved: each delta adds to {@code all_time} and every running window. */
    public static void add(@Nonnull UUID uuid, @Nullable String name, @Nullable Map<String, Long> deltas) {
        writeWindows(uuid, name, deltas, Leaderboard::addStats);
    }

    /** A best that grows moved (a Max stat): {@code all_time} and every running window keep the higher. */
    public static void raise(@Nonnull UUID uuid, @Nullable String name, @Nullable Map<String, Long> values) {
        writeWindows(uuid, name, values, Leaderboard::raiseStats);
    }

    /** A best that shrinks moved (a Min stat): {@code all_time} and every running window keep the lower. */
    public static void lower(@Nonnull UUID uuid, @Nullable String name, @Nullable Map<String, Long> values) {
        writeWindows(uuid, name, values, Leaderboard::lowerStats);
    }

    /**
     * Write {@code values} into {@code all_time} outright: a gauge (a level), a native read, or the store's
     * re-sync of a player's lifetime tallies when they are ready. No window is touched.
     */
    public static void setAllTime(@Nonnull UUID uuid, @Nullable String name, @Nullable Map<String, Long> values) {
        Leaderboard target = board;
        if (target == null || !source.enabled()) {
            return;
        }
        Map<String, Long> ranked = StatSectionConfig.getInstance().ranked(values);
        if (!ranked.isEmpty()) {
            target.setStats(StatPeriod.ALL_TIME, uuid, name, ranked);
        }
    }

    /**
     * The board is about to open for the player at {@code ref}: the source sets what it reads live (its
     * native readings) through {@link #setAllTime}, so the player reads their own numbers as they stand.
     * Nothing happens before {@link #install} or while the board is off; a source that throws costs the
     * refresh, never the page. World thread.
     */
    public static void refresh(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        StatBoardSource current = source;
        if (board == null || !current.enabled()) {
            return;
        }
        try {
            current.refresh(store, ref);
        } catch (RuntimeException e) {
            SafeLog.warn("[statboard] the stat source could not refresh a player's live readings before the board"
                    + " opened", e);
        }
    }

    /** True when the board has taken the one-time import {@code claimId}; false before {@link #install}. */
    public static boolean imported(@Nonnull String claimId) {
        Leaderboard target = board;
        return target != null && target.imported(claimId);
    }

    /**
     * Run the one-time import {@code claimId} unless the board has taken it, then claim it in the board's
     * {@code imports}, saved with its rows: true only for the call that ran it ({@link Leaderboard#importOnce}).
     * The writes go through {@link #setAllTime}, absolute values a second run would leave as they were, since a
     * throw claims nothing and the next call runs the import again. Nothing runs before {@link #install} or
     * while the source says the board is off, and nothing is claimed then, so the import waits for a board
     * that takes it.
     */
    public static boolean importOnce(@Nonnull String claimId, @Nonnull Runnable writes) {
        Leaderboard target = board;
        if (target == null || !source.enabled()) {
            return false;
        }
        return target.importOnce(claimId, writes);
    }

    /** Write what the debounce has not yet: the library's stop. Never throws. */
    public static void flushNow() {
        Leaderboard target = board;
        if (target != null) {
            target.flushNow();
        }
    }

    /** One of the board's moves, applied to one bucket. */
    @FunctionalInterface
    private interface Move {
        void apply(@Nonnull Leaderboard board, @Nonnull String bucket, @Nonnull UUID uuid, @Nullable String name,
                   @Nonnull Map<String, Long> values);
    }

    private static void writeWindows(@Nonnull UUID uuid, @Nullable String name, @Nullable Map<String, Long> values,
                                     @Nonnull Move move) {
        Leaderboard target = board;
        if (target == null || !source.enabled()) {
            return;
        }
        Map<String, Long> ranked = StatSectionConfig.getInstance().ranked(values);
        if (ranked.isEmpty()) {
            return;
        }
        // One read of the offset, so a /zigleaderboard clock racing this write cannot mix a shifted check
        // with an unshifted now.
        Duration off = StatClock.offset();
        if (!off.isZero()) {
            // A shifted clock only moves which windows the board reads. A write lands in all_time alone: a
            // future window written now would outlive the trial (retain keeps the newest by text, so after
            // clock 0 or a restart it would drop the real running week), and nothing is pruned or remembered.
            move.apply(target, StatPeriod.ALL_TIME, uuid, name, ranked);
            return;
        }
        Instant now = Instant.now().plus(off);
        // Every running event, asked once a season needs it and at most once a second across every write.
        Map<String, Occurrence> live = null;
        Set<String> buckets = new LinkedHashSet<>();
        buckets.add(StatPeriod.ALL_TIME);
        // Each window's prefix -> how many of its kind to keep, for every window this write turned over.
        Map<String, Integer> turned = new LinkedHashMap<>();
        for (StatPeriod period : StatPeriodConfig.getInstance().periods()) {
            if (live == null && period.kind() == PeriodKind.SEASON_RUN) {
                live = liveAt(now);
            }
            for (StatPeriod.Window window : period.windows(now, live == null ? Map.of() : live)) {
                buckets.add(window.key());
                String prefix = window.prefix();
                if (prefix != null && !window.key().equals(lastWindow.put(prefix, window.key()))) {
                    turned.put(prefix, period.keep());
                }
            }
        }
        for (String bucket : buckets) {
            move.apply(target, bucket, uuid, name, ranked);
        }
        // After the writes, so the running window is one of the Keep newest it keeps.
        for (Map.Entry<String, Integer> window : turned.entrySet()) {
            target.retain(window.getKey(), window.getValue());
        }
    }

    /**
     * Every event running at {@code now} ({@link Occurrences#liveAllCached}); none when the calendar throws,
     * which costs the seasons' ranking for this write and says so once, never the count or the other periods.
     */
    @Nonnull
    private static Map<String, Occurrence> liveAt(@Nonnull Instant now) {
        try {
            return Occurrences.liveAllCached(now.toEpochMilli());
        } catch (RuntimeException e) {
            if (WARNED_SEASON.compareAndSet(false, true)) {
                SafeLog.warn("[statboard] the calendar could not say which runs are live, so the seasons rank"
                        + " nothing for now", e);
            }
            return Map.of();
        }
    }
}
