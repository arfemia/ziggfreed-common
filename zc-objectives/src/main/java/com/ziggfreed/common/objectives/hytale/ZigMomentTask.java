package com.ziggfreed.common.objectives.hytale;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.objectives.Objective;
import com.hypixel.hytale.builtin.adventure.objectives.ObjectiveDataStore;
import com.hypixel.hytale.builtin.adventure.objectives.ObjectivePlugin;
import com.hypixel.hytale.builtin.adventure.objectives.task.CountObjectiveTask;
import com.hypixel.hytale.builtin.adventure.objectives.task.ObjectiveTask;
import com.hypixel.hytale.builtin.adventure.objectives.transaction.TransactionRecord;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.progress.ObjectiveArithmetic;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * A running {@link ZigMomentTaskAsset} step on one of Hytale's own Objectives. It registers nothing of its own:
 * the engine files every live task by class ({@code ObjectiveDataStore.addTaskRef}, at setup) and drops it again
 * when the task completes or unloads, and {@link MomentTaskFeed} credits each live one off the library's moment
 * stream, so a moment is counted once however many tasks are running.
 *
 * <p>A credited moment moves the count by the step kind's own arithmetic, the one every CommonLib engine runs
 * ({@link ObjectiveArithmetic}): it adds up for an accumulating kind, raises a high-water mark for a value-based
 * one, and fills the step at once for a ceiling kind the first time a value comes in at or under {@code Count}.
 * The engine then does the rest as for any vanilla count task: its task conditions, the packet, completion and the
 * next task set.
 *
 * <p>A value-based step is also seeded when the task attaches (an objective starting, or loading again as a
 * player rejoins), from the standing value CommonLib's quest engine seeds a quest step from when the quest is
 * taken ({@link QuestEngine#standingValue}), so a player already past the target completes it at once, as the
 * vanilla gather task does with what a player already carries ({@link #seedStanding}).
 */
public final class ZigMomentTask extends CountObjectiveTask {

    @Nonnull
    public static final BuilderCodec<ZigMomentTask> CODEC = BuilderCodec.builder(ZigMomentTask.class,
            ZigMomentTask::new, CountObjectiveTask.CODEC).build();

    /** The engine's generator: one task for one step of one task set. */
    public ZigMomentTask(@Nonnull ZigMomentTaskAsset asset, int taskSetIndex, int taskIndex) {
        super(asset, taskSetIndex, taskIndex);
    }

    private ZigMomentTask() {
    }

    @Nonnull
    @Override
    public ZigMomentTaskAsset getAsset() {
        return (ZigMomentTaskAsset) super.getAsset();
    }

    /**
     * Nothing to register or undo: the engine's own task index is what the feed reads. A value-based step is
     * seeded from what its players already stand at once the start or load that set it up has finished
     * ({@link #seedStanding}).
     */
    @Nonnull
    @Override
    protected TransactionRecord[] setup0(@Nonnull Objective objective, @Nonnull World world,
            @Nonnull Store<EntityStore> store) {
        ObjectiveDef step = getAsset().def();
        if (step != null && ProgressionRuntime.objectiveKinds().isValueBased(step.kind())) {
            world.execute(() -> seedStanding(objective, world));
        }
        return new TransactionRecord[0];
    }

    /**
     * Seed this step from the value each of the objective's players in its world already stands at, read as a
     * quest step is seeded when the quest is taken ({@link QuestEngine#standingValue}) and applied as a standing
     * value, so a player already past the target completes the step at once, through the engine's own completion
     * and next task set. A world task after the start or load that set the task up, so the engine has marked its
     * players active by then; a task that completed, or an objective that ended or unloaded, in between is left
     * alone. A kind the quest engine reads no standing for (a track level, a rank) moves nothing here: it settles
     * when its producer next states it, as a quest step does. Never throws.
     */
    void seedStanding(@Nonnull Objective objective, @Nonnull World world) {
        try {
            ObjectiveDef step = getAsset().def();
            if (step == null || isComplete() || objective.isCompleted() || !isCurrentTaskOf(objective)) {
                return;
            }
            ObjectiveKind kind = ProgressionRuntime.objectiveKinds().kind(step.kind());
            QuestEngine quests = ProgressionRuntime.quests();
            Store<EntityStore> store = world.getEntityStore().getStore();
            for (UUID player : List.copyOf(objective.getActivePlayerUUIDs())) {
                Ref<EntityStore> ref = PlayerAccess.liveRef(player);
                if (ref == null || ref.getStore() != store) {
                    continue;
                }
                Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
                long value = subject == null ? 0L : quests.standingValue(subject, step);
                int next = value <= 0L ? count : standing(kind, step, count, value);
                if (next != count) {
                    setTaskCompletion(store, ref, next, objective);
                    if (isComplete()) {
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[objectives] a " + ZigMomentTaskAsset.TYPE + " task of objective '"
                    + objective.getObjectiveId() + "' could not read what its players already stand at: "
                    + t.getMessage());
        }
    }

    /** Whether this task is still one of {@code objective}'s current tasks, and the objective still running. */
    private boolean isCurrentTaskOf(@Nonnull Objective objective) {
        ObjectivePlugin plugin = ObjectivePlugin.get();
        ObjectiveDataStore data = plugin == null ? null : plugin.getObjectiveDataStore();
        ObjectiveTask[] tasks = objective.getCurrentTasks();
        if (data == null || tasks == null || data.getObjective(objective.getObjectiveUUID()) != objective) {
            return false;
        }
        for (ObjectiveTask task : tasks) {
            if (task == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * Count {@code moment}, credited to {@code player}, toward this step of {@code objective} when it is this step's
     * moment: the objective's own world (a vanilla task counts there alone), a player taking part, the step's kind,
     * target, qualifier and zone. World thread.
     */
    void credit(@Nonnull Moment moment, @Nonnull Objective objective, @Nonnull UUID player, @Nonnull UUID world) {
        if (isComplete() || !world.equals(objective.getWorldUUID())
                || !objective.getActivePlayerUUIDs().contains(player)) {
            return;
        }
        ObjectiveDef step = getAsset().def();
        if (step == null || !step.kind().equalsIgnoreCase(moment.kindId())
                || !step.matches(moment.target(), moment.qualifier()) || !step.matchesZone(moment.zone())) {
            return;
        }
        int next = counted(ProgressionRuntime.objectiveKinds().kind(step.kind()), step, count, moment.amount());
        if (next != count) {
            setTaskCompletion(moment.store(), moment.ref(), next, objective);
        }
    }

    /**
     * The count after {@code fired} reaches a step at {@code current}, by {@code kind}'s arithmetic. A ceiling's
     * progress is met or not, so its count is {@code current} until a fired value meets it, then the whole step.
     */
    static int counted(@Nullable ObjectiveKind kind, @Nonnull ObjectiveDef step, int current, long fired) {
        return advanced(kind, step, current, fired, false);
    }

    /**
     * The count after a standing {@code value} (what a player already stands at) reaches a step at
     * {@code current}: a high-water mark whatever the kind's arithmetic, compared as a fired value under a ceiling
     * ({@link ObjectiveArithmetic#applyStanding}), exactly as a quest step is seeded.
     */
    static int standing(@Nullable ObjectiveKind kind, @Nonnull ObjectiveDef step, int current, long value) {
        return advanced(kind, step, current, value, true);
    }

    /** The one walk behind {@link #counted} and {@link #standing}: the step's state rebuilt, moved, read back. */
    private static int advanced(@Nullable ObjectiveKind kind, @Nonnull ObjectiveDef step, int current, long value,
            boolean standing) {
        boolean ceiling = ObjectiveArithmetic.isCeiling(kind);
        int required = step.amountAsInt();
        ObjectiveProgressState state = ObjectiveArithmetic.stored(kind, step,
                ceiling ? (current >= required ? 1 : 0) : current);
        if (standing) {
            ObjectiveArithmetic.applyStanding(kind, step, state, value);
        } else {
            ObjectiveArithmetic.apply(kind, step, state, value);
        }
        if (ceiling) {
            return state.isCompleted() ? required : current;
        }
        return Math.max(current, state.current());
    }

    /**
     * The engine's packet, worded by CommonLib when the objective authors no line for this step: a
     * {@code DescriptionId}, or a line shipped under the engine's default task key, still wins.
     */
    @Nonnull
    @Override
    public com.hypixel.hytale.protocol.ObjectiveTask toPacket(@Nonnull Objective objective) {
        com.hypixel.hytale.protocol.ObjectiveTask packet = super.toPacket(objective);
        ZigMomentTaskAsset step = getAsset();
        if (step.getDescriptionId() != null
                || LangCatalog.has(step.getDescriptionKey(objective.getObjectiveId(), taskSetIndex, taskIndex))) {
            return packet;
        }
        Message line = step.line();
        if (line != null) {
            packet.taskDescriptionKey = line.getFormattedMessage();
        }
        return packet;
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigMomentTask{} " + super.toString();
    }
}
