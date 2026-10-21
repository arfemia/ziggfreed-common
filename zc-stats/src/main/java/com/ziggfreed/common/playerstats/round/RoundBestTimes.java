package com.ziggfreed.common.playerstats.round;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.metadata.InstanceRoundCompletedEvent;
import com.ziggfreed.common.instance.metadata.RoundKeys;
import com.ziggfreed.common.instance.metadata.RoundMetadata;
import com.ziggfreed.common.playerstats.PlayerStats;
import com.ziggfreed.common.playerstats.PlayerThreads;
import com.ziggfreed.common.playerstats.StatBreakdown;
import com.ziggfreed.common.playerstats.asset.StatAsset;
import com.ziggfreed.common.playerstats.asset.StatConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * A won round's length, counted as each winner's best time in a stat that times one mod's rounds ({@link #track}):
 * the library's {@code Dungeon_Best_Time} times every dungeon run's clear, and a minigame times its own rounds by
 * tracking a stat file of its own the same way.
 *
 * <p>The round's length is its {@link RoundMetadata#durationSeconds()}, kept lowest ({@link PlayerStats#lower}), on
 * the line the stat file's own {@code Breakdown} names from the round's target and qualifier ({@link RoundKeys}),
 * the same spelling a round's moments are produced under, so a stat counting the round's moments off a
 * {@code From} list and this best time file a round on one line ({@code dungeon:hard@My_Crypt} for a
 * {@code TargetAtQualifier} stat). A round nobody won, one with no length, or a stat no file defines writes nothing;
 * each winner is counted on their own world thread, and one who is offline is skipped.
 */
public final class RoundBestTimes {

    private static final String LOG = "[stats]";

    /** What one stat times: a mod's won rounds (its id lower-cased), those {@code times} keeps. */
    private record Tracked(@Nonnull String mod, @Nonnull Predicate<RoundMetadata> times) {
    }

    /** Stat id (as written) -> what it times. */
    private static final Map<String, Tracked> TRACKED = new ConcurrentHashMap<>();

    private RoundBestTimes() {
    }

    /**
     * Time every won round of {@code modId} into stat {@code statId} (a Min stat of Format Time), in place of any mod
     * it timed before. Callable at setup from whoever ships the stat file.
     */
    public static void track(@Nonnull String statId, @Nonnull String modId) {
        track(statId, modId, metadata -> true);
    }

    /**
     * {@link #track(String, String)}, timing only the won rounds {@code times} keeps: the library's dungeon times
     * leave out an endless run, which nobody wins fastest.
     */
    public static void track(@Nonnull String statId, @Nonnull String modId, @Nonnull Predicate<RoundMetadata> times) {
        TRACKED.put(statId, new Tracked(modId.trim().toLowerCase(Locale.ROOT), times));
    }

    /** The bus entry point, guarded whole: a listener that throws must never take the round's end down with it. */
    public static void onCompleted(@Nonnull InstanceRoundCompletedEvent event) {
        try {
            RoundMetadata metadata = event.metadata();
            long seconds = metadata.durationSeconds();
            if (!event.isWin() || seconds <= 0L || TRACKED.isEmpty()) {
                return;
            }
            String mod = metadata.modId().trim().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Tracked> tracked : TRACKED.entrySet()) {
                if (tracked.getValue().mod().equals(mod) && tracked.getValue().times().test(metadata)) {
                    count(tracked.getKey(), metadata, seconds, event.winners());
                }
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + " a round's best time could not be counted", t);
        }
    }

    /** {@code seconds} as each of {@code winners}' best in {@code statId}, on the line its file's Breakdown names. */
    private static void count(@Nonnull String statId, @Nonnull RoundMetadata metadata, long seconds,
            @Nonnull List<UUID> winners) {
        if (StatConfig.getInstance().index().stat(statId) == null) {
            return;
        }
        String line = lineOf(statId, metadata);
        List<String> stats = List.of(statId);
        for (UUID winner : winners) {
            PlayerThreads.run(winner, LOG, "round", line == null ? statId : line, stats,
                    (store, ref) -> PlayerStats.lower(store, ref, statId, line, seconds));
        }
    }

    /**
     * The line stat {@code statId} files the round under: its {@code Breakdown} read off the round's target and
     * qualifier, exactly as a moment's would be; null for the total only, or a stat no file defines.
     */
    @Nullable
    static String lineOf(@Nonnull String statId, @Nonnull RoundMetadata metadata) {
        StatAsset stat = StatConfig.getInstance().index().stat(statId);
        StatBreakdown breakdown = stat == null ? null : stat.breakdown();
        return breakdown == null ? null : breakdown.thing(RoundKeys.target(metadata), RoundKeys.qualifier(metadata));
    }
}
