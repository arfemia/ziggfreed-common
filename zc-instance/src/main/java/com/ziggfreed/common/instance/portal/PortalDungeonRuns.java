package com.ziggfreed.common.instance.portal;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.portals.resources.PortalWorld;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.portalworld.PortalType;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.instance.metadata.InstanceRoundCompletedEvent;
import com.ziggfreed.common.instance.metadata.InstanceRoundStartedEvent;
import com.ziggfreed.common.instance.metadata.InstanceRounds;
import com.ziggfreed.common.instance.metadata.RoundMetadata;
import com.ziggfreed.common.instance.result.ResultKind;
import com.ziggfreed.common.util.SafeLog;

/**
 * A visit to one of the game's own portal dungeons, counted as a dungeon run: the library never opens or runs the
 * dungeon, it watches the world the game opens for it. A world is such a dungeon while its {@code PortalWorld}
 * resource names a PortalType a {@link PortalDungeonAsset} file follows; its run is keyed by the world's own id
 * (a fresh one each time the game opens the dungeon).
 *
 * <p><b>A run's life.</b>
 * <ul>
 *   <li>It <b>begins</b> when the first player is in the world: ready ({@code PlayerReadyEvent}, in the world's store
 *       and ready, or past the engine's ready timeout) and held by it ({@code World.getPlayerRefs}), so a join that
 *       fails while loading counts nobody. A player in it later, not yet counted, joins it with a start of their
 *       own.</li>
 *   <li>It is <b>cleared</b> when one of the boss fights its file's {@code Complete.Encounters} names is won in its
 *       world ({@link EncounterDefeatedEvent#worldUuid()}). Its winners are exactly the players the world holds then;
 *       one not yet counted (a slow join whose ready the engine never dispatched, once its ready timer ran out before
 *       the player reached the store) gets a start of their own first, and a clear in a followed world with no run
 *       begins one there, from those players, and clears it in the same step. A cleared run stays with its world until
 *       the world goes, so a player coming back into a cleared dungeon starts nothing.</li>
 *   <li>It <b>fails</b> when its last member leaves the world with no clear (it is then forgotten, so a player who
 *       comes back starts a fresh run), or when the world is removed while it is going. Who is still a member is
 *       checked against the players the world holds at each departure, so nobody who is gone can hold a run open.</li>
 * </ul>
 *
 * <p><b>The round contract</b> is a walk-in dungeon's ({@code DungeonRuns}'): mod id {@value DungeonRuns#MOD_ID},
 * mode empty (a native run is normal; a hard boss keeps its hard records on its own), preset the PortalType id, so
 * {@code Dungeon_Runs_Started}, {@code _Completed}, {@code _Failed} and {@code Dungeon_Best_Time}, and a quest or an
 * achievement step targeting {@code dungeon:}, count it with no second path. The first start names the first
 * arrival; a later arrival not yet counted gets a start of their own, its player count the run's as it stands; the
 * end names every player a start named, and its winners only such players (one in the world at a clear who was not
 * yet counted gets a start first).
 *
 * <p><b>Threads and locks.</b> A player's ready, a departure and a boss defeat arrive on the world's own thread (a
 * ready is still handled in a hop of its own, as the library's other ready listeners do, since the event names the
 * player's entity and not the thread); a world's removal arrives on the thread removing it. This list's lock is a
 * leaf, and a round moment is never fired under it: each is queued and posted to the dungeon's world thread, or run
 * where it is drained once the world takes no more tasks.
 */
public final class PortalDungeonRuns {

    private static final String LOG = "[portal] ";

    private static final PortalDungeonRuns SHARED = new PortalDungeonRuns();

    /** The library's one list of portal dungeon runs. */
    @Nonnull
    public static PortalDungeonRuns shared() {
        return SHARED;
    }

