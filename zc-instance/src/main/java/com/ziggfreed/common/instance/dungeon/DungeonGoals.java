package com.ziggfreed.common.instance.dungeon;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.world.CopyKey;
import com.ziggfreed.common.ui.toast.ToastDelivery;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastLine;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a player reads as they arrive in a dungeon area while its run is going, so they know what to do there with
 * no store page read: the run's first arrivals, a late joiner, and one who left and came back alike. It is one
 * INFO toast headed with the dungeon's name ({@link DungeonResults#nameOf}), its rows the goal, then the run's
 * rules:
 * <ul>
 *   <li>the goal is the dungeon's own {@code Text.GoalKey} line when it names one, else lines built from
 *       {@code Complete} under the library's {@code goal.*} keys: defeat the boss (every boss, with
 *       {@code AllBosses} over two or more), finish the task (an {@code Objective}), hold out for the
 *       {@code SurviveTimer};</li>
 *   <li>the rules follow whatever the goal's source: the run's {@code TimeLimitSeconds}, and the wipe that fails
 *       it under {@code FailOnWipe}.</li>
 * </ul>
 * A dungeon with nothing to say (no goal line, nothing in {@code Complete}, no rule) shows nothing.
 *
 * <p>{@link DungeonRuns} queues it at the arrival, like a round moment, and it shows {@link #DELAY_MS} later, so
 * it does not land under the loading screen that follows the teleport; by then the player must still stand in
 * that area, in that run, still going, or nothing shows. It ranks as a progress notice
 * ({@link ToastSpec#PRIORITY_PROGRESS}), so it never covers a payout's or an achievement's toast on an open page.
 */
final class DungeonGoals {

    /** How long after the arrival the line shows: past the loading screen the teleport ends on. */
    static final long DELAY_MS = 2_500L;

    private static final String PREFIX = DungeonRefusals.PREFIX;

    private static final String LOG = "[dungeon] ";

    /**
     * What one run asks of its players, read off the dungeon's knobs when the run began.
     *
     * @param dungeonId      the dungeon's id, its name when it names no key
     * @param nameKey        its {@code Text.NameKey}, or null
     * @param goalKey        its {@code Text.GoalKey}, or null for lines built from the rest
     * @param bosses         how many bosses {@code Complete.Bosses} names
     * @param allBosses      {@code Complete.AllBosses}
     * @param objective      whether {@code Complete.Objective} asks for something
     * @param surviveSeconds {@code Complete.SurviveTimer}, 0 for none
     * @param limitSeconds   {@code Run.TimeLimitSeconds}, 0 for none
     * @param failOnWipe     {@code Run.FailOnWipe}
     */
    record Goal(@Nonnull String dungeonId, @Nullable String nameKey, @Nullable String goalKey, int bosses,
                boolean allBosses, boolean objective, long surviveSeconds, long limitSeconds, boolean failOnWipe) {
    }

    private DungeonGoals() {
    }

    /**
     * Show {@code player} the goal of run {@code runId} in {@code area} {@link #DELAY_MS} from now, if they still
     * stand in it then. Never throws.
     */
    static void showSoon(@Nonnull UUID player, @Nonnull CopyKey area, @Nonnull UUID runId, @Nonnull Goal goal) {
        try {
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> showIfStillThere(player, area, runId, goal), DELAY_MS,
                    TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the goal of " + goal.dungeonId() + " could not be queued for " + player, t);
        }
    }

    /**
     * The delayed turn, on the scheduler: show the goal where the player is looking (their own world thread), unless
     * they left the area, went offline, or the run ended or gave way to another meanwhile. AreaMembers is asked
     * before the run list, each lock let go before the next.
     */
    private static void showIfStillThere(@Nonnull UUID player, @Nonnull CopyKey area, @Nonnull UUID runId,
            @Nonnull Goal goal) {
        try {
            if (!area.equals(AreaMembers.shared().presentArea(player)) || !DungeonRuns.shared().live(area, runId)) {
                return;
            }
            Universe universe = Universe.get();
            PlayerRef playerRef = universe == null ? null : universe.getPlayer(player);
            Ref<EntityStore> ref = playerRef == null ? null : playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            ToastSpec spec = spec(goal);
            if (spec != null) {
                ToastDelivery.deliverWhenSettled(playerRef, spec, null);
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the goal of " + goal.dungeonId() + " could not be shown to " + player, t);
        }
    }

    /** The toast for {@code goal}, or null when it has nothing to say. */
    @Nullable
    static ToastSpec spec(@Nonnull Goal goal) {
        List<ToastLine> rows = rows(goal);
        if (rows.isEmpty()) {
            return null;
        }
        return ToastSpec.of(ToastKind.INFO, DungeonResults.nameOf(goal.dungeonId(), goal.nameKey()))
                .withLines(rows)
                .withPriority(ToastSpec.PRIORITY_PROGRESS);
    }

    /** The goal's rows: what to do there (its own line, else built from {@code Complete}), then the run's rules. */
    @Nonnull
    static List<ToastLine> rows(@Nonnull Goal goal) {
        List<ToastLine> rows = new ArrayList<>();
        if (goal.goalKey() != null) {
            rows.add(ToastLine.text(ContentKeys.tr(goal.goalKey())));
        } else {
            if (goal.bosses() > 0) {
                rows.add(line(goal.allBosses() && goal.bosses() > 1 ? "goal.all_bosses" : "goal.boss"));
            }
            if (goal.objective()) {
                rows.add(line("goal.objective"));
            }
            if (goal.surviveSeconds() > 0) {
                rows.add(line("goal.survive", duration(goal.surviveSeconds())));
            }
        }
        if (goal.limitSeconds() > 0) {
            rows.add(line("goal.time_limit", duration(goal.limitSeconds())));
        }
        if (goal.failOnWipe()) {
            rows.add(line("goal.wipe"));
        }
        return rows;
    }

    /**
     * A length of time as a line nests it: whole seconds under a minute, whole minutes, or minutes and seconds,
     * each number a typed param the player's own client writes.
     */
    @Nonnull
    static Message duration(long seconds) {
        long s = Math.max(0L, seconds);
        long minutes = s / 60L;
        long rest = s % 60L;
        if (minutes == 0L) {
            return Msg.key(PREFIX + "goal.time.seconds", rest);
        }
        if (rest == 0L) {
            return Msg.key(PREFIX + "goal.time.minutes", minutes);
        }
        return Msg.key(PREFIX + "goal.time.minutes_seconds", minutes, rest);
    }

    @Nonnull
    private static ToastLine line(@Nonnull String key, @Nonnull Object... args) {
        return ToastLine.text(Msg.key(PREFIX + key, args));
    }
}
