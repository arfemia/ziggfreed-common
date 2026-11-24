package com.ziggfreed.common.objectives.hytale;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.npcobjectives.assets.BountyObjectiveTaskAsset;
import com.hypixel.hytale.builtin.adventure.npcobjectives.assets.KillObjectiveTaskAsset;
import com.hypixel.hytale.builtin.adventure.objectives.config.task.GatherObjectiveTaskAsset;
import com.hypixel.hytale.builtin.adventure.objectives.config.task.ObjectiveTaskAsset;
import com.hypixel.hytale.builtin.adventure.objectives.config.task.TaskSet;
import com.hypixel.hytale.builtin.adventure.objectives.config.taskcondition.TaskConditionAsset;
import com.hypixel.hytale.builtin.adventure.objectives.config.triggercondition.ObjectiveLocationTriggerCondition;
import com.ziggfreed.common.objectives.gate.RequiresCheck;
import com.ziggfreed.common.objectives.gate.ZigRequiresMarkerCondition;
import com.ziggfreed.common.objectives.gate.ZigRequiresTaskCondition;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.gate.GateRuntime;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.GateValidator;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.validation.Finding;

/**
 * What the native objectives ask of {@code ZigRequires} in their own condition maps, read at boot: each authored
 * block through the shared gate audit ({@link GateValidator}: its codes and words, the noun naming the task's Type or
 * the marker), and each place the engine asks the condition about someone who is never a player, about nobody, or
 * never. Every finding's source is the condition's own path: the objective or marker id, then
 * {@code TaskSets[i].Tasks[j].TaskConditions[k]} or {@code TriggerConditions[k]}. {@link NativeObjectiveAudit#run}
 * logs them.
 *
 * <p>Who each map is asked about, read off the engine's own callers:
 * <ul>
 *   <li>a task's {@code TaskConditions}: the player for a craft, use-block, use-entity, reach-location, gather or
 *       {@code ZigMoment} task; the creature killed for a kill task ({@link #NO_PLAYER} for a requirement only a
 *       player can meet); nobody at all when a {@code Gather} task starts ({@link #NOBODY_AT_START}); never, for a
 *       Bounty task, whose conditions the engine only spends ({@link #NEVER_ASKED});</li>
 *   <li>a location marker's {@code TriggerConditions}: the marker's place, never a player ({@link #NO_PLAYER}).</li>
 * </ul>
 * A task Type another mod registers is asked about whatever that mod hands its conditions, so it is checked only
 * through the shared audit. A {@code ZigMoment} task that writes its {@code Moment}'s {@code While} beside a
 * {@code ZigRequires} condition is named once ({@link #BESIDE_WHILE}): both hold the one step, each asked once.
 */
public final class NativeConditionAudit {

    /** The domain every finding carries: the native objective audit's own. */
    public static final String DOMAIN = "objective";

    /**
     * A requirement only a player can meet ({@code Quests}, {@code Permission}, a {@code Custom} kind) in a condition
     * the engine asks about someone who is never a player (the creature a kill task killed, a marker's place): the
     * condition refuses outright there, a {@code Not} group naming one included, so it never holds. A WARNING: it
     * fails closed.
     */
    public static final String NO_PLAYER = "ZIG_REQUIRES_NO_PLAYER";

    /**
     * A {@code Gather} task's condition, which the engine asks about nobody when the task starts, so it refuses then
     * and what the players already carry counts from their next inventory change. An INFO: the task still counts.
     */
    public static final String NOBODY_AT_START = "ZIG_REQUIRES_NOBODY_AT_START";

    /** A Bounty task's condition, which the engine never asks, so it gates nothing. A WARNING. */
    public static final String NEVER_ASKED = "ZIG_REQUIRES_NEVER_ASKED";

    /**
     * A {@code ZigMoment} task gated both by its {@code Moment}'s {@code While} and by a {@code ZigRequires} task
     * condition: two blocks on the one step, where the {@code While} alone says it. An INFO: both hold.
     */
    public static final String BESIDE_WHILE = "ZIG_REQUIRES_BESIDE_WHILE";

    private NativeConditionAudit() {
    }

    /** How many {@code ZigRequires} an objective's or a marker's conditions hold, and what the audit says of them. */
    public record Result(int conditions, @Nonnull List<Finding> findings) {
    }

    /**
     * Every {@code ZigRequires} in the task conditions of an objective's task sets, in task set, task and condition
     * order, and what the audit says of each.
     *
     * @param objectiveId the objective's id, the head of each finding's source
     * @param sets        the objective's task sets, null for none
     */
    @Nonnull
    public static Result objective(@Nonnull String objectiveId, @Nullable TaskSet[] sets) {
        int count = 0;
        List<Finding> out = new ArrayList<>();
        for (int s = 0; sets != null && s < sets.length; s++) {
            ObjectiveTaskAsset[] tasks = sets[s] == null ? null : sets[s].getTasks();
            for (int t = 0; tasks != null && t < tasks.length; t++) {
                ObjectiveTaskAsset task = tasks[t];
                TaskConditionAsset[] conditions = task == null ? null : task.getTaskConditions();
                for (int c = 0; conditions != null && c < conditions.length; c++) {
                    if (conditions[c] instanceof ZigRequiresTaskCondition gate) {
                        count++;
                        out.addAll(task(task, gate.getRequires(), objectiveId + ".TaskSets[" + s + "].Tasks[" + t
                                + "].TaskConditions[" + c + "]"));
                    }
                }
            }
        }
        return new Result(count, out);
    }