    /** One run. Guarded by the list's lock, but for the fields its constructor sets. */
    private static final class Run {
        final World world;
        final UUID worldId;
        final String portalType;
        final List<String> clearedBy;
        final long startedAtMs;
        /** Every player a start named, in order: each is named at the end too. */
        final Set<UUID> participants = new LinkedHashSet<>();
        /** Every player in the world now, as its arrivals and departures say. */
        final Set<UUID> present = new LinkedHashSet<>();
        boolean ended;

        Run(@Nonnull World world, @Nonnull UUID worldId, @Nonnull String portalType, @Nonnull List<String> clearedBy,
                long startedAtMs) {
            this.world = world;
            this.worldId = worldId;
            this.portalType = portalType;
            this.clearedBy = List.copyOf(clearedBy);
            this.startedAtMs = startedAtMs;
        }

        String where() {
            return world.getName() + " (" + portalType + ")";
        }
    }

    /** A round moment waiting to be posted to its world's thread. */
    private record Fire(@Nonnull World world, @Nonnull Runnable task) {
    }

    /** Every run by its world's id. Guarded by {@code this}. */
    private final Map<UUID, Run> runs = new HashMap<>();

    /** What is waiting to be posted, drained outside every lock. */
    private final Queue<Fire> outbox = new ConcurrentLinkedQueue<>();

    private PortalDungeonRuns() {
    }

    // ==================== the engine's events ====================

