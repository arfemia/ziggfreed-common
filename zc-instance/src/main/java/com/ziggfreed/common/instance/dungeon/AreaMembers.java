package com.ziggfreed.common.instance.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.instance.world.CopyKey;
import com.ziggfreed.common.util.SafeLog;

/**
 * Who is in each dungeon area: every player on the way into one, and every player who arrived and has not left.
 * The engine has no per-world leave event, so one ticker, once a second, on one daemon thread
 * ({@code zc-dungeon-members}) and never the world thread, reads each member's cached {@code PlayerRef} (their
 * world, and their transform as of their last tick) and settles them:
 * <ul>
 *   <li>a player on the way has <b>arrived</b> once they stand in the copy's world, in its store; they have
 *       <b>failed</b> to arrive when they went offline, when the copy's spawn or its paste failed (or ran past
 *       the engine's 30 seconds), when the teleport left them in another world, or after
 *       {@link #ON_THE_WAY_LIMIT_MS} on the way;</li>
 *   <li>a member has <b>left</b> once they went offline, are in another world, or the copy closed, or, in a
 *       copy shared by several parties, once they stand outside their area's cell
 *       ({@link AreaLayout#inCell}).</li>
 * </ul>
 * Each settles with the router ({@link CopyRouter#arrived}, {@link CopyRouter#failed},
 * {@link CopyRouter#left}) and with the area's run ({@link DungeonRuns}: an arrival begins or joins it, a
 * freed area ends one still going); an area whose last player left or failed is free, the router lets the
 * party holding it go, and {@link Areas} pastes it afresh for the next party. The ticker touches no store and
 * no entity, so nothing it settles needs the world thread; after each pass it hands the runs their own tick
 * ({@link DungeonRuns#tick}: time limits, wipe checks, the round moments), outside this list's lock.
 *
 * <p><b>One step.</b> A place is reserved with the router and filed here under this list's lock
 * ({@link #reserve}), and a member is settled with the router, the areas and the runs under the same lock, so
 * an entry and a leave of one area never interleave: an area is marked free here exactly when the router
 * frees it. The lock order is this list, then the router, then the areas, then the runs; none of those calls
 * back here under its own lock.
 *
 * <p>The thread starts with the first member and stops at the library's shutdown ({@link #shutdown}).
 */
public final class AreaMembers {

    /** How often the ticker runs, in milliseconds: the router's own ticker period. */
    public static final long PERIOD_MS = CopyRouter.TICKER_PERIOD_MS;

    /** How long a player may be on the way before their place is given up. */
    public static final long ON_THE_WAY_LIMIT_MS = 120_000L;

    /** How long after the copy is ready a player may still stand in another world before the teleport counts as failed. */
    static final long FALLBACK_GRACE_MS = 5_000L;

    private static final String LOG = "[dungeon] ";

    private static final AreaMembers SHARED = new AreaMembers();

    /** The library's one member list. */
    @Nonnull
    public static AreaMembers shared() {
        return SHARED;
    }

    /** Where a player stands with the dungeon areas. */
    public enum Standing {
        /** In no area and on the way into none. */
        NONE,
        /** On the way into an area. */
        ON_THE_WAY,
        /** In an area. */
        PRESENT
    }

    /** One member. */
    private static final class Member {
        final UUID player;
        final CopyKey area;
        final CompletableFuture<World> ready;
        final long sinceMs;
        /** When the copy was ready for them, or 0 until it is. */
        volatile long readyAtMs;
        boolean present;
        long arrivedAtMs;
        @Nullable World world;
        @Nullable UUID worldId;
        @Nullable AreaLayout layout;
        /** A tick has already warned that it could not settle this member. Read and written by the ticker only. */
        boolean tickFailed;

        Member(UUID player, CopyKey area, CompletableFuture<World> ready, long sinceMs) {
            this.player = player;
            this.area = area;
            this.ready = ready;
            this.sinceMs = sinceMs;
        }
    }

    /** What a tick decided about one member. */
    private enum Verdict {
        STAY,
        ARRIVED,
        FAILED,
        LEFT
    }

    /** Every member by player. Guarded by {@code this}. */
    private final Map<UUID, Member> members = new HashMap<>();

    @Nullable private ScheduledExecutorService ticker;
    private boolean stopped;

    private AreaMembers() {
    }

    /** Where {@code player} stands with the dungeon areas. */
    @Nonnull
    public synchronized Standing standing(@Nonnull UUID player) {
        Member member = members.get(player);
        if (member == null) {
            return Standing.NONE;
        }
        return member.present ? Standing.PRESENT : Standing.ON_THE_WAY;
    }