    /**
     * Every {@code ZigRequires} in a location marker's trigger conditions, in order, and what the audit says of each.
     *
     * @param markerId   the marker's id, the head of each finding's source
     * @param conditions the marker's trigger conditions, null for none
     */
    @Nonnull
    public static Result marker(@Nonnull String markerId, @Nullable ObjectiveLocationTriggerCondition[] conditions) {
        int count = 0;
        List<Finding> out = new ArrayList<>();
        for (int c = 0; conditions != null && c < conditions.length; c++) {
            if (conditions[c] instanceof ZigRequiresMarkerCondition gate) {
                count++;
                String source = markerId + ".TriggerConditions[" + c + "]";
                out.addAll(shared(gate.getRequires(), source, "location marker"));
                noPlayer(gate.getRequires(), source, "a location marker asks its trigger conditions about its own"
                        + " place with nobody in it", "to gate on who walks up, put ZigRequires in the objective's"
                        + " task conditions", out);
            }
        }
        return new Result(count, out);
    }

    /** One task condition's findings: the shared audit, then who its task's engine asks it about. */
    @Nonnull
    static List<Finding> task(@Nonnull ObjectiveTaskAsset task, @Nullable GateSpec requires, @Nonnull String source) {
        List<Finding> out = new ArrayList<>(shared(requires, source, typeOf(task) + " task"));
        if (task instanceof BountyObjectiveTaskAsset) {
            out.add(Finding.warning(DOMAIN, NEVER_ASKED, "a Bounty task never asks its task conditions (the engine only"
                    + " spends them when the bounty dies), so this ZigRequires gates nothing", source));
        } else if (RequiresCheck.asksNothing(requires)) {
            return out;
        } else if (task instanceof KillObjectiveTaskAsset) {
            noPlayer(requires, source, "a kill task asks its task conditions about the creature killed (any reading of"
                    + " a player reads the creature too)", "to gate the kill on the player, count it with a ZigMoment"
                    + " KILL_ENTITY task and write the block as its Moment's While", out);
        } else if (task instanceof GatherObjectiveTaskAsset) {
            out.add(Finding.info(DOMAIN, NOBODY_AT_START, "a Gather task asks its task conditions about nobody when it"
                    + " starts (to count what its players already carry), so this ZigRequires refuses then and what"
                    + " they carry counts from their next inventory change", source));
        } else if (task instanceof ZigMomentTaskAsset moment) {
            ObjectiveDef step = moment.def();
            if (step != null && step.countsWhile() != null) {
                out.add(Finding.info(DOMAIN, BESIDE_WHILE, "the task's Moment already writes While, so the step is"
                        + " gated twice (each asked once at every moment): write the one block as the While", source));
            }
        }
        return out;
    }

    /** The shared gate audit of one block, each finding filed under the condition's own path. */
    @Nonnull
    private static List<Finding> shared(@Nullable GateSpec requires, @Nonnull String source, @Nonnull String noun) {
        return GateValidator.validate(requires, DOMAIN, source, noun, ProgressionRuntime.gateKinds(),
                GateRuntime.vocabulary()::isRegistered, null);
    }

    /**
     * One {@link #NO_PLAYER} WARNING into {@code out} when the block names, in any group, a leaf only a player can
     * meet ({@link RequiresCheck#playerOnlyLeaves}), for a map the engine asks about someone who is never a player:
     * the condition then refuses outright, a {@code Not} group naming one included
     * ({@link RequiresCheck#holdsWithoutPlayer}), so it never holds there.
     *
     * @param askedAbout whom the map is asked about, the message's middle
     * @param remedy     what to write instead, the message's end
     */
    private static void noPlayer(@Nullable GateSpec requires, @Nonnull String source, @Nonnull String askedAbout,
            @Nonnull String remedy, @Nonnull List<Finding> out) {
        List<String> named = RequiresCheck.playerOnlyLeaves(requires);
        if (!named.isEmpty()) {
            out.add(Finding.warning(DOMAIN, NO_PLAYER, "Requires names " + String.join(", ", named) + ", which only a"
                    + " player can meet, but " + askedAbout + ", so this condition never holds there (in a Not group"
                    + " too); " + remedy, source));
        }
    }

    /** The task's {@code Type} as an objective file names it, or its class's name for one the engine cannot name. */
    @Nonnull
    private static String typeOf(@Nonnull ObjectiveTaskAsset task) {
        try {
            String id = ObjectiveTaskAsset.CODEC.getIdFor(task.getClass());
            return id == null ? task.getClass().getSimpleName() : id;
        } catch (RuntimeException e) {
            return task.getClass().getSimpleName();
        }
    }
}
