package com.ziggfreed.common.instance.dungeon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.instance.metadata.InstanceRoundCompletedEvent;
import com.ziggfreed.common.instance.metadata.InstanceRoundStartedEvent;
import com.ziggfreed.common.instance.metadata.InstanceRounds;
import com.ziggfreed.common.instance.metadata.RoundMetadata;
import com.ziggfreed.common.instance.result.ResultKind;
import com.ziggfreed.common.instance.world.CopyKey;
import com.ziggfreed.common.util.SafeLog;

/**
 * A party's run through one dungeon area: one run per area of a copy ({@link CopyKey}), over the area's
 * members as {@link AreaMembers} settles them. It leaves as the library's two round moments, so quests,
 * achievements and stats count it with no edge out of this module: {@link InstanceRoundStartedEvent} when it
 * begins and {@link InstanceRoundCompletedEvent} when it ends.
 *
 * <p><b>A run's life.</b>
 * <ul>
 *   <li>It <b>begins</b> at its area's first arrival. The start names that player and everyone already on the
 *       way into the area; whoever arrives later joins it. The dungeon's knobs are read once, here, so a reload
 *       mid-run changes the next run, not this one.</li>
 *   <li>It is <b>cleared</b> when its {@code Complete} holds: a boss its {@code Bosses} names falls, or with
 *       {@code AllBosses} every one of them has. A boss fight's {@link EncounterDefeatedEvent} names its world
 *       and participants but no area, so it counts for the run of the area each participant is in. A dungeon
 *       with no {@code Bosses} has no clear: its runs end only by failing.</li>
 *   <li>It <b>fails</b> when its {@code Run.TimeLimitSeconds} passes, when every member has left (its area
 *       is free: nobody in it or on the way), or, only with {@code Run.FailOnWipe} on, when every member in
 *       the area is dead at once. The wipe is read on the copy's world thread, in a hop the ticker makes for
 *       such a run alone; nothing else here touches a store.</li>
 *   <li>Once ended, a run takes nobody: its members may stay, and the area holds no new run until it frees
 *       and is pasted afresh.</li>
 * </ul>
 *
 * <p><b>The round contract</b> (lead's call): mod id {@value #MOD_ID}, mode the dungeon's {@code Difficulty}
 * label (empty for normal), preset the dungeon id, so a step targets {@code dungeon:} (any difficulty) or
 * {@code dungeon:hard}, qualified by the dungeon. A clear names as winners every member in the area at that
 * moment; a failure names none. Its participants are every player the start names (the first arriver and
 * those already on the way) and every player who arrived later, so each player counted at the start is counted
 * at the end.
 *
 * <p><b>Threads and locks.</b> {@link AreaMembers} calls {@link #arrived}, {@link #left} and {@link #freed}
 * under its own lock and {@link #tick} after it, on its 1 Hz ticker thread; a boss defeat arrives on the copy's
 * world thread. This list's lock is a leaf: nothing is called while it is held, the defeat path asks
 * {@link AreaMembers} before it takes it, and a round moment is never fired under any lock. Every moment is
 * queued and posted to the copy's world thread ({@code world.execute}), the start and the end alike; only a
 * copy no longer taking tasks has its moment fired where the queue is drained, since listeners hop to each
 * player's own world themselves. Nothing here ever waits on a world thread.
 */
public final class DungeonRuns {

    /** The mod id every dungeon round carries. */
    public static final String MOD_ID = "dungeon";

    private static final String LOG = "[dungeon] ";

    private static final DungeonRuns SHARED = new DungeonRuns();

    /** The library's one run list. */
    @Nonnull
    public static DungeonRuns shared() {
        return SHARED;
    }

    /** How a run ended. */
    private enum Ending {
        CLEARED("was cleared"),
        TIME_LIMIT("failed: its time limit passed"),
        ALL_LEFT("failed: every member left"),
        WIPE("failed: every member was dead at once");

        final String words;

        Ending(String words) {
            this.words = words;
        }
    }