    /** The area {@code player} is in or on the way into, or null. */
    @Nullable
    public synchronized CopyKey areaOf(@Nonnull UUID player) {
        Member member = members.get(player);
        return member == null ? null : member.area;
    }

    /** The area {@code player} has arrived in and not left, or null (on the way into one counts as none). */
    @Nullable
    public synchronized CopyKey presentArea(@Nonnull UUID player) {
        Member member = members.get(player);
        return member == null || !member.present ? null : member.area;
    }

    /**
     * Reserve {@code player} a place with the router ({@link CopyRouter#reserve}) and file them as on their way,
     * as one step. {@code ready} is the future their teleport waits on: complete it with the copy once it is
     * spawned and the area pasted, or exceptionally when either fails, and the ticker settles the place.
     * Answers null, reserving nothing, when the player is already in an area or on the way into one.
     */
    @Nullable
    public synchronized CopyRouter.Placement reserve(@Nonnull String partyKey, @Nonnull UUID player,
            @Nonnull CopyRouter.Rules rules, long nowMs, @Nonnull CopyRouter.WorldProbe probe,
            @Nonnull CompletableFuture<World> ready) {
        if (members.containsKey(player)) {
            return null;
        }
        CopyRouter.Placement placement = CopyRouter.shared().reserve(partyKey, player, rules, nowMs, probe);
        if (placement.placed() && placement.area() != null) {
            Member member = new Member(player, placement.area(), ready, nowMs);
            ready.whenComplete((world, error) -> member.readyAtMs = System.currentTimeMillis());
            members.put(player, member);
            startTicker();
        }
        return placement;
    }

    /** Stop the ticker thread. Called once from the library's shutdown; harmless twice, and never throws. */
    public static void shutdown() {
        SHARED.stop();
    }

    // ==================== the ticker ====================

