package com.ziggfreed.common.instance.dungeon;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.instance.world.CopyKey;

/**
 * Where each dungeon run respawns its members once they have reached a checkpoint ({@code ZigCheckpoint},
 * {@code dungeon/kit/ZigCheckpointEffect}): one override per area of a copy, keyed by the area and owned by the run
 * that took it, which {@link AreaSpawnProvider} reads before the area's own landing spot. It never writes the
 * world's spawn or a player's respawn point: those are kept by world name, and copy names repeat.
 *
 * <p><b>A run's checkpoints.</b> Each checkpoint (a placed volume, by its id) is taken at most once per run: the
 * first member to reach it moves the run's respawn there and retires the one before, and a checkpoint already taken
 * in the run, the current one or a retired one, takes nothing again, so walking back through an earlier statue
 * never moves the respawn back. An override another run left in the area (an older run of a copy name that came
 * back) is replaced by the new run's first checkpoint, and never answers meanwhile: {@link #respawnAt} hands one out
 * only while its run is still going ({@code DungeonRuns.live}).
 *
 * <p><b>When the run ends</b> (however it ends: a clear, a failure, everyone gone), its override stops answering,
 * since {@link #respawnAt} checks the run is still going each time it is asked, so a member still there after a clear
 * respawns where the area lands them; the area's next run replaces it with its own first checkpoint.
 * {@code DungeonRuns.end} also asks {@link #clear} to let it go, but nothing here relies on that.
 *
 * <p>Thread-safe without a lock of its own: one {@link ConcurrentHashMap} whose entries are immutable and replaced
 * whole, so the run list may clear an override under its leaf lock and the spawn provider may read one from any
 * thread.
 */
public final class RunCheckpoints {

    private static final RunCheckpoints SHARED = new RunCheckpoints();

    /** The library's one checkpoint list. */
    @Nonnull
    public static RunCheckpoints shared() {
        return SHARED;
    }

    /**
     * One area's override: the run that holds it, where its members respawn, and every checkpoint the run has taken.
     */
    private record Held(@Nonnull UUID runId, @Nonnull Transform at, @Nonnull Set<String> taken) {
    }

    /** Every area's override. */
    private final ConcurrentHashMap<CopyKey, Held> held = new ConcurrentHashMap<>();

    private RunCheckpoints() {
    }

    /**
     * Run {@code runId} of {@code area} reached checkpoint {@code placement}: when the run has not taken it before,
     * its members respawn at {@code at} from now on and the checkpoint before it is retired. True when it was taken
     * now, so the caller heals and tells the party once; false for a checkpoint the run already took.
     */
    public boolean take(@Nonnull CopyKey area, @Nonnull UUID runId, @Nonnull String placement,
            @Nonnull Transform at) {
        boolean[] taken = {false};
        Transform spot = new Transform(at);
        held.compute(area, (key, now) -> {
            if (now == null || !now.runId().equals(runId)) {
                taken[0] = true;
                return new Held(runId, spot, Set.of(placement));
            }
            if (now.taken().contains(placement)) {
                return now;
            }
            taken[0] = true;
            Set<String> more = new HashSet<>(now.taken());
            more.add(placement);
            return new Held(runId, spot, Set.copyOf(more));
        });
        return taken[0];
    }

    /**
     * Where a member of {@code area} respawns in {@code world} while their run holds a checkpoint: a copy of that
     * spot, or null when the area holds none, the override belongs to a run that is over, or {@code area} is no
     * area of {@code world}. The spawn provider's one question, asked before the area's own landing spot.
     */
    @Nullable
    public Transform respawnAt(@Nullable CopyKey area, @Nonnull World world) {
        if (area == null || !area.world().equals(world.getName().toLowerCase(Locale.ROOT))) {
            return null;
        }
        Held now = held.get(area);
        if (now == null || !DungeonRuns.shared().live(area, now.runId())) {
            return null;
        }
        return new Transform(now.at());
    }

    /**
     * Run {@code runId} of {@code area} ended: let its override go if it still holds the area. A take racing the end
     * can file one again; {@link #respawnAt} never answers it, and the next run's first take replaces it.
     */
    void clear(@Nonnull CopyKey area, @Nonnull UUID runId) {
        held.computeIfPresent(area, (key, now) -> now.runId().equals(runId) ? null : now);
    }
}
