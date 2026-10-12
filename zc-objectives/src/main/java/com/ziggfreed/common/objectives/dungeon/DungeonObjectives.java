package com.ziggfreed.common.objectives.dungeon;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.instance.dungeon.DungeonObjective;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.progress.MatchMode;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.ObjectiveMatch;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;

/**
 * A dungeon's {@code Complete.Objective}, met: every produced moment of a player standing in a dungeon area whose
 * run still counts an objective is matched against it the way a quest step is (the kind, through any alias a pack
 * registered, the target under its {@code MatchMode}, the qualifier whole), and a match counts toward the run's
 * total through {@link DungeonRuns#countObjective}, which may clear the run.
 *
 * <p>It lives here because the instance module cannot see the moment stream: it holds the objective's words and
 * the count, and this listener, which sees both, joins them (a reaction to a produced moment, never a second
 * system on the same native event). A server with no such run pays one volatile read a moment
 * ({@link DungeonRuns#anyObjective}). Called on the player's world thread; never throws.
 */
public final class DungeonObjectives implements MomentListener {

    public static final DungeonObjectives INSTANCE = new DungeonObjectives();

    /** A failed reaction has already warned. */
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private DungeonObjectives() {
    }

    @Override
    public void react(@Nonnull Moment moment) {
        DungeonRuns runs = DungeonRuns.shared();
        if (!runs.anyObjective()) {
            return;
        }
        try {
            PlayerRef playerRef = PlayerAccess.playerRef(moment.store(), moment.ref());
            UUID player = playerRef == null ? null : playerRef.getUuid();
            if (player == null) {
                return;
            }
            DungeonObjective objective = runs.objectiveOf(player);
            if (objective == null || !matches(objective, moment)) {
                return;
            }
            runs.countObjective(player, objective, moment.amount(), System.currentTimeMillis());
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                SafeLog.warn("[dungeon] a moment could not be counted toward a dungeon's objective; later failures "
                        + "are not logged", t);
            }
        }
    }

    /** Does {@code moment} meet {@code objective}, read as a quest step with those leaves would read it? */
    static boolean matches(@Nonnull DungeonObjective objective, @Nonnull Moment moment) {
        ObjectiveKindRegistry.Alias alias = ProgressionRuntime.objectiveKinds().alias(objective.kind());
        String kind = alias == null ? objective.kind() : alias.runsAs();
        if (!kind.equalsIgnoreCase(moment.kindId())) {
            return false;
        }
        String target = alias == null ? objective.target() : alias.rewriteTarget(objective.target());
        return ObjectiveMatch.matches(target, MatchMode.fromString(objective.matchMode()), objective.qualifier(),
                moment.target(), moment.qualifier());
    }
}
