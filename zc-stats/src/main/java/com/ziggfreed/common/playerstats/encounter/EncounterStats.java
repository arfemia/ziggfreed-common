package com.ziggfreed.common.playerstats.encounter;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.event.EncounterWipedEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardListener;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.PlayerThreads;
import com.ziggfreed.common.playerstats.ZcStats;
import com.ziggfreed.common.util.SafeLog;

/**
 * A boss fight's end, counted into every participant's encounter stats the moment its event lands, each on
 * the line of the fight's bucket string ({@link #fightLine}, through {@link EncounterLeaderboardListener#bucketFor}),
 * the key the old encounter board's rows were filed under, so an imported row and a new win land on the same
 * line.
 *
 * <p><b>A defeat</b> ({@link #onDefeated}) counts seven stats ({@link #DEFEAT_STATS}): {@code Encounters_Won} (one
 * more win), {@code Encounter_Best_Time} (the fight's length, kept lowest), {@code Encounter_Best_Share} (their
 * share of the credit in whole percent, kept highest), {@code Encounter_Damage_Dealt} (the raw damage they dealt the
 * boss), {@code Encounter_Deathless_Wins} (one more, when they never died in the fight),
 * {@code Encounter_Killing_Blows} (one more, when they are the event's {@code lastHitter}) and
 * {@code Encounter_First_Win} (the win's time in seconds since the epoch, kept lowest, so it holds their first). A
 * fight the event files under a zc run (a dungeon area's) lands on that bucket filed under the run's source
 * ({@link EncounterLeaderboardListener#fightInRun}); Records folds a boss's lines back together as it reads them.
 *
 * <p><b>A wipe</b> ({@link #onWiped}) counts {@code Encounter_Wipes} once. Its event carries no run tag and no
 * difficulty, so its line is the fight's own (never filed under a run), keyed with the row's
 * {@code Progression.Difficulty}.
 *
 * <p>The first win's time is the server's own clock, never the stat board's trial clock
 * ({@code /zigleaderboard clock}): it is kept in the player's record, which outlives a trial, and the board reads it
 * against its own clock as it paints, so a trial still reads it as that many days ago.
 *
 * <p>Every participant the ledger saw is counted, credited or not, as the encounter board counted them. A
 * binding row naming no {@code Leaderboard.Bucket} keeps no records, and a participant who is no longer
 * online is skipped: their record is written on their own world thread, never in their absence.
 */
public final class EncounterStats {

    /** The stats a defeat counts for each participant, in the order the log names them. */
    static final List<String> DEFEAT_STATS = List.of(ZcStats.ENCOUNTERS_WON, ZcStats.ENCOUNTER_BEST_TIME,
            ZcStats.ENCOUNTER_BEST_SHARE, ZcStats.ENCOUNTER_DAMAGE_DEALT, ZcStats.ENCOUNTER_DEATHLESS_WINS,
            ZcStats.ENCOUNTER_KILLING_BLOWS, ZcStats.ENCOUNTER_FIRST_WIN);

    /** The stats a wipe counts for each participant. */
    static final List<String> WIPE_STATS = List.of(ZcStats.ENCOUNTER_WIPES);

    private static final String LOG = "[encounter]";

    private EncounterStats() {
    }

    /** Guarded whole: a listener that throws must never take the defeat down with it. */
    public static void onDefeated(@Nonnull EncounterDefeatedEvent event) {
        try {
            String bucket = fightLine(rowOf(event.encounterId()), event.participants().size(), event.difficulty());
            if (bucket == null) {
                return;
            }
            // A fight inside a dungeon area is filed under its run's dungeon.
            String fight = EncounterLeaderboardListener.fightInRun(bucket,
                    event.runTag() == null ? null : event.runTag().source());
            long seconds = Math.round(event.elapsedSeconds());
            long wonAt = System.currentTimeMillis() / 1000L;
            UUID lastHitter = event.lastHitter();
            int counted = 0;
            for (ParticipantShare share : event.participantShares()) {
                if (onPlayerThread(share.playerId(), fight, DEFEAT_STATS,
                        (store, ref) -> count(store, ref, fight, seconds, share, lastHitter, wonAt))) {
                    counted++;
                }
            }
            SafeLog.info("[encounter] stats run=" + EncounterRun.shortId(event.runId()) + " encounter="
                    + event.encounterId() + ": " + counted + " of " + event.participantShares().size()
                    + " participant(s) counted under '" + fight + "'");
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the stat store could not count a defeat", t);
        }
    }