    /**
     * A player is ready in a world: in the world's store and ready, or past the engine's ready timeout (the engine fires
     * this at most once per world they come into, and never for a join whose timer ran out before the store held them,
     * which a clear makes good, {@link #onDefeated}): on that world's thread, they begin or join its run when it is a
     * followed portal dungeon and the world holds them. Never throws.
     */
    public void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            if (!PortalDungeonConfig.getInstance().anyEnabled()) {
                return;
            }
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = ref == null ? null : ref.getStore().getExternalData().getWorld();
            if (world == null || !world.isAlive()) {
                return;
            }
            world.execute(() -> {
                try {
                    if (!ref.isValid()) {
                        return;
                    }
                    PlayerRef player = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
                    if (player != null) {
                        arrived(world, player.getUuid(), System.currentTimeMillis());
                    }
                } catch (Throwable t) {
                    SafeLog.warn(LOG + "a player's arrival could not be counted toward a portal dungeon run", t);
                }
            });
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a player's arrival in a world could not be read for a portal dungeon run", t);
        }
    }

    /** A player left a world (on its thread): its run loses them, and fails when they were its last. Never throws. */
    public void onPlayerRemoved(@Nonnull RemovedPlayerFromWorldEvent event) {
        try {
            UUID player = playerOf(event.getHolder());
            if (player != null) {
                left(event.getWorld().getWorldConfig().getUuid(), player, System.currentTimeMillis());
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a player's departure from a world could not be read for a portal dungeon run", t);
        }
    }

    /** A boss fight was won (on its world's thread): it clears that world's run when the run's file names it. */
    public void onDefeated(@Nonnull EncounterDefeatedEvent event) {
        try {
            UUID worldId = event.worldUuid();
            if (worldId != null) {
                defeated(worldId, event.encounterId(), System.currentTimeMillis());
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a boss defeat could not be read for a portal dungeon run", t);
        }
    }

    /** A world is going: a run still going in it fails, and it is forgotten. Never throws. */
    public void onWorldRemoved(@Nonnull World world) {
        try {
            UUID worldId = world.getWorldConfig().getUuid();
            synchronized (this) {
                Run run = runs.remove(worldId);
                if (run != null && !run.ended) {
                    end(run, false, System.currentTimeMillis(), "its world closed");
                }
            }
            drain();
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a closing world's portal dungeon run could not be ended", t);
        }
    }

    // ==================== the run list ====================

    /**
     * On {@code world}'s thread: {@code player} is ready in it; they begin or join its run when it is a dungeon and the
     * world holds them (a player whose join failed, or who left before this ran, counts for nothing).
     */
    private void arrived(@Nonnull World world, @Nonnull UUID player, long nowMs) {
        try {
            String portalType = portalTypeOf(world);
            PortalDungeonAsset dungeon = PortalDungeonConfig.getInstance().enabled(portalType);
            if (dungeon == null || !playersIn(world).contains(player)) {
                return;
            }
            UUID worldId = world.getWorldConfig().getUuid();
            String line;
            synchronized (this) {
                Run run = runs.get(worldId);
                if (run == null) {
                    run = new Run(world, worldId, portalType, dungeon.completeEncounters(), nowMs);
                    runs.put(worldId, run);
                    run.participants.add(player);
                    run.present.add(player);
                    queueStart(run, List.of(player));
                    line = "a run began in " + run.where() + " with " + player + "; "
                            + (run.clearedBy.isEmpty() ? "nothing clears it" : "it clears on " + run.clearedBy);
                } else if (run.ended) {
                    return;
                } else {
                    run.present.add(player);
                    if (!run.participants.add(player)) {
                        return;
                    }
                    queueStart(run, List.of(player));
                    line = player + " joined the run in " + run.where() + " after it began, and a start of their own "
                            + "counts them";
                }
            }
            SafeLog.info(LOG + line);
            drain();
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a player's arrival could not be counted toward a portal dungeon run", t);
        }
    }

    /**
     * {@code player} left world {@code worldId} (on its thread): its run's members are those still in it, the leaver
     * gone and anyone the world no longer holds swept out, and the run fails and is forgotten when it was going and
     * nobody is left.
     */
    private void left(@Nonnull UUID worldId, @Nonnull UUID player, long nowMs) {
        synchronized (this) {
            Run run = runs.get(worldId);
            if (run == null) {
                return;
            }
            run.present.remove(player);
            run.present.retainAll(playersIn(run.world));
            if (!run.present.isEmpty()) {
                return;
            }
            if (!run.ended) {
                runs.remove(worldId);
                end(run, false, nowMs, "every member left");
            }
        }
        drain();
    }

    /**
     * Boss fight {@code encounterId} was won in world {@code worldId} (on its thread): the world's run clears when its
     * file names the fight, its winners exactly the players the world holds now, each not yet counted named by a start
     * of their own first. A followed world with no run (nobody's ready ever counted: a slow join whose ready timer ran
     * out before the store held them) begins one there, from those players, and clears it in the same step.
     */
    private void defeated(@Nonnull UUID worldId, @Nonnull String encounterId, long nowMs) {
        Universe universe = Universe.get();
        World world = universe == null ? null : universe.getWorld(worldId);
        if (world == null) {
            return;
        }
        // Read outside the lock, on the world's thread: what the world is, and who it holds.
        String portalType = portalTypeOf(world);
        PortalDungeonAsset dungeon = PortalDungeonConfig.getInstance().enabled(portalType);
        Set<UUID> inWorld = playersIn(world);
        synchronized (this) {
            Run run = runs.get(worldId);
            if (run != null && run.ended) {
                return;
            }
            List<String> clearedBy = run != null ? run.clearedBy
                    : dungeon == null ? List.of() : dungeon.completeEncounters();
            if (!names(clearedBy, encounterId)) {
                return;
            }
            if (run == null) {
                if (inWorld.isEmpty()) {
                    return;
                }
                run = new Run(world, worldId, portalType, clearedBy, nowMs);
                runs.put(worldId, run);
                run.participants.addAll(inWorld);
                queueStart(run, List.copyOf(inWorld));
                // Logged before the clear's own line (end), so the log reads in order.
                SafeLog.info(LOG + "a run began in " + run.where() + " at its clear, with the " + inWorld.size()
                        + " player(s) in it, none of whom was counted as they arrived");
            } else {
                for (UUID player : inWorld) {
                    if (run.participants.add(player)) {
                        queueStart(run, List.of(player));
                    }
                }
            }
            // The winners are exactly the players the world holds now.
            run.present.clear();
            run.present.addAll(inWorld);
            end(run, true, nowMs, encounterId + " fell");
        }
        drain();
    }

    /** Queue the start naming {@code named}, its player count the run's as it stands. Under this list's lock. */
    private void queueStart(@Nonnull Run run, @Nonnull List<UUID> named) {
        InstanceRoundStartedEvent event = new InstanceRoundStartedEvent(
                metadata(run, run.participants.size(), 0L, null), named);
        outbox.add(new Fire(run.world, () -> InstanceRounds.fireStarted(event)));
    }

    /**
     * End {@code run} (under this list's lock): mark it and queue its end moment, every player a start named its
     * participants and, at a clear, every player in the world its winners. {@code why} is for the log.
     */
    private void end(@Nonnull Run run, boolean cleared, long nowMs, @Nonnull String why) {
        run.ended = true;
        long seconds = Math.max(0L, nowMs - run.startedAtMs) / 1000L;
        List<UUID> participants = List.copyOf(run.participants);
        List<UUID> winners = cleared ? List.copyOf(run.present) : List.of();
        InstanceRoundCompletedEvent event = new InstanceRoundCompletedEvent(
                metadata(run, participants.size(), seconds, cleared ? ResultKind.WIN : ResultKind.LOSS),
                participants, winners);
        outbox.add(new Fire(run.world, () -> InstanceRounds.fireCompleted(event)));
        SafeLog.info(LOG + "the run in " + run.where() + (cleared ? " was cleared" : " failed") + " (" + why
                + ") after " + seconds + " s: " + participants.size() + " took part, " + winners.size() + " won");
    }

    /** The round's envelope under the dungeon contract: mode empty, preset the PortalType id. */
    @Nonnull
    private static RoundMetadata metadata(@Nonnull Run run, int players, long seconds, @Nullable ResultKind result) {
        return RoundMetadata.builder(DungeonRuns.MOD_ID)
                .modeId("")
                .presetId(run.portalType)
                .playerCount(players)
                .durationSeconds(seconds)
                .resultKind(result)
                .build();
    }

    /** Post everything queued to its world's thread. Outside every lock; never throws. */
    private void drain() {
        Fire fire;
        while ((fire = outbox.poll()) != null) {
            post(fire);
        }
    }

    /** Run {@code fire} on its world's thread; a world no longer taking tasks has it run here instead. */
    private static void post(@Nonnull Fire fire) {
        try {
            if (fire.world().isAlive()) {
                fire.world().execute(fire.task());
                return;
            }
        } catch (Throwable ignored) {
            // The world stopped taking tasks between the check and the post: run it here.
        }
        try {
            fire.task().run();
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a portal dungeon run's round moment could not be posted", t);
        }
    }

    // ==================== reads ====================

    /**
     * The id of the PortalType {@code world} was opened for, or null when it is no portal world (or the game's portal
     * module is not running). On the world's thread.
     */
    @Nullable
    private static String portalTypeOf(@Nonnull World world) {
        try {
            PortalWorld portal = world.getEntityStore().getStore().getResource(PortalWorld.getResourceType());
            PortalType type = portal == null ? null : portal.getPortalType();
            return type == null ? null : type.getId();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * Every player {@code world} holds now, by id: {@code World.getPlayerRefs}, which the engine tracks as a player's
     * entity is added to the world's store and untracks as it is removed, so a join that never completed is not in it.
     */
    @Nonnull
    private static Set<UUID> playersIn(@Nonnull World world) {
        Set<UUID> out = new LinkedHashSet<>();
        for (PlayerRef ref : world.getPlayerRefs()) {
            if (ref != null) {
                out.add(ref.getUuid());
            }
        }
        return out;
    }

    /** The player a world event's holder carries, or null when it carries none. */
    @Nullable
    private static UUID playerOf(@Nonnull Holder<EntityStore> holder) {
        PlayerRef ref = holder.getComponent(PlayerRef.getComponentType());
        return ref == null ? null : ref.getUuid();
    }

    /** Whether {@code ids} holds {@code id}, matched without regard to case. */
    private static boolean names(@Nonnull List<String> ids, @Nonnull String id) {
        for (String held : ids) {
            if (held.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }
}
