package com.ziggfreed.common.instance.dungeon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.seam.RunTag;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.metadata.InstanceRoundCompletedEvent;
import com.ziggfreed.common.instance.metadata.InstanceRoundStartedEvent;
import com.ziggfreed.common.instance.metadata.InstanceRounds;
import com.ziggfreed.common.instance.metadata.RoundMetadata;
import com.ziggfreed.common.instance.result.ResultKind;
import com.ziggfreed.common.instance.world.CopyKey;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
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
 *       way into the area; whoever arrives later joins it, named by a start of their own. The dungeon's knobs
 *       are read once, here, so a reload mid-run changes the next run, not this one.</li>
 *   <li>It is <b>cleared</b> when every leaf its {@code Complete} writes holds, the moment the last of them does:
 *       a boss its {@code Bosses} names has fallen (with {@code AllBosses} every one of them), its
 *       {@code Objective} has been met as many times as it asks, and its {@code SurviveTimer} has run out since
 *       the start. A boss fight's {@link EncounterDefeatedEvent} names its world and participants but no area, so
 *       it counts for the run of the area each participant is in; an objective's moments arrive from
 *       zc-objectives, which matches the moment stream against {@link #objectiveOf} and counts through
 *       {@link #countObjective}, since this module cannot see that stream; the survive timer is read on the
 *       ticker. A dungeon whose {@code Complete} writes none of them has no clear: its runs end only by
 *       failing.</li>
 *   <li>It <b>fails</b> when its {@code Run.TimeLimitSeconds} passes, when every member has left (its area
 *       is free: nobody in it or on the way), or, only with {@code Run.FailOnWipe} on, when every member in
 *       the area is dead at once. The wipe is read on the copy's world thread, in a hop the ticker makes for
 *       such a run alone; nothing else here touches a store.</li>
 *   <li>Once ended, a run takes nobody: its members may stay, and the area holds no new run until it frees
 *       and is pasted afresh.</li>
 * </ul>
 *
 * <p><b>What its members see.</b> Each player coming into the run while it is going (its first arrivals, a late
 * joiner, one back after leaving) is told what to do there a moment after they arrive ({@link DungeonGoals}). At a
 * clear every member in the area earns the dungeon's completion Claim and sees the results screen
 * ({@link DungeonResults}); at a failure its party could have prevented (the time limit, a wipe) every member in
 * the area is told why. A failure because everyone left tells nobody.
 *
 * <p><b>The round contract</b> (lead's call): mod id {@value #MOD_ID}, mode the dungeon's {@code Difficulty}
 * label (empty for normal), preset the dungeon id, so a step targets {@code dungeon:} (any difficulty) or
 * {@code dungeon:hard}, qualified by the dungeon. A clear names as winners every member in the area at that
 * moment; a failure names none. The first start names the first arriver and those already on the way; a player
 * who arrives later, not yet counted, gets a start of their own naming them alone, its player count the run's as
 * it stands. Its participants at the end are every player a start named, so each player counted at a start is
 * counted at the end and each player counted at the end was counted at a start, once however often they leave
 * and come back.
 *
 * <p><b>The run tag.</b> A boss fight inside an area belongs to the area's run (R105): zc-encounter asks
 * {@link #runOf} through its run-tag seam at the defeat, which files the fight's records under
 * {@code dungeon:<id>} and lets a payout name the run.
 *
 * <p><b>Threads and locks.</b> {@link AreaMembers} calls {@link #arrived}, {@link #left} and {@link #freed}
 * under its own lock and {@link #tick} after it, on its 1 Hz ticker thread; a boss defeat, the run-tag question
 * and an objective's moment arrive on the copy's world thread. This list's lock is a leaf: nothing is called
 * while it is held, every path that needs {@link AreaMembers} asks it before taking this lock, and a round
 * moment is never fired under any lock. Every moment, an arrival's goal line, a clear's payout and a failure's
 * line are queued and posted to the copy's world thread ({@code world.execute}); only a copy no longer taking
 * tasks has its queue run where it is drained, since everything queued hops to each player's own world itself.
 * Nothing here ever waits on a world thread.
 */
public final class DungeonRuns {

    /** The mod id every dungeon round carries. */
    public static final String MOD_ID = "dungeon";

    /** What a dungeon run's tag, its completion Claim's source and its returns key start with. */
    public static final String SOURCE_PREFIX = "dungeon:";

    private static final String LOG = "[dungeon] ";

    private static final DungeonRuns SHARED = new DungeonRuns();

    /** The library's one run list. */
    @Nonnull
    public static DungeonRuns shared() {
        return SHARED;
    }

    /** How a run ended, and the line a failure tells the members still in the area (null: none). */
    private enum Ending {
        CLEARED("was cleared", null),
        TIME_LIMIT("failed: its time limit passed", "run.failed.time_limit"),
        ALL_LEFT("failed: every member left", null),
        WIPE("failed: every member was dead at once", "run.failed.wipe");

        final String words;
        @Nullable final String lineKey;

        Ending(String words, @Nullable String lineKey) {
            this.words = words;
            this.lineKey = lineKey;
        }
    }

    /** A dungeon's knobs, read once when a run begins. */
    private record Knobs(@Nonnull String mode, @Nullable String nameKey, @Nullable String goalKey, long limitMs,
                         boolean failOnWipe, @Nonnull Set<String> bosses, boolean allBosses,
                         @Nullable DungeonObjective objective, long surviveMs, @Nullable String lootable,
                         @Nullable DiminishSpec diminish) {

        /** A dungeon no longer loaded: no limit, no clear, no reward, nothing to tell an arrival. */
        static final Knobs BARE = new Knobs("", null, null, 0L, false, Set.of(), false, null, 0L, null, null);

        /** Whether anything can clear the run. */
        boolean canClear() {
            return !bosses.isEmpty() || objective != null || surviveMs > 0;
        }
    }

    /** One run. Guarded by the list's lock, but for the fields its constructor sets. */
    private static final class Run {
        final CopyKey area;
        final World world;
        final UUID worldId;
        /** Which run this is, unique across restarts: its tag and its Claims name it. */
        final UUID runId = UUID.randomUUID();
        final String dungeon;
        final Knobs knobs;
        final long startedAtMs;
        /**
         * Every player the first start named (the first arriver and those on the way with them) and every player
         * who arrived later, each named by a start of their own, in that order: every player a start counts is
         * counted at the end too, and every player the end counts was counted at a start, once.
         */
        final Set<UUID> participants = new LinkedHashSet<>();
        /** Every player in the area now. */
        final Set<UUID> present = new LinkedHashSet<>();
        /** The bosses of {@code knobs.bosses} that have fallen in the run, lower-cased. */
        final Set<String> fallen = new HashSet<>();
        /** How many times the run's objective has been met, by every member together. */
        long objectiveCount;
        /** How many boss fights were won in the area during the run, whether or not {@code Complete} names them. */
        int bossesDefeated;
        /** The damage each member dealt the bosses that fell in the area during the run. */
        final Map<UUID, Double> damage = new HashMap<>();
        boolean ended;
        /** A wipe check is queued on the world thread and has not run yet. */
        boolean wipeCheckQueued;
        /** A wipe check has already warned that it failed. */
        boolean wipeCheckWarned;

        Run(CopyKey area, World world, String dungeon, Knobs knobs, long startedAtMs) {
            this.area = area;
            this.world = world;
            this.worldId = world.getWorldConfig().getUuid();
            this.dungeon = dungeon;
            this.knobs = knobs;
            this.startedAtMs = startedAtMs;
        }

        boolean objectiveOpen() {
            return knobs.objective != null && objectiveCount < knobs.objective.amount();
        }

        String where() {
            return area.world() + " area " + area.area() + " (" + dungeon + ", "
                    + (knobs.mode.isEmpty() ? "normal" : knobs.mode) + ")";
        }

        /** What a player arriving in the run is told to do there ({@link DungeonGoals}). */
        DungeonGoals.Goal goal() {
            return new DungeonGoals.Goal(dungeon, knobs.nameKey, knobs.goalKey, knobs.bosses.size(), knobs.allBosses,
                    knobs.objective != null, knobs.surviveMs / 1000L, knobs.limitMs / 1000L, knobs.failOnWipe);
        }
    }

    /** What is waiting to be posted to a copy's world thread: a round moment, a clear's payout, a failure's line. */
    private record Fire(@Nonnull World world, @Nonnull Runnable task) {
    }

    /** Every run by its area. Guarded by {@code this}. */
    private final Map<CopyKey, Run> runs = new HashMap<>();

    /** What is waiting to be posted, drained outside every lock. */
    private final Queue<Fire> outbox = new ConcurrentLinkedQueue<>();

    /**
     * Whether some live run's objective is still counting: the moment listener's one question on every moment,
     * so a server with no such run pays a volatile read. Written under {@code this}.
     */
    private volatile boolean objectivesOpen;

    private DungeonRuns() {
    }

    // ==================== the member settles (under AreaMembers' lock) ====================

    /**
     * {@code player} arrived in {@code area} of {@code world}: they join its run, or begin one, named with
     * {@code onTheWay} (the players already on the way into the same area). A player the run has not counted yet
     * joining it late gets a start of their own, naming them alone; one already counted (named on the way, or
     * back after leaving) gets none. Whoever comes into a run still going is told what to do there
     * ({@link DungeonGoals}); an ended run takes nobody and tells nothing. Called under {@link AreaMembers}' lock;
     * the start moment and the goal line are queued, never fired here.
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
                if (run.ended) {
                    return;
                }
                run.present.add(player);
                queueGoal(run, player);
                if (!run.participants.add(player)) {
                    // Named at the start, or here before: counted once already.
                    return;
                }
                queueStart(run, List.of(player));
                line = player + " joined the run " + run.runId + " in " + run.where()
                        + " after it began, and a start of their own counts them";
            } else {
                run = begin(area, world, dungeonId, dungeon, nowMs);
                run.participants.add(player);
                run.present.add(player);
                runs.put(area, run);
                noteObjectives();

                List<UUID> named = new ArrayList<>();
                named.add(player);
                for (UUID other : onTheWay) {
                    if (!named.contains(other)) {
                        named.add(other);
                    }
                }
                run.participants.addAll(named);
                queueStart(run, named);
                queueGoal(run, player);
                line = startLine(run, player, named.size());
            }
        }
        SafeLog.info(LOG + line);
    }

    /** The log line of a run's first start, by {@code player} with {@code named} named in all. */
    @Nonnull
    private static String startLine(@Nonnull Run run, @Nonnull UUID player, int named) {
        return "a run began in " + run.where() + " with " + player
                + (named > 1 ? " and " + (named - 1) + " more on the way" : "")
                + (run.knobs.limitMs > 0 ? "; it fails after " + run.knobs.limitMs / 1000L + " s"
                        : "; it has no time limit")
                + "; " + clearWords(run.knobs)
                + (run.knobs.failOnWipe ? "; a wipe fails it" : "")
                + (run.knobs.lootable == null ? "" : "; a clear pays " + run.knobs.lootable);
    }

    /**
     * Queue the start moment naming {@code named}: the run's first start, or a late joiner's own. Its player count
     * is the run's as it stands, so a late start reads the size of the run it joined, the joiner included. Under
     * this list's lock.
     */
    private void queueStart(@Nonnull Run run, @Nonnull List<UUID> named) {
        InstanceRoundStartedEvent event = new InstanceRoundStartedEvent(
                metadata(run, run.participants.size(), 0L, null), named);
        outbox.add(new Fire(run.world, () -> InstanceRounds.fireStarted(event)));
    }

    /** Queue the line telling {@code player}, just come into {@code run}, what to do there. Under this list's lock. */
    private void queueGoal(@Nonnull Run run, @Nonnull UUID player) {
        DungeonGoals.Goal goal = run.goal();
        CopyKey area = run.area;
        UUID runId = run.runId;
        outbox.add(new Fire(run.world, () -> DungeonGoals.showSoon(player, area, runId, goal)));
    }

    /** Is {@code runId} the run of {@code area}, and still going? An arrival's goal line asks before it shows. */
    synchronized boolean live(@Nonnull CopyKey area, @Nonnull UUID runId) {
        Run run = runs.get(area);
        return run != null && !run.ended && run.runId.equals(runId);
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
     * Once a second, on the members' ticker thread and outside its lock: clear each run whose survive timer ran
     * out, with someone in the area and the rest of its {@code Complete} met, fail each run past its time limit
     * (a clear due on the same tick wins), queue a wipe check on the world thread for each run that fails on a
     * wipe, and post everything queued. Never throws.
     */
    void tick(long nowMs) {
        try {
            List<Run> wipeChecks = new ArrayList<>();
            synchronized (this) {
                for (Run run : runs.values()) {
                    if (run.ended) {
                        continue;
                    }
                    // A timer clears a run only for a party standing in it when it runs out.
                    if (run.knobs.surviveMs > 0 && !run.present.isEmpty() && holds(run, nowMs)) {
                        end(run, Ending.CLEARED, nowMs, "its party lasted " + run.knobs.surviveMs / 1000L + " s");
                        continue;
                    }
                    if (run.knobs.limitMs > 0 && nowMs - run.startedAtMs >= run.knobs.limitMs) {
                        end(run, Ending.TIME_LIMIT, nowMs, null);
                        continue;
                    }
                    if (run.knobs.failOnWipe && !run.wipeCheckQueued && !run.present.isEmpty()
                            && run.world.isAlive()) {
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
            SHARED.defeated(event.encounterId(), event.worldUuid(), event.participants(), event.damageDealt(),
                    System.currentTimeMillis());
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a boss defeat could not be counted for its dungeon run", t);
        }
    }

    /**
     * Boss {@code encounterId} fell in world {@code worldId} with {@code participants}, who dealt it
     * {@code damage}: count it for each of their areas' runs, which it may clear. AreaMembers is asked first,
     * before this list's lock is taken.
     */
    void defeated(@Nullable String encounterId, @Nullable UUID worldId, @Nonnull List<UUID> participants,
            @Nonnull Map<UUID, Double> damage, long nowMs) {
        if (encounterId == null || encounterId.isBlank() || participants.isEmpty()) {
            return;
        }
        Map<CopyKey, List<UUID>> areas = new LinkedHashMap<>();
        for (UUID participant : participants) {
            CopyKey area = AreaMembers.shared().presentArea(participant);
            if (area != null) {
                areas.computeIfAbsent(area, k -> new ArrayList<>()).add(participant);
            }
        }
        if (areas.isEmpty()) {
            return;
        }
        String boss = encounterId.trim().toLowerCase(Locale.ROOT);
        synchronized (this) {
            for (Map.Entry<CopyKey, List<UUID>> entry : areas.entrySet()) {
                Run run = runs.get(entry.getKey());
                if (run == null || run.ended || (worldId != null && !worldId.equals(run.worldId))) {
                    continue;
                }
                run.bossesDefeated++;
                for (UUID member : entry.getValue()) {
                    Double dealt = damage.get(member);
                    if (dealt != null && Double.isFinite(dealt) && dealt > 0.0) {
                        run.damage.merge(member, dealt, Double::sum);
                    }
                }
                if (run.knobs.bosses.contains(boss)) {
                    run.fallen.add(boss);
                    if (holds(run, nowMs)) {
                        end(run, Ending.CLEARED, nowMs, encounterId.trim() + " fell");
                    }
                }
            }
        }
        drain();
    }

    // ==================== the run tag (the copy's world thread) ====================

    /**
     * The run a fight in {@code worldUuid} belongs to: the run of the area the first of {@code players} who stands
     * in one is in, ended or not, so a fight fought after its run cleared is still filed under it; null when none
     * of them is in an area of that world. zc-encounter's run-tag seam asks it at a defeat. AreaMembers is asked
     * before this list's lock is taken.
     */
    @Nullable
    public RunTag runOf(@Nullable UUID worldUuid, @Nonnull List<UUID> players) {
        for (UUID player : players) {
            CopyKey area = AreaMembers.shared().presentArea(player);
            if (area == null) {
                continue;
            }
            synchronized (this) {
                Run run = runs.get(area);
                if (run != null && (worldUuid == null || worldUuid.equals(run.worldId))) {
                    return new RunTag(SOURCE_PREFIX + run.dungeon, run.runId.toString());
                }
            }
        }
        return null;
    }

    /**
     * What a run's tag source names as its players read it: a dungeon's name ({@code Text.NameKey}, else its id)
     * for {@code dungeon:<id>}, else the source as written. Boss Fights names a fight's dungeon through it.
     */
    @Nonnull
    public static Message nameOfSource(@Nonnull String source) {
        String trimmed = source.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith(SOURCE_PREFIX)) {
            return Msg.raw(trimmed);
        }
        String id = trimmed.substring(SOURCE_PREFIX.length());
        DungeonAsset dungeon = id.isBlank() ? null : DungeonConfig.getInstance().resolve(id);
        return DungeonResults.nameOf(dungeon == null || dungeon.getId() == null ? id : dungeon.getId(),
                dungeon == null ? null : dungeon.nameKey());
    }

    /**
     * A fight's name with the run it was fought in, as Boss Fights' tab reads it ({@code records.fight}, the fight
     * then the dungeon's name): a translation with nested params, so it goes on a {@code TextSpans} sink.
     */
    @Nonnull
    public static Message fightIn(@Nonnull Message fight, @Nonnull String source) {
        return Msg.key(DungeonResults.PREFIX + "records.fight", fight, nameOfSource(source));
    }

    // ==================== an objective's moments (the player's world thread) ====================

    /** Whether some live run's objective is still counting; false costs the moment listener nothing more. */
    public boolean anyObjective() {
        return objectivesOpen;
    }

    /**
     * The objective a moment of {@code player}'s may count toward: the {@code Complete.Objective} of the run of
     * the area they stand in, while it still counts; null when they are in no such run. The caller matches the
     * moment against it and counts a match with {@link #countObjective}, handing the same instance back.
     */
    @Nullable
    public DungeonObjective objectiveOf(@Nonnull UUID player) {
        if (!objectivesOpen) {
            return null;
        }
        CopyKey area = AreaMembers.shared().presentArea(player);
        if (area == null) {
            return null;
        }
        synchronized (this) {
            Run run = runs.get(area);
            return run == null || run.ended || !run.objectiveOpen() ? null : run.knobs.objective;
        }
    }

    /**
     * {@code player} met {@code objective} {@code amount} times: count it for the run of their area, if that run
     * still counts that very objective (the instance {@link #objectiveOf} answered), which may clear it.
     */
    public void countObjective(@Nonnull UUID player, @Nonnull DungeonObjective objective, long amount, long nowMs) {
        if (amount <= 0) {
            return;
        }
        CopyKey area = AreaMembers.shared().presentArea(player);
        if (area == null) {
            return;
        }
        synchronized (this) {
            Run run = runs.get(area);
            if (run == null || run.ended || run.knobs.objective != objective || !run.objectiveOpen()) {
                return;
            }
            run.objectiveCount = Math.min(objective.amount(), run.objectiveCount + amount);
            if (holds(run, nowMs)) {
                end(run, Ending.CLEARED, nowMs, "its objective was met");
            }
            noteObjectives();
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
        return new Run(area, world, preset, dungeon == null ? Knobs.BARE : knobsOf(dungeon), nowMs);
    }

    /** What a run needs of {@code dungeon}, read once. */
    @Nonnull
    private static Knobs knobsOf(@Nonnull DungeonAsset dungeon) {
        Set<String> bosses = new LinkedHashSet<>();
        for (String boss : dungeon.completeBosses()) {
            bosses.add(boss.toLowerCase(Locale.ROOT));
        }
        Integer limit = dungeon.timeLimitSeconds();
        Integer survive = dungeon.surviveTimerSeconds();
        return new Knobs(dungeon.difficulty(), dungeon.nameKey(), dungeon.goalKey(),
                limit == null ? 0L : limit * 1000L, dungeon.failOnWipe(), Set.copyOf(bosses),
                dungeon.completeAllBosses(), dungeon.completeObjective(), survive == null ? 0L : survive * 1000L,
                dungeon.rewardsLootable(), dungeon.rewardsDiminish());
    }

    /**
     * Does every leaf {@code run}'s {@code Complete} writes hold at {@code nowMs}? Never for a run nothing can
     * clear. Under this list's lock.
     */
    private static boolean holds(@Nonnull Run run, long nowMs) {
        Knobs knobs = run.knobs;
        if (!knobs.canClear()) {
            return false;
        }
        boolean bosses = knobs.bosses.isEmpty()
                || (knobs.allBosses ? run.fallen.containsAll(knobs.bosses) : !run.fallen.isEmpty());
        boolean objective = knobs.objective == null || run.objectiveCount >= knobs.objective.amount();
        boolean survived = knobs.surviveMs <= 0 || nowMs - run.startedAtMs >= knobs.surviveMs;
        return bosses && objective && survived;
    }

    /** How a run's log line says what clears it. */
    @Nonnull
    private static String clearWords(@Nonnull Knobs knobs) {
        if (!knobs.canClear()) {
            return "nothing clears it";
        }
        List<String> parts = new ArrayList<>();
        if (!knobs.bosses.isEmpty()) {
            parts.add((knobs.allBosses ? "every one of " : "the first of ") + knobs.bosses + " falling");
        }
        if (knobs.objective != null) {
            DungeonObjective o = knobs.objective;
            parts.add(o.amount() + " x " + o.kind() + (o.target().isEmpty() ? "" : " " + o.target()));
        }
        if (knobs.surviveMs > 0) {
            parts.add("lasting " + knobs.surviveMs / 1000L + " s");
        }
        return "it clears on " + String.join(" and ", parts);
    }

    /** Keep {@link #objectivesOpen} true exactly while a live run's objective still counts. Under this list's lock. */
    private void noteObjectives() {
        boolean open = false;
        for (Run run : runs.values()) {
            if (!run.ended && run.objectiveOpen()) {
                open = true;
                break;
            }
        }
        objectivesOpen = open;
    }

    /**
     * End {@code run} (under this list's lock): mark it, and queue its end moment, then, after a clear, its
     * payout and results screen, or after a failure its party could have prevented, the line telling the members
     * still in the area why. {@code why} names what cleared it, for the log.
     */
    private void end(@Nonnull Run run, @Nonnull Ending ending, long nowMs, @Nullable String why) {
        run.ended = true;
        noteObjectives();
        boolean cleared = ending == Ending.CLEARED;
        long seconds = Math.max(0L, nowMs - run.startedAtMs) / 1000L;
        List<UUID> participants = List.copyOf(run.participants);
        List<UUID> winners = cleared ? List.copyOf(run.present) : List.of();
        InstanceRoundCompletedEvent event = new InstanceRoundCompletedEvent(
                metadata(run, participants.size(), seconds, cleared ? ResultKind.WIN : ResultKind.LOSS),
                participants, winners);
        outbox.add(new Fire(run.world, () -> InstanceRounds.fireCompleted(event)));
        if (cleared && !winners.isEmpty()) {
            Map<UUID, Long> dealt = new HashMap<>();
            for (Map.Entry<UUID, Double> entry : run.damage.entrySet()) {
                dealt.put(entry.getKey(), Math.round(entry.getValue()));
            }
            DungeonResults.Clear clear = new DungeonResults.Clear(run.dungeon, run.runId.toString(), run.knobs.mode,
                    run.knobs.nameKey, run.knobs.lootable, run.knobs.diminish, seconds, winners, Map.copyOf(dealt),
                    run.bossesDefeated);
            outbox.add(new Fire(run.world, () -> DungeonResults.cleared(clear)));
        } else if (ending.lineKey != null && !run.present.isEmpty()) {
            DungeonResults.Failure failure = new DungeonResults.Failure(run.dungeon, run.knobs.nameKey,
                    ending.lineKey, List.copyOf(run.present));
            outbox.add(new Fire(run.world, () -> DungeonResults.failed(failure)));
        }
        SafeLog.info(LOG + "the run " + run.runId + " in " + run.where() + " " + ending.words
                + (why == null ? "" : " (" + why + ")") + " after " + seconds + " s: "
                + participants.size() + " took part, " + winners.size() + " won");
    }

    /** The round's envelope under the dungeon contract. */
    @Nonnull
    private static RoundMetadata metadata(@Nonnull Run run, int players, long seconds, @Nullable ResultKind result) {
        return RoundMetadata.builder(MOD_ID)
                .modeId(run.knobs.mode)
                .presetId(run.dungeon)
                .playerCount(players)
                .durationSeconds(seconds)
                .resultKind(result)
                .build();
    }

    /** Post everything queued to its copy's world thread. Outside every lock; never throws. */
    private void drain() {
        Fire fire;
        while ((fire = outbox.poll()) != null) {
            post(fire);
        }
    }

    /**
     * Run {@code fire} on its copy's world thread; a copy no longer taking tasks has it run here instead, since
     * everything queued hops to each player's own world itself.
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
            SafeLog.warn(LOG + "a dungeon run's round moment, payout or line could not be posted", t);
        }
    }
}
