package com.ziggfreed.common.objectives.hytale;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.objectives.ObjectiveDataStore;
import com.hypixel.hytale.builtin.adventure.objectives.ObjectivePlugin;
import com.hypixel.hytale.builtin.adventure.objectives.config.ObjectiveAsset;
import com.hypixel.hytale.builtin.adventure.objectives.config.completion.ObjectiveCompletionAsset;
import com.hypixel.hytale.builtin.adventure.objectives.config.task.ObjectiveTaskAsset;
import com.hypixel.hytale.builtin.adventure.objectives.config.task.TaskSet;
import com.hypixel.hytale.builtin.adventure.objectives.task.ObjectiveTask;
import com.ziggfreed.common.factor.ModFactors;
import com.ziggfreed.common.objectives.interaction.InteractionRewards;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.source.TrackSources;
import com.ziggfreed.common.util.SafeLog;

/**
 * The checks behind the native objective Types: why they could not register ({@link #reportNotSetUp}), that a
 * registration landed ({@link #proveRegistered}, at
 * setup, since the engine's {@code registerTask} silently does nothing before its data store exists), and what
 * the loaded objectives ask of them ({@link #run}, on every boot): one INFO line counting them, and a WARNING for
 * each step or completion that can never pay. The boot pass also hands {@link OffStreamMoments} the kinds the
 * loaded tasks name, so a kind a mod counts straight into the engines is named the first time it fires.
 */
public final class NativeObjectiveAudit {

    /** The engine's Objectives plugin, as a manifest names it. */
    static final String OBJECTIVES_PLUGIN = "Hytale:Objectives";

    private NativeObjectiveAudit() {
    }

    /**
     * Say why the Types were not registered, when the Objectives plugin had not set up as the library did: a
     * WARNING when that plugin is installed (a load-order fault, since the manifest's optional dependency should
     * have set it up first), an INFO line when the server does not run it (switched off, or failed to load).
     */
    public static void reportNotSetUp() {
        String types = ZigMomentTaskAsset.TYPE + ", " + ZigRewardsCompletionAsset.TYPE + " or "
                + TrackCompletionAsset.TYPE;
        Double installed = ModFactors.installed(OBJECTIVES_PLUGIN);
        if (installed != null && installed >= 1d) {
            SafeLog.warn("[objectives] the Objectives plugin is installed but had not set up when CommonLib "
                    + "registered its Types (a load-order fault: " + OBJECTIVES_PLUGIN + " should set up first), "
                    + "so no native objective can use " + types);
            return;
        }
        SafeLog.info("[objectives] the Objectives plugin is not running, so no native objective can use " + types);
    }

    /**
     * Throw unless every Type sits in the engine's codec maps under its own name and the task is filed in the
     * engine's live task index, which is the proof the engine's registration did not silently skip.
     */
    public static void proveRegistered(@Nonnull ObjectivePlugin objectives) {
        ObjectiveDataStore data = objectives.getObjectiveDataStore();
        List<String> missing = new ArrayList<>();
        if (ObjectiveTaskAsset.CODEC.getCodecFor(ZigMomentTaskAsset.TYPE) != ZigMomentTaskAsset.CODEC) {
            missing.add("the " + ZigMomentTaskAsset.TYPE + " task asset");
        }
        if (ObjectiveTask.CODEC.getCodecFor(ZigMomentTaskAsset.TYPE) != ZigMomentTask.CODEC) {
            missing.add("the " + ZigMomentTaskAsset.TYPE + " running task");
        }
        if (data == null || data.getTaskRefsForType(ZigMomentTask.class) == null) {
            missing.add("the " + ZigMomentTaskAsset.TYPE + " live task index");
        }
        if (ObjectiveCompletionAsset.CODEC.getCodecFor(ZigRewardsCompletionAsset.TYPE)
                != ZigRewardsCompletionAsset.CODEC) {
            missing.add("the " + ZigRewardsCompletionAsset.TYPE + " completion");
        }
        if (ObjectiveCompletionAsset.CODEC.getCodecFor(TrackCompletionAsset.TYPE) != TrackCompletionAsset.CODEC) {
            missing.add("the " + TrackCompletionAsset.TYPE + " completion");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the native objective Types did not register (" + String.join(", ", missing)
                    + "): an objective naming them would fail to load");
        }
    }