    /** Guarded whole: a listener that throws must never take the wipe down with it. */
    public static void onWiped(@Nonnull EncounterWipedEvent event) {
        try {
            EncounterBindingAsset row = rowOf(event.encounterId());
            EncounterBindingAsset.Progression progression = row == null ? null : row.getProgression();
            String fight = fightLine(row, event.participants().size(),
                    progression == null ? null : progression.getDifficulty());
            if (fight == null) {
                return;
            }
            int counted = 0;
            for (ParticipantShare share : event.participantShares()) {
                if (onPlayerThread(share.playerId(), fight, WIPE_STATS,
                        (store, ref) -> PlayerStats.add(store, ref, ZcStats.ENCOUNTER_WIPES, fight, 1L))) {
                    counted++;
                }
            }
            SafeLog.info("[encounter] stats run=" + EncounterRun.shortId(event.runId()) + " encounter="
                    + event.encounterId() + ": a wipe, " + counted + " of " + event.participantShares().size()
                    + " participant(s) counted under '" + fight + "'");
        } catch (Throwable t) {
            SafeLog.warn("[encounter] the stat store could not count a wipe", t);
        }
    }

    /**
     * The line a fight's stats are counted on: the bucket string {@code row}'s {@code Leaderboard} group composes
     * for {@code partySize} and {@code difficulty} ({@link EncounterLeaderboardListener#bucketFor}), or null when the
     * fight has no row or its row keeps no records. The one keying both a defeat and a wipe use.
     */
    @Nullable
    static String fightLine(@Nullable EncounterBindingAsset row, int partySize, @Nullable String difficulty) {
        return EncounterLeaderboardListener.bucketFor(row == null ? null : row.getLeaderboard(), partySize, difficulty);
    }

    /** The binding row of the fight {@code encounterId} names, or null for none. */
    @Nullable
    private static EncounterBindingAsset rowOf(@Nonnull String encounterId) {
        return EncounterBindingConfig.getInstance().forEncounter(encounterId);
    }

    /**
     * One participant's seven defeat stats ({@link #DEFEAT_STATS}), on the line of {@code fight}. Called on the
     * player's world thread.
     *
     * @param lastHitter who landed the boss's last hit, or null when nobody player-shaped did
     * @param wonAt      the win's time, in seconds since the epoch on the server's clock
     */
    static void count(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String fight,
            long seconds, @Nonnull ParticipantShare share, @Nullable UUID lastHitter, long wonAt) {
        PlayerStats.add(store, ref, ZcStats.ENCOUNTERS_WON, fight, 1L);
        PlayerStats.lower(store, ref, ZcStats.ENCOUNTER_BEST_TIME, fight, seconds);
        PlayerStats.raise(store, ref, ZcStats.ENCOUNTER_BEST_SHARE, fight, sharePercent(share.share()));
        PlayerStats.add(store, ref, ZcStats.ENCOUNTER_DAMAGE_DEALT, fight, Math.round(share.damageDealt()));
        if (!share.died()) {
            PlayerStats.add(store, ref, ZcStats.ENCOUNTER_DEATHLESS_WINS, fight, 1L);
        }
        if (share.playerId().equals(lastHitter)) {
            PlayerStats.add(store, ref, ZcStats.ENCOUNTER_KILLING_BLOWS, fight, 1L);
        }
        PlayerStats.lower(store, ref, ZcStats.ENCOUNTER_FIRST_WIN, fight, wonAt);
    }

    /** A share of the credit, 0 to 1 of the top contributor's, in whole percent points (the top reads 100). */
    static long sharePercent(double share) {
        return Math.round(Math.max(0.0, Math.min(1.0, share)) * 100.0);
    }

    /**
     * Run {@code task} for {@code playerId} on their own world thread ({@link PlayerThreads#run}), the log naming
     * {@code stats} and {@code fight} when they changed world first; false when they are offline.
     */
    private static boolean onPlayerThread(@Nonnull UUID playerId, @Nonnull String fight, @Nonnull List<String> stats,
            @Nonnull PlayerThreads.Task task) {
        return PlayerThreads.run(playerId, LOG, "fight", fight, stats, task);
    }
}