    /** One run. Guarded by the list's lock, but for the fields its constructor sets. */
    private static final class Run {
        final CopyKey area;
        final World world;
        final UUID worldId;
        final String dungeon;
        final String mode;
        final long limitMs;
        final boolean failOnWipe;
        /** The bosses that clear it, lower-cased; empty when none does. */
        final Set<String> bosses;
        final boolean allBosses;
        final long startedAtMs;
        /**
         * Every player the start named (the first arriver and those on the way with them) and every player who
         * arrived later, in that order, so each player the start counts is counted at the end too.
         */
        final Set<UUID> participants = new LinkedHashSet<>();
        /** Every player in the area now. */
        final Set<UUID> present = new LinkedHashSet<>();
        /** The bosses of {@link #bosses} that have fallen in the run. */
        final Set<String> fallen = new HashSet<>();
        boolean ended;
        /** A wipe check is queued on the world thread and has not run yet. */
        boolean wipeCheckQueued;
        /** A wipe check has already warned that it failed. */
        boolean wipeCheckWarned;

        Run(CopyKey area, World world, String dungeon, String mode, long limitMs, boolean failOnWipe,
                Set<String> bosses, boolean allBosses, long startedAtMs) {
            this.area = area;
            this.world = world;
            this.worldId = world.getWorldConfig().getUuid();
            this.dungeon = dungeon;
            this.mode = mode;
            this.limitMs = limitMs;
            this.failOnWipe = failOnWipe;
            this.bosses = bosses;
            this.allBosses = allBosses;
            this.startedAtMs = startedAtMs;
        }

        String where() {
            return area.world() + " area " + area.area() + " (" + dungeon + ", "
                    + (mode.isEmpty() ? "normal" : mode) + ")";
        }
    }

    /** A round moment waiting to be posted to its copy's world thread. */
    private record Fire(@Nonnull World world, @Nonnull Runnable task) {
    }

    /** Every run by its area. Guarded by {@code this}. */
    private final Map<CopyKey, Run> runs = new HashMap<>();

    /** The round moments waiting to be posted, drained outside every lock. */
    private final Queue<Fire> outbox = new ConcurrentLinkedQueue<>();

    private DungeonRuns() {
    }

    // ==================== the member settles (under AreaMembers' lock) ====================

    /**
     * {@code player} arrived in {@code area} of {@code world}: they join its run, or begin one, named with
     * {@code onTheWay} (the players already on the way into the same area). Called under {@link AreaMembers}'
     * lock; the start moment is queued, never fired here.
     */
    void arrived(@Nonnull CopyKey area, @Nonnull World world, @Nonnull UUID player,
            @Nonnull Collection<UUID> onTheWay, long nowMs) {
        String dungeonId = CopyRouter.dungeonOf(area.world());
        DungeonAsset dungeon = dungeonId == null ? null : DungeonConfig.getInstance().resolve(dungeonId);
        String line;
        synchronized (this) {
            Run run = runs.get(area);
            if (run != null && run.world != world) {
                // A run still filed under this name belongs to an older copy that closed with it unsettled.
                runs.remove(area);
                if (!run.ended) {
                    end(run, Ending.ALL_LEFT, nowMs, null);
                }
                run = null;
            }
            if (run != null) {
                if (!run.ended) {
                    run.participants.add(player);
                    run.present.add(player);
                }
                return;
            }
            run = begin(area, world, dungeonId, dungeon, nowMs);
            run.participants.add(player);
            run.present.add(player);
            runs.put(area, run);

            List<UUID> named = new ArrayList<>();
            named.add(player);
            for (UUID other : onTheWay) {
                if (!named.contains(other)) {
                    named.add(other);
                }
            }
            run.participants.addAll(named);
            InstanceRoundStartedEvent event = new InstanceRoundStartedEvent(
                    metadata(run, named.size(), 0L, null), named);
            outbox.add(new Fire(world, () -> InstanceRounds.fireStarted(event)));
            line = "a run began in " + run.where() + " with " + player
                    + (named.size() > 1 ? " and " + (named.size() - 1) + " more on the way" : "")
                    + (run.limitMs > 0 ? "; it fails after " + run.limitMs / 1000L + " s" : "; it has no time limit")
                    + (run.bosses.isEmpty() ? "; no boss clears it" : "; it clears when "
                            + (run.allBosses ? "every one of " : "the first of ") + run.bosses + " falls")
                    + (run.failOnWipe ? "; a wipe fails it" : "");
        }
        SafeLog.info(LOG + line);
    }

    /**
     * {@code player} left {@code area}; they stay a participant of its run. Under AreaMembers' lock, which
     * calls {@link #freed} next when nobody is left in the area or on the way.
     */
    synchronized void left(@Nonnull CopyKey area, @Nonnull UUID player) {
        Run run = runs.get(area);
        if (run != null) {
            run.present.remove(player);
        }
    }