    /** Count and check every loaded objective's use of the Types. Never throws. */
    public static void run() {
        try {
            Counts counts = new Counts();
            for (ObjectiveAsset objective : ObjectiveAsset.getAssetMap().getAssetMap().values()) {
                audit(objective, counts);
            }
            OffStreamMoments.watch(counts.objectivesByKind);
            SafeLog.info(String.format(Locale.ROOT, "[objectives] native objectives: %d use CommonLib's Types "
                    + "(%d %s tasks, %d %s and %d %s completions), %d warning(s)", counts.objectives, counts.tasks,
                    ZigMomentTaskAsset.TYPE, counts.rewards, ZigRewardsCompletionAsset.TYPE, counts.tracks,
                    TrackCompletionAsset.TYPE, counts.warnings));
        } catch (Throwable t) {
            SafeLog.warn("[objectives] the native objective audit could not run: " + t.getMessage());
        }
    }

    /** What one objective asks of the Types, folded into {@code counts}. */
    private static void audit(@Nonnull ObjectiveAsset objective, @Nonnull Counts counts) {
        boolean uses = false;
        TaskSet[] sets = objective.getTaskSets();
        for (TaskSet set : sets == null ? new TaskSet[0] : sets) {
            for (ObjectiveTaskAsset task : set.getTasks() == null ? new ObjectiveTaskAsset[0] : set.getTasks()) {
                if (task instanceof ZigMomentTaskAsset moment) {
                    uses = true;
                    counts.tasks++;
                    warn(objective, momentProblem(moment), counts);
                    ObjectiveDef step = moment.def();
                    if (step != null) {
                        List<String> naming = counts.objectivesByKind.computeIfAbsent(step.kind(),
                                kind -> new ArrayList<>());
                        if (!naming.contains(objective.getId())) {
                            naming.add(objective.getId());
                        }
                    }
                }
            }
        }
        ObjectiveCompletionAsset[] completions = objective.getCompletionHandlers();
        for (ObjectiveCompletionAsset completion : completions == null ? new ObjectiveCompletionAsset[0]
                : completions) {
            if (completion instanceof ZigRewardsCompletionAsset rewards) {
                uses = true;
                counts.rewards++;
                warn(objective, InteractionRewards.specs(rewards.getRewards()).isEmpty()
                        ? ZigRewardsCompletionAsset.TYPE + " pays no reward row" : null, counts);
            } else if (completion instanceof TrackCompletionAsset track) {
                uses = true;
                counts.tracks++;
                warn(objective, trackProblem(track), counts);
            }
        }
        if (uses) {
            counts.objectives++;
        }
    }

    /** Why a {@code ZigMoment} step can never count, or null when it can. */
    @Nullable
    static String momentProblem(@Nonnull ZigMomentTaskAsset task) {
        ObjectiveDef step = task.def();
        if (step == null) {
            return ZigMomentTaskAsset.TYPE + " names no Moment Kind, so it never counts";
        }
        ObjectiveKindRegistry kinds = ProgressionRuntime.objectiveKinds();
        String kind = step.kind();
        if (!kinds.isRegistered(kind)) {
            return ZigMomentTaskAsset.TYPE + " names the kind '" + kind + "', which no mod on this server registers";
        }
        if (!kinds.isProducible(kind)) {
            return ZigMomentTaskAsset.TYPE + " names the kind '" + kind + "', which nothing on this server produces";
        }
        if (TrackSources.DEAL_DAMAGE.equalsIgnoreCase(kind) && !TrackSources.listens(TrackSources.DEAL_DAMAGE)) {
            return ZigMomentTaskAsset.TYPE + " names " + TrackSources.DEAL_DAMAGE + ", which reaches a native "
                    + "objective only while a track source pays on it, and none on this server does, so it never "
                    + "counts";
        }
        return null;
    }

    /** Why a {@code ZigTrack} completion can never pay, or null when it can. */
    @Nullable
    private static String trackProblem(@Nonnull TrackCompletionAsset completion) {
        if (completion.amount() == 0) {
            return TrackCompletionAsset.TYPE + " moves no Amount";
        }
        return TrackService.def(completion.trackId()) == null ? TrackCompletionAsset.TYPE + " names the track '"
                + completion.trackId() + "', which this server does not run" : null;
    }

    private static void warn(@Nonnull ObjectiveAsset objective, @Nullable String problem, @Nonnull Counts counts) {
        if (problem == null) {
            return;
        }
        counts.warnings++;
        SafeLog.warn("[objectives] objective '" + objective.getId() + "': " + problem);
    }

    /** The audit's running tallies. */
    private static final class Counts {
        /** Each kind a task runs on (any case) to the objectives naming it, for {@link OffStreamMoments}. */
        final Map<String, List<String>> objectivesByKind = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int objectives;
        int tasks;
        int rewards;
        int tracks;
        int warnings;
    }
}
