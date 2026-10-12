package com.ziggfreed.common.playerstats.encounter;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.ledger.ParticipantShare;
import com.ziggfreed.common.encounter.run.EncounterRun;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardListener;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.ZcStats;
import com.ziggfreed.common.util.SafeLog;

/**
 * A boss defeat, counted into every participant's encounter stats the moment {@code EncounterDefeatedEvent}
 * lands: {@code Encounters_Won} (one more win), {@code Encounter_Best_Time} (the fight's length, kept lowest),
 * {@code Encounter_Best_Share} (their share of the credit in whole percent, kept highest) and
 * {@code Encounter_Damage_Dealt} (the raw damage they dealt the boss), each on the line of the fight's bucket
 * string ({@link EncounterLeaderboardListener#bucketFor}), the key the old encounter board's rows were filed
 * under, so an imported row and a new win land on the same line. A fight the event files under a zc run (a
 * dungeon area's) lands on that bucket filed under the run's source
 * ({@link EncounterLeaderboardListener#fightInRun}), so Boss Fights shows it under its dungeon.
 *
 * <p>Every participant the ledger saw is counted, credited or not, as the encounter board counted them. A
 * binding row naming no {@code Leaderboard.Bucket} keeps no records, and a participant who is no longer
 * online is skipped: their record is written on their own world thread, never in their absence.
 */
public final class EncounterStats {

    private EncounterStats() {
    }

    /** Guarded whole: a listener that throws must never take the defeat down with it. */
    public static void onDefeated(@Nonnull EncounterDefeatedEvent event) {
        try {
            EncounterBindingAsset row = EncounterBindingConfig.getInstance().forEncounter(event.encounterId());
            String bucket = EncounterLeaderboardListener.bucketFor(row == null ? null : row.getLeaderboard(),
                    event.participants().size(), event.difficulty());
            if (bucket == null) {
                return;
            }
            // A fight inside a dungeon area is filed under its run's dungeon, which Boss Fights then names.
            String fight = EncounterLeaderboardListener.fightInRun(bucket,
                    event.runTag() == null ? null : event.runTag().source());
            long seconds = Math.round(event.elapsedSeconds());
            int counted = 0;
            for (ParticipantShare share : event.participantShares()) {
                if (onPlayerThread(share.playerId(), fight, (store, ref) -> count(store, ref, fight, seconds, share))) {
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

    /** One participant's four stats, on the line of {@code fight}. Called on the player's world thread. */
    static void count(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String fight,
            long seconds, @Nonnull ParticipantShare share) {
        PlayerStats.add(store, ref, ZcStats.ENCOUNTERS_WON, fight, 1L);
        PlayerStats.lower(store, ref, ZcStats.ENCOUNTER_BEST_TIME, fight, seconds);
        PlayerStats.raise(store, ref, ZcStats.ENCOUNTER_BEST_SHARE, fight, sharePercent(share.share()));
        PlayerStats.add(store, ref, ZcStats.ENCOUNTER_DAMAGE_DEALT, fight, Math.round(share.damageDealt()));
    }

    /** A share of the credit, 0 to 1 of the top contributor's, in whole percent points (the top reads 100). */
    static long sharePercent(double share) {
        return Math.round(Math.max(0.0, Math.min(1.0, share)) * 100.0);
    }

    /** What runs on a player's world thread, handed their live store and entity. */
    @FunctionalInterface
    interface PlayerTask {

        void run(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref);
    }

    /**
     * Run {@code task} for {@code playerId} on the world thread they are on now: inline when that is the
     * thread running, else queued there and the player found again when it runs, since a tick later a held
     * entity reference may name whoever took its slot. False when they are offline or their world is gone. A
     * player who changed world before the queued task runs is skipped with one INFO line naming the stats and
     * {@code fight}, never the player; a queued count that throws is one WARN line naming {@code fight}, never
     * the player either.
     */
    private static boolean onPlayerThread(@Nonnull UUID playerId, @Nonnull String fight, @Nonnull PlayerTask task) {
        Ref<EntityStore> ref = liveRef(playerId);
        World world = ref == null ? null : ref.getStore().getExternalData().getWorld();
        if (world == null || !world.isAlive()) {
            return false;
        }
        if (world.isInThread()) {
            task.run(ref.getStore(), ref);
            return true;
        }
        world.execute(() -> {
            try {
                Ref<EntityStore> now = liveRef(playerId);
                if (now != null && now.getStore().isInThread()) {
                    task.run(now.getStore(), now);
                } else if (now != null) {
                    SafeLog.info("[encounter] a participant changed world before their defeat was counted, so "
                            + ZcStats.ENCOUNTERS_WON + ", " + ZcStats.ENCOUNTER_BEST_TIME + ", "
                            + ZcStats.ENCOUNTER_BEST_SHARE + " and " + ZcStats.ENCOUNTER_DAMAGE_DEALT + " under '"
                            + fight + "' were skipped for them");
                }
            } catch (Throwable t) {
                SafeLog.warn("[encounter] the stat store could not count a participant's defeat under '" + fight + "'", t);
            }
        });
        return true;
    }

    /** The player's live entity, or null when they are offline or no longer in a store. */
    @Nullable
    private static Ref<EntityStore> liveRef(@Nonnull UUID playerId) {
        Universe universe = Universe.get();
        PlayerRef player = universe == null ? null : universe.getPlayer(playerId);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        return ref != null && ref.isValid() ? ref : null;
    }
}
