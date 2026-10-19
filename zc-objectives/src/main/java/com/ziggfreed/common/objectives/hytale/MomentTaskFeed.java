package com.ziggfreed.common.objectives.hytale;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.adventure.objectives.Objective;
import com.hypixel.hytale.builtin.adventure.objectives.ObjectiveDataStore;
import com.hypixel.hytale.builtin.adventure.objectives.ObjectivePlugin;
import com.hypixel.hytale.builtin.adventure.objectives.task.ObjectiveTaskRef;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's moment stream, fed to every live {@code ZigMoment} task of Hytale's own Objectives: a reaction to
 * each produced moment (never a second system on a native event), which hands it to each running
 * {@link ZigMomentTask} the engine has filed ({@code ObjectiveDataStore.getTaskRefsForType}) to count if it is that
 * task's moment.
 *
 * <p>It sees what every listener sees: each primary moment once. An alias re-dispatch (a talk's further answered
 * ids, a harvest also counted as a break) reaches the quest and achievement engines only, so a native task counts
 * the moment under its primary kind and id; and {@code DEAL_DAMAGE} reaches the listeners only while a track source
 * names it ({@code ZigDamageProducer}), which the boot audit checks. A kind a mod counts straight into the engines
 * rather than through {@code ProgressDispatch} (some mods do for their own kinds) never reaches any listener, this
 * one included: {@link OffStreamMoments} warns, naming the objectives, the first time one a loaded task names comes
 * in that way.
 *
 * <p>The live tasks are copied before any is credited, so a task set a moment completes, whose next set the engine
 * sets up at once, never hands that same moment to the next set's tasks. A server with no running
 * {@code ZigMoment} task pays one map read a moment. World thread; never throws.
 */
public final class MomentTaskFeed implements MomentListener {

    public static final MomentTaskFeed INSTANCE = new MomentTaskFeed();

    /** A failed reaction has already warned. */
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private MomentTaskFeed() {
    }

    @Override
    public void react(@Nonnull Moment moment) {
        ObjectivePlugin objectives = ObjectivePlugin.get();
        ObjectiveDataStore data = objectives == null ? null : objectives.getObjectiveDataStore();
        Set<ObjectiveTaskRef<ZigMomentTask>> live = data == null ? null : data.getTaskRefsForType(ZigMomentTask.class);
        if (live == null || live.isEmpty()) {
            return;
        }
        try {
            PlayerRef playerRef = PlayerAccess.playerRef(moment.store(), moment.ref());
            UUID player = playerRef == null ? null : playerRef.getUuid();
            UUID world = moment.store().getExternalData().getWorld().getWorldConfig().getUuid();
            if (player == null || world == null) {
                return;
            }
            for (ObjectiveTaskRef<ZigMomentTask> task : List.copyOf(live)) {
                Objective objective = data.getObjective(task.getObjectiveUUID());
                if (objective != null && !objective.isCompleted()) {
                    task.getObjectiveTask().credit(moment, objective, player, world);
                }
            }
        } catch (Throwable t) {
            if (WARNED.compareAndSet(false, true)) {
                SafeLog.warn("[objectives] a moment could not be counted toward a native objective's "
                        + ZigMomentTaskAsset.TYPE + " task; later failures are not logged", t);
            }
        }
    }
}
