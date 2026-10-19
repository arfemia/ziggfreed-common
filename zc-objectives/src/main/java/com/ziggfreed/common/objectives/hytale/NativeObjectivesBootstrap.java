package com.ziggfreed.common.objectives.hytale;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.adventure.objectives.ObjectivePlugin;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.runtime.ProgressionRegistrar;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers CommonLib's Types into Hytale's own Objectives, so a vanilla-shaped objective file counts CommonLib's
 * moments and pays through its reward kinds:
 * <ul>
 *   <li>the task {@code ZigMoment} ({@link ZigMomentTaskAsset}), fed by the moment stream ({@link MomentTaskFeed},
 *       a library-default listener), with {@link OffStreamMoments} (a library-default dispatch tap) naming a kind
 *       a loaded task asks for that a mod counts straight into the engines;</li>
 *   <li>the completions {@code ZigRewards} ({@link ZigRewardsCompletionAsset}) and {@code ZigTrack}
 *       ({@link TrackCompletionAsset}), each parking a Claim.</li>
 * </ul>
 *
 * <p>The engine's {@code registerTask} returns silently while its data store is missing, so the registration
 * runs only once the Objectives plugin has set up: the library's manifest names {@code Hytale:Objectives} among its
 * {@code OptionalDependencies}, so the engine sets that plugin up first whenever it runs, and a server that
 * switched it off loads the library anyway, with a line saying no native objective can use these Types. A server
 * where the plugin is installed but had not set up by now is a load-order fault, and says so as a warning
 * ({@link NativeObjectiveAudit#reportNotSetUp}). Once
 * registered, the registration is proved ({@link NativeObjectiveAudit#proveRegistered}), and a failure throws
 * here, at setup, rather than surfacing later inside an owner's pack (R700). Every boot then counts what the
 * loaded objectives ask of the Types ({@link NativeObjectiveAudit#run}). Registration only
 * ({@code RootRegistrationOnlyTest}).
 */
public final class NativeObjectivesBootstrap {

    private NativeObjectivesBootstrap() {
    }

    /** Register the Types, the feed, the tap and the audit. From plugin {@code setup()}, before any asset decodes. */
    public static void registerObjectiveTypes(@Nonnull PluginBase plugin) {
        ObjectivePlugin objectives = ObjectivePlugin.get();
        if (objectives == null || objectives.getObjectiveDataStore() == null) {
            NativeObjectiveAudit.reportNotSetUp();
            return;
        }
        objectives.registerTask(ZigMomentTaskAsset.TYPE, ZigMomentTaskAsset.class, ZigMomentTaskAsset.CODEC,
                ZigMomentTask.class, ZigMomentTask.CODEC, ZigMomentTask::new);
        objectives.registerCompletion(ZigRewardsCompletionAsset.TYPE, ZigRewardsCompletionAsset.class,
                ZigRewardsCompletionAsset.CODEC, ZigRewardsCompletion::new);
        objectives.registerCompletion(TrackCompletionAsset.TYPE, TrackCompletionAsset.class,
                TrackCompletionAsset.CODEC, TrackCompletion::new);
        NativeObjectiveAudit.proveRegistered(objectives);
        ProgressionRegistrar library = ProgressionRuntime.defaults(ProgressionDefaults.OWNER);
        library.momentListener(MomentTaskFeed.INSTANCE);
        library.dispatchTap(OffStreamMoments.INSTANCE);
        plugin.getEventRegistry().register(BootEvent.class, event -> NativeObjectiveAudit.run());
        SafeLog.info("[objectives] registered into Hytale's Objectives: the task " + ZigMomentTaskAsset.TYPE
                + " and the completions " + ZigRewardsCompletionAsset.TYPE + " and " + TrackCompletionAsset.TYPE
                + ", proved on the engine's codec maps and live task index");
    }
}
