package com.ziggfreed.common.instance.leaderboard;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;

/**
 * The encounter board a boss defeat was written onto before its records moved to the stat board, and the
 * bucket string a fight's records are keyed by ({@link #bucketFor}).
 *
 * <p><b>The board is frozen.</b> Nothing writes it any more: the stat store counts a defeat into its own
 * encounter stats, keyed by the same bucket string, and imports this board's rows once into the stat board
 * and into each player's record. The file ({@value #BOARD}, beside the library's owner files in
 * {@code mods/ziggfreedcommon/}) is still opened and kept, so the import can read it and an older build
 * rolled back to still finds its rows. Before 2.2.0 the file sat in the library's data folder, and the
 * first load that finds it only there moves it across ({@link Leaderboard#init(Path, Path)}).
 *
 * <p><b>The bucket.</b> A binding row's {@code Leaderboard.Bucket}, suffixed with the party size when
 * {@code ByPartySize} is set and with the run's difficulty when {@code ByDifficulty} is, joined with
 * {@code :} the way an instance round names its mode, so a fight fought by four on hard is
 * {@code <bucket>:4:hard}. A row naming no bucket keeps no records. A fight fought inside a zc run (a dungeon
 * area's; zc-encounter's run tag) is filed under that run's source as well, {@code <bucket>@dungeon:<id>}
 * ({@link #fightInRun}), so Boss Fights shows it under its dungeon.
 */
public final class EncounterLeaderboardListener {

    /** The board file's base name. */
    public static final String BOARD = "encounter-leaderboard";

    /** The stat key raw damage dealt to the subject accrued under on this board. */
    public static final String STAT_DAMAGE_DEALT = "damage_dealt";

    /** What joins the bucket to its party-size and difficulty suffixes. */
    static final char BUCKET_SEPARATOR = ':';

    /** What joins a fight's key to the source of the run it was fought in. */
    static final char RUN_SEPARATOR = '@';

    /** The difficulty suffix for a run that carried no label. */
    static final String NO_DIFFICULTY = "any";

    private static final AtomicReference<Leaderboard> INSTALLED = new AtomicReference<>();

    private EncounterLeaderboardListener() {
    }

    /**
     * Open the board under {@code dataDir}, moving its file from {@code oldDir}, the folder it sat in before,
     * on the first load that finds it only there. Called once at setup, from the instance bootstrap.
     */
    public static void open(@Nullable Path dataDir, @Nullable Path oldDir) {
        Leaderboard board = new Leaderboard(BOARD);
        board.init(dataDir, oldDir);
        INSTALLED.set(board);
    }

    /** The frozen board, or null before {@link #open}. */
    @Nullable
    public static Leaderboard board() {
        return INSTALLED.get();
    }

    /**
     * Write whatever the board still holds unwritten: the library's stop, through
     * {@code InstanceBootstrap.shutdown}. Nothing before {@link #open}; never throws.
     */
    public static void flushNow() {
        Leaderboard board = INSTALLED.get();
        if (board != null) {
            board.flushNow();
        }
    }

    /** Put {@code board} in the frozen board's place, or forget it with null: a test's own board. */
    public static void installForTests(@Nullable Leaderboard board) {
        INSTALLED.set(board);
    }

    /** The bucket string a defeat's records are keyed by, or null when the group is absent or names none. */
    @Nullable
    public static String bucketFor(@Nullable EncounterBindingAsset.Leaderboard group, int partySize,
            @Nullable String difficulty) {
        String bucket = group == null ? null : group.getBucket();
        if (bucket == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(bucket.trim());
        if (group.byPartySize()) {
            out.append(BUCKET_SEPARATOR).append(partySize);
        }
        if (group.byDifficulty()) {
            String label = difficulty == null || difficulty.isBlank() ? NO_DIFFICULTY
                    : difficulty.trim().toLowerCase(Locale.ROOT);
            out.append(BUCKET_SEPARATOR).append(label);
        }
        return out.toString();
    }

    /**
     * {@code fight}'s key filed under the run it was fought in: {@code <fight>@<runSource>}, or {@code fight} alone
     * when {@code runSource} is null or blank (a fight in no run). A source carries no {@code @} or {@code |}.
     */
    @Nonnull
    public static String fightInRun(@Nonnull String fight, @Nullable String runSource) {
        return runSource == null || runSource.isBlank() ? fight : fight + RUN_SEPARATOR + runSource.trim();
    }

    /**
     * The run source a recorded fight key is filed under, or null for a fight in no run. A source reads
     * {@code <site>:<id>}, so text after an {@code @} that holds no {@code :} is part of the fight's own key.
     */
    @Nullable
    public static String runOf(@Nonnull String key) {
        int at = key.lastIndexOf(RUN_SEPARATOR);
        if (at <= 0 || at == key.length() - 1) {
            return null;
        }
        String source = key.substring(at + 1);
        return source.indexOf(BUCKET_SEPARATOR) > 0 ? source : null;
    }

    /** A recorded fight key without the run it is filed under ({@link #runOf}): the key {@link #bucketFor} built. */
    @Nonnull
    public static String fightOf(@Nonnull String key) {
        String run = runOf(key);
        return run == null ? key : key.substring(0, key.length() - run.length() - 1);
    }
}