    private void startTicker() {
        if (ticker != null || stopped) {
            return;
        }
        ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread daemon = new Thread(task, "zc-dungeon-members");
            daemon.setDaemon(true);
            return daemon;
        });
        thread.scheduleAtFixedRate(this::tick, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
        ticker = thread;
    }

    private synchronized void stop() {
        stopped = true;
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }

    /**
     * One pass over every member. Never throws: a periodic task that throws is never run again. One member who
     * cannot be judged or settled is passed over (warned once), so the others still settle.
     */
    private void tick() {
        try {
            long now = System.currentTimeMillis();
            List<Member> snapshot;
            synchronized (this) {
                snapshot = new ArrayList<>(members.values());
            }
            Universe universe = Universe.get();
            for (Member member : snapshot) {
                try {
                    PlayerRef player = universe == null ? null : universe.getPlayer(member.player);
                    settle(member, judge(member, player, now), player, now);
                } catch (Throwable t) {
                    if (!member.tickFailed) {
                        member.tickFailed = true;
                        SafeLog.warn(LOG + "the area members' tick could not settle " + member.player + " in "
                                + member.area.world() + " area " + member.area.area(), t);
                    }
                }
            }
            Areas.shared().prune();
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the area members' tick failed", t);
        }
        DungeonRuns.shared().tick(System.currentTimeMillis());
    }

    /** What has become of {@code member}, read off their cached {@code PlayerRef}. Touches no store. */
    @Nonnull
    private static Verdict judge(@Nonnull Member member, @Nullable PlayerRef player, long now) {
        if (!member.present) {
            if (player == null || now - member.sinceMs > ON_THE_WAY_LIMIT_MS) {
                return Verdict.FAILED;
            }
            Future.State state = member.ready.state();
            if (state == Future.State.RUNNING) {
                return Verdict.STAY;
            }
            if (state != Future.State.SUCCESS) {
                return Verdict.FAILED;
            }
            World world = member.ready.resultNow();
            if (world == null) {
                return Verdict.FAILED;
            }
            UUID copy = world.getWorldConfig().getUuid();
            boolean inStore = player.getReference() != null;
            if (copy.equals(player.getWorldUuid())) {
                return inStore ? Verdict.ARRIVED : Verdict.STAY;
            }
            long readyAt = member.readyAtMs;
            return inStore && readyAt > 0 && now - readyAt > FALLBACK_GRACE_MS ? Verdict.FAILED : Verdict.STAY;
        }
        World world = member.world;
        if (player == null || world == null || !world.isAlive() || member.worldId == null
                || !member.worldId.equals(player.getWorldUuid())) {
            return Verdict.LEFT;
        }
        AreaLayout layout = member.layout;
        if (layout != null && layout.shared() && player.getReference() != null
                && now - member.arrivedAtMs >= PERIOD_MS) {
            Vector3d at = player.getTransform().getPosition();
            if (!layout.inCell(member.area.area(), at.x, at.z)) {
                return Verdict.LEFT;
            }
        }
        return Verdict.STAY;
    }

    /** Act on a verdict, with the router and the areas, under this list's lock. */
    private void settle(@Nonnull Member member, @Nonnull Verdict verdict, @Nullable PlayerRef player, long now) {
        if (verdict == Verdict.STAY) {
            return;
        }
        String line;
        AreaLayout arrivedIn = null;
        synchronized (this) {
            if (members.get(member.player) != member) {
                return;
            }
            if (verdict == Verdict.ARRIVED) {
                World world = member.ready.resultNow();
                member.present = true;
                member.arrivedAtMs = now;
                member.world = world;
                member.worldId = world.getWorldConfig().getUuid();
                member.layout = layoutOf(world);
                arrivedIn = member.layout;
                CopyRouter.shared().arrived(member.player);
                DungeonRuns.shared().arrived(member.area, world, member.player, onTheWayInto(member.area), now);
                Vector3d at = player == null ? null : player.getTransform().getPosition();
                line = member.player + " arrived in " + world.getName() + " area " + member.area.area()
                        + (at == null ? "" : String.format(Locale.ROOT, " at %.1f %.1f %.1f", at.x, at.y, at.z))
                        + " after " + (now - member.sinceMs) + " ms on the way";
            } else {
                members.remove(member.player);
                if (verdict == Verdict.FAILED) {
                    CopyRouter.shared().failed(member.player, now);
                    line = member.player + " did not reach " + member.area.world() + " area " + member.area.area()
                            + reason(member, player);
                } else {
                    CopyRouter.shared().left(member.player, now);
                    DungeonRuns.shared().left(member.area, member.player);
                    line = member.player + " left " + member.area.world() + " area " + member.area.area() + " after "
                            + (now - member.arrivedAtMs) + " ms inside";
                }
                if (nobodyIn(member.area)) {
                    Areas.shared().released(worldOf(member), member.area);
                    DungeonRuns.shared().freed(member.area, now);
                    line += "; the area is free, and the next party there finds it cleared and pasted afresh";
                }
            }
        }
        SafeLog.info(LOG + line);
        // The area's camera intro, outside the lock: played on arrival, its view handed back on any way out.
        if (verdict == Verdict.ARRIVED) {
            DungeonIntro.playSoon(member.player, member.area, arrivedIn);
        } else {
            DungeonIntro.stop(member.player);
        }
    }

    /** The copy {@code member} arrived in, else the one their teleport was waiting on, else null. */
    @Nullable
    private static World worldOf(@Nonnull Member member) {
        if (member.world != null) {
            return member.world;
        }
        return member.ready.state() == Future.State.SUCCESS ? member.ready.resultNow() : null;
    }

    /** Every player on the way into {@code area} and not yet in it. Under this list's lock. */
    @Nonnull
    private List<UUID> onTheWayInto(@Nonnull CopyKey area) {
        List<UUID> out = new ArrayList<>();
        for (Member other : members.values()) {
            if (!other.present && other.area.equals(area)) {
                out.add(other.player);
            }
        }
        return out;
    }

    /** Is no one in {@code area} or on the way into it? Under this list's lock. */
    private boolean nobodyIn(@Nonnull CopyKey area) {
        for (Member other : members.values()) {
            if (other.area.equals(area)) {
                return false;
            }
        }
        return true;
    }

    /** The copy's area layout, from the spawn provider it opened with; a copy without one is a single area. */
    @Nonnull
    private static AreaLayout layoutOf(@Nonnull World world) {
        return AreaLayout.ofCopy(world);
    }

    /** Why a player on the way never arrived, for the log. */
    @Nonnull
    private static String reason(@Nonnull Member member, @Nullable PlayerRef player) {
        if (player == null) {
            return ": they went offline";
        }
        Future.State state = member.ready.state();
        if (state == Future.State.FAILED) {
            return ": the copy or its paste failed (" + member.ready.exceptionNow() + ")";
        }
        if (state == Future.State.RUNNING) {
            return ": still on the way after " + ON_THE_WAY_LIMIT_MS + " ms";
        }
        return ": the teleport left them in another world";
    }
}
