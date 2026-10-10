package com.ziggfreed.common.instance.leaderboard.statboard;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.leaderboard.Leaderboard;

/**
 * The stat board: every player's ranked stats, one bucket per period window, in
 * {@code mods/ziggfreedcommon/stats-board.json}. It is a rank index, never the record: the store that
 * keeps a player's stats writes here as they move (through {@link #add}, {@link #raise}, {@link #lower}
 * and {@link #setAllTime}), and re-syncs {@code all_time} from its own record when the player is ready.
 *
 * <p><b>Buckets.</b> {@code all_time}, plus the running window of every period ({@link StatPeriod}):
 * {@code weekly@2026-W42}, {@code monthly@2026-10}, {@code range@<id>} while its range runs. A new window
 * is the reset; when a week or a month turns over, the board keeps that period's newest {@code Keep}
 * windows and drops the rest. Only a line some section names is written ({@link StatSectionConfig#ranked}),
 * so an entry carries just what Records can show.
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
    /** Each week's or month's period id -> the window it last wrote, so a turnover is seen once. */
    private static final Map<String, String> lastWindow = new ConcurrentHashMap<>();

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
        Instant now = StatClock.now();
        // While an admin runs the board's clock off the server's, a turnover is a trial: nothing is pruned
        // and no window is remembered as the last one, so a jump ahead never drops the real running week,
        // and setting the clock back to 0 finds no turnover either.
        boolean shifted = !StatClock.offset().isZero();
        Set<String> buckets = new LinkedHashSet<>();
        buckets.add(StatPeriod.ALL_TIME);
        List<StatPeriod> turned = new ArrayList<>();
        for (StatPeriod period : StatPeriodConfig.getInstance().periods()) {
            String key = period.writeKey(now);
            if (key == null) {
                continue;
            }
            buckets.add(key);
            if (!shifted && period.windowPrefix() != null && !key.equals(lastWindow.put(period.id(), key))) {
                turned.add(period);
            }
        }
        for (String bucket : buckets) {
            move.apply(target, bucket, uuid, name, ranked);
        }
        // After the writes, so the running window is one of the Keep newest it keeps.
        for (StatPeriod period : turned) {
            String prefix = period.windowPrefix();
            if (prefix != null) {
                target.retain(prefix, period.keep());
            }
        }
    }
}