    /**
     * {@code area} is free: every member has left it, and nobody is on the way. Its run is forgotten, and one
     * still going fails. Under AreaMembers' lock; the end moment is queued, never fired here.
     */
    synchronized void freed(@Nonnull CopyKey area, long nowMs) {
        Run run = runs.remove(area);
        if (run != null && !run.ended) {
            run.present.clear();
            end(run, Ending.ALL_LEFT, nowMs, null);
        }
    }

    // ==================== the ticker (after AreaMembers' lock) ====================

    /**
     * Once a second, on the members' ticker thread and outside its lock: fail each run past its time limit,
     * queue a wipe check on the world thread for each run that fails on a wipe, and post every queued moment.
     * Never throws.
     */
    void tick(long nowMs) {
        try {
            List<Run> wipeChecks = new ArrayList<>();
            synchronized (this) {
                for (Run run : runs.values()) {
                    if (run.ended) {
                        continue;
                    }
                    if (run.limitMs > 0 && nowMs - run.startedAtMs >= run.limitMs) {
                        end(run, Ending.TIME_LIMIT, nowMs, null);
                        continue;
                    }
                    if (run.failOnWipe && !run.wipeCheckQueued && !run.present.isEmpty() && run.world.isAlive()) {
                        run.wipeCheckQueued = true;
                        wipeChecks.add(run);
                    }
                }
            }
            for (Run run : wipeChecks) {
                try {
                    run.world.execute(() -> checkWipe(run));
                } catch (Throwable t) {
                    // The copy stopped taking tasks: its members are leaving, which ends the run.
                    synchronized (this) {
                        run.wipeCheckQueued = false;
                    }
                }
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the dungeon runs' tick failed", t);
        }
        drain();
    }

    /** On the copy's world thread: is every member in the area dead at once? A wipe fails the run. */
    private void checkWipe(@Nonnull Run run) {
        try {
            List<UUID> members;
            synchronized (this) {
                if (run.ended || runs.get(run.area) != run) {
                    return;
                }
                members = List.copyOf(run.present);
            }
            if (members.isEmpty() || !allDead(run.world, members)) {
                return;
            }
            synchronized (this) {
                if (run.ended || runs.get(run.area) != run) {
                    return;
                }
                end(run, Ending.WIPE, System.currentTimeMillis(), null);
            }
            drain();
        } catch (Throwable t) {
            boolean warn;
            synchronized (this) {
                warn = !run.wipeCheckWarned;
                run.wipeCheckWarned = true;
            }
            if (warn) {
                SafeLog.warn(LOG + "the wipe check of the run in " + run.where() + " failed", t);
            }
        } finally {
            synchronized (this) {
                run.wipeCheckQueued = false;
            }
        }
    }

    /**
     * Is every one of {@code members} dead, standing in {@code world}? Run on that world's thread. A member who
     * is not in its store (offline, mid-teleport) is not counted dead, so a wipe is never read off a stale ref.
     */
    private static boolean allDead(@Nonnull World world, @Nonnull List<UUID> members) {
        Universe universe = Universe.get();
        if (universe == null) {
            return false;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        for (UUID member : members) {
            PlayerRef player = universe.getPlayer(member);
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            if (ref == null || !ref.isValid() || ref.getStore() != store) {
                return false;
            }
            if (store.getComponent(ref, DeathComponent.getComponentType()) == null) {
                return false;
            }
        }
        return true;
    }

    // ==================== a boss falls (the copy's world thread) ====================

    /**
     * The bus listener for a boss defeat: it counts for the run of the area each participant is in. Registered
     * at setup; guarded whole, since a listener the fight's end runs through must never throw.
     */
    public static void onEncounterDefeated(@Nonnull EncounterDefeatedEvent event) {
        try {
            SHARED.defeated(event.encounterId(), event.worldUuid(), event.participants(), System.currentTimeMillis());
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a boss defeat could not be counted for its dungeon run", t);
        }
    }

    /**
     * Boss {@code encounterId} fell in world {@code worldId} with {@code participants}: count it for each of their
     * areas' runs that it clears. AreaMembers is asked first, before this list's lock is taken.
     */
    void defeated(@Nullable String encounterId, @Nullable UUID worldId, @Nonnull List<UUID> participants, long nowMs) {
        if (encounterId == null || encounterId.isBlank() || participants.isEmpty()) {
            return;
        }
        Set<CopyKey> areas = new LinkedHashSet<>();
        for (UUID participant : participants) {
            CopyKey area = AreaMembers.shared().presentArea(participant);
            if (area != null) {
                areas.add(area);
            }
        }
        if (areas.isEmpty()) {
            return;
        }
        String boss = encounterId.trim().toLowerCase(Locale.ROOT);
        synchronized (this) {
            for (CopyKey area : areas) {
                Run run = runs.get(area);
                if (run == null || run.ended || !run.bosses.contains(boss)
                        || (worldId != null && !worldId.equals(run.worldId))) {
                    continue;
                }
                run.fallen.add(boss);
                if (run.allBosses ? run.fallen.containsAll(run.bosses) : !run.fallen.isEmpty()) {
                    end(run, Ending.CLEARED, nowMs, encounterId.trim());
                }
            }
        }
        drain();
    }

    // ==================== internals ====================

    /** A new run of {@code area}, its knobs read off the dungeon now; a dungeon no longer loaded runs bare. */
    @Nonnull
    private static Run begin(@Nonnull CopyKey area, @Nonnull World world, @Nullable String dungeonId,
            @Nullable DungeonAsset dungeon, long nowMs) {
        String preset = dungeon != null && dungeon.getId() != null && !dungeon.getId().isBlank()
                ? dungeon.getId().trim() : dungeonId == null ? area.world() : dungeonId;
        if (dungeon == null) {
            return new Run(area, world, preset, "", 0L, false, Set.of(), false, nowMs);
        }
        Set<String> bosses = new LinkedHashSet<>();
        for (String boss : dungeon.completeBosses()) {
            bosses.add(boss.toLowerCase(Locale.ROOT));
        }
        Integer limit = dungeon.timeLimitSeconds();
        return new Run(area, world, preset, dungeon.difficulty(), limit == null ? 0L : limit * 1000L,
                dungeon.failOnWipe(), Set.copyOf(bosses), dungeon.completeAllBosses(), nowMs);
    }

    /** End {@code run} (under this list's lock): mark it, and queue its end moment. {@code boss} names a clear's. */
    private void end(@Nonnull Run run, @Nonnull Ending ending, long nowMs, @Nullable String boss) {
        run.ended = true;
        boolean cleared = ending == Ending.CLEARED;
        long seconds = Math.max(0L, nowMs - run.startedAtMs) / 1000L;
        List<UUID> participants = List.copyOf(run.participants);
        List<UUID> winners = cleared ? List.copyOf(run.present) : List.of();
        InstanceRoundCompletedEvent event = new InstanceRoundCompletedEvent(
                metadata(run, participants.size(), seconds, cleared ? ResultKind.WIN : ResultKind.LOSS),
                participants, winners);
        outbox.add(new Fire(run.world, () -> InstanceRounds.fireCompleted(event)));
        SafeLog.info(LOG + "the run in " + run.where() + " " + ending.words
                + (boss == null ? "" : " (" + boss + " fell)") + " after " + seconds + " s: "
                + participants.size() + " took part, " + winners.size() + " won");
    }

    /** The round's envelope under the dungeon contract. */
    @Nonnull
    private static RoundMetadata metadata(@Nonnull Run run, int players, long seconds, @Nullable ResultKind result) {
        return RoundMetadata.builder(MOD_ID)
                .modeId(run.mode)
                .presetId(run.dungeon)
                .playerCount(players)
                .durationSeconds(seconds)
                .resultKind(result)
                .build();
    }

    /** Post every queued moment to its copy's world thread. Outside every lock; never throws. */
    private void drain() {
        Fire fire;
        while ((fire = outbox.poll()) != null) {
            post(fire);
        }
    }

    /**
     * Run {@code fire} on its copy's world thread; a copy no longer taking tasks has it run here instead, since
     * a moment's listeners hop to each player's own world themselves.
     */
    private static void post(@Nonnull Fire fire) {
        try {
            if (fire.world().isAlive()) {
                fire.world().execute(fire.task());
                return;
            }
        } catch (Throwable ignored) {
            // The copy stopped taking tasks between the check and the post: run it here.
        }
        try {
            fire.task().run();
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a dungeon run's round moment could not be fired", t);
        }
    }
}
