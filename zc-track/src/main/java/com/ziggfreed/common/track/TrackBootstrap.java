package com.ziggfreed.common.track;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.rewards.asset.RewardAsset;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.progress.ObjectiveTargetNames;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.command.ZigTrackCommand;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the track module at library setup, called once from the wiring root's {@code setup()}: the
 * per-player record and the connect hook that attaches it, the four readings, the Track reward kind and how it reads, the native {@code ZigTrack} reward Type,
 * how the two track objective kinds name their targets, the editor's list of tracks, the settle at every ready,
 * the {@code /zigtrack} family and the once-at-boot log of broken track files. The three stores (tracks, curves,
 * categories) are registered with the other framework stores. Registration only ({@code RootRegistrationOnlyTest}).
 */
public final class TrackBootstrap {

    /** The editor dataset of track ids: a track reading's Param, a reward's Track. */
    public static final String TRACKS_DATA_SET = "ziggfreedcommon:tracks";

    private TrackBootstrap() {
    }

    /** Everything the module registers, in order. */
    public static void install(@Nonnull JavaPlugin plugin) {
        // The per-player record, before any world loads, and its connect hook. The player store over it ships
        // filled (TrackStores).
        TrackComponent.register(plugin.getEntityStoreRegistry());
        TrackComponent.install(plugin);
        registerVocabulary();
        registerNativeReward(plugin);
        registerHooks(plugin);
    }

    /** The plugin-free half: the readings, the reward kind and its chip, the objective target names. */
    public static void registerVocabulary() {
        try {
            TrackFactors.contribute();
            TrackRewardKind.registerInto(RewardKinds.shared());
            RewardChips.contribute(TrackRewardKind.chips());
            ObjectiveTargetNames.register(TrackObjectives.LEVEL, TrackObjectives::levelTargetName);
            ObjectiveTargetNames.register(TrackObjectives.TIER, TrackObjectives::tierTargetName);
        } catch (Throwable t) {
            SafeLog.warn("[track] could not declare the track readings, reward kind and objective names", t);
        }
    }

    /**
     * The native reward Type, into the engine's own reward codec, so a {@code Server/Rewards} file pays a climb.
     * Not guarded: a failed registration throws here, at setup, rather than as an unknown Type inside an owner's
     * pack later (R700).
     */
    private static void registerNativeReward(@Nonnull JavaPlugin plugin) {
        plugin.getCodecRegistry(RewardAsset.CODEC).register(ZigTrackReward.TYPE, ZigTrackReward.class,
                ZigTrackReward.CODEC);
    }

    private static void registerHooks(@Nonnull JavaPlugin plugin) {
        try {
            EditorDataSets.live(plugin.getEventRegistry(), TRACKS_DATA_SET, TrackConfig.getInstance()::ids);
            plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, TrackLogin::onPlayerReady);
            plugin.getEventRegistry().register(BootEvent.class, event -> TrackValidator.logErrorsOnce());
            plugin.getCommandRegistry().registerCommand(new ZigTrackCommand());
        } catch (Throwable t) {
            SafeLog.warn("[track] could not hang the track hooks and the /zigtrack family", t);
        }
    }
}
