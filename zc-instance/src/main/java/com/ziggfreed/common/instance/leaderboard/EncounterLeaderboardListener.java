package com.ziggfreed.common.instance.leaderboard;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.util.SafeLog;

/**
 * Writes a boss defeat onto a leaderboard: one row per participant, into the bucket the fight's
 * binding row names, the moment {@code EncounterDefeatedEvent} lands.
 *
 * <p>The library's one edge from the instance layer to the encounter framework, and it only
 * listens: it never reaches into the fight, and a binding row whose {@code Leaderboard} group names
 * no bucket writes nothing. The bucket is the row's {@code Bucket}, suffixed with the party size
 * when {@code ByPartySize} is set and with the run's difficulty when {@code ByDifficulty} is, joined
 * with {@code :} the way an instance round names its mode, so a fight fought by four on hard lands
 * in {@code <bucket>:4:hard}. A participant's SCORE is their credited share in whole percent points
 * (the top contributor reads 100), so best score is "who carried the most" and total points accrue
 * with every fight; the TIME is the fight's length, which the board keeps the lower of on a win,
 * so the fastest clear is free; and the stat bag carries the raw damage dealt and taken under the
 * two keys below.
 *
 * <p>The board is this listener's own ({@value #BOARD}), kept beside the library's other data
 * files, because no consumer board exists on a server running the library alone; a consumer that
 * wants the rows on a board of its own reads them back through {@link #board()}.
 */
public final class EncounterLeaderboardListener {

    /** The board file's base name. */
    public static final String BOARD = "encounter-leaderboard";

    /** The stat key raw damage dealt to the subject accrues under. */
    public static final String STAT_DAMAGE_DEALT = "damage_dealt";

    /** The stat key raw damage taken while a member accrues under. */
    public static final String STAT_DAMAGE_TAKEN = "damage_taken";

    /** What joins the bucket to its party-size and difficulty suffixes. */
    static final char BUCKET_SEPARATOR = ':';

    /** The difficulty suffix for a run that carried no label. */
    static final String NO_DIFFICULTY = "any";

    private static final AtomicReference<Leaderboard> INSTALLED = new AtomicReference<>();

    private EncounterLeaderboardListener() {
    }

    /**
     * Open the board under {@code dataDir} and listen for defeats on the shared bus. Registration
     * only, from the instance bootstrap; every decision stays in {@link #record}.
     */
    public static void install(@Nonnull PluginBase plugin, @Nullable Path dataDir) {
        Leaderboard board = new Leaderboard(BOARD);
        board.init(dataDir);
        INSTALLED.set(board);
        plugin.getEventRegistry().registerGlobal(EncounterDefeatedEvent.class, EncounterLeaderboardListener::onDefeated);
    }

    /** The board the defeats are written to, or null before install. */
    @Nullable
    public static Leaderboard board() {
        return INSTALLED.get();
    }

    /** Guarded whole: a listener that throws must never take the defeat down with it. */
    static void onDefeated(@Nonnull EncounterDefeatedEvent event) {
        try {
            Leaderboard board = INSTALLED.get();
            if (board == null) {
                return;
            }
            EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(event.encounterId());
            int rows = record(board, event, row == null ? null : row.getLeaderboard());
            if (rows > 0) {
                SafeLog.info("[encounter] leaderboard run=" + EncounterRun.shortId(event.runId()) + " encounter="
                        + event.encounterId() + ": " + rows + " row(s) into '"
                        + bucketFor(row.getLeaderboard(), event.participants().size(), event.difficulty()) + "'");
            }
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the leaderboard could not record a defeat", t);
        }
    }

    /**
     * One row per participant into the group's bucket.
     *
     * @return how many rows were written; 0 when the group is absent or names no bucket
     */
    static int record(@Nonnull Leaderboard board, @Nonnull EncounterDefeatedEvent event,
            @Nullable EncounterBindingAsset.Leaderboard group) {
        String bucket = bucketFor(group, event.participants().size(), event.difficulty());
        if (bucket == null) {
            return 0;
        }
        int time = (int) Math.round(event.elapsedSeconds());
        int rows = 0;
        for (ParticipantShare share : event.participantShares()) {
            Map<String, Long> deltas = new LinkedHashMap<>();
            deltas.put(STAT_DAMAGE_DEALT, Math.round(share.damageDealt()));
            deltas.put(STAT_DAMAGE_TAKEN, Math.round(share.damageTaken()));
            board.record(bucket, share.playerId(), share.playerName(), scoreOf(share), time, true, deltas);
            rows++;
        }
        return rows;
    }

    /** The bucket a defeat writes into, or null when the group is absent or names none. */
    @Nullable
    static String bucketFor(@Nullable EncounterBindingAsset.Leaderboard group, int partySize,
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

    /** A participant's score: their share in whole percent points of the top contributor's. */
    static int scoreOf(@Nonnull ParticipantShare share) {
        return (int) Math.round(Math.max(0.0, Math.min(1.0, share.share())) * 100.0);
    }
}
