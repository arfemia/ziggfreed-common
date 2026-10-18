package com.ziggfreed.common.track;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.builtin.rewards.asset.RewardAsset;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.permissions.GroupPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.permissions.PlayerPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.progress.ObjectiveTargetNames;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.command.ZigTrackCommand;
import com.ziggfreed.common.track.gain.GainPlans;
import com.ziggfreed.common.track.source.TrackSourceReactions;
import com.ziggfreed.common.track.world.TrackRules;
import com.ziggfreed.common.track.world.TrackWorldRules;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the track module at library setup, called once from the wiring root's {@code setup()}: the
 * per-player record and the connect hook that attaches it, the four readings, the Track reward kind and how it reads, the native {@code ZigTrack} reward Type,
 * how the two track objective kinds name their targets, the editor's list of tracks, the settle at every ready,
 * the {@code /zigtrack} family and the once-at-boot log of broken track files; the native {@code ZigTrackRules}
 * gameplay config group, the track sources' reaction on the produced moments and the gain plans' epoch hooks. The
 * five stores (tracks, curves, categories, sources, world rules) are registered with the other framework stores.
 * Registration only ({@code RootRegistrationOnlyTest}).
 */
public final class TrackBootstrap {

    /** The editor dataset of track ids: a track reading's Param, a reward's Track. */
    public static final String TRACKS_DATA_SET = "ziggfreedcommon:tracks";

    private TrackBootstrap() {
    }

    /** The owner the track sources' reaction is registered under, at library-default rank. */
    public static final String OWNER = "ziggfreedcommon";

    /** Everything the module registers, in order. */
    public static void install(@Nonnull JavaPlugin plugin) {
        // The per-player record, before any world loads, and its connect hook. The player store over it ships
        // filled (TrackStores).
        TrackComponent.register(plugin.getEntityStoreRegistry());
        TrackComponent.install(plugin);
        registerVocabulary();
        registerNativeReward(plugin);
        registerNativeWorldRules(plugin);
        registerHooks(plugin);
        registerGrowth(plugin);
    }

    /**
     * A world's track rules, into the engine's own gameplay config as a {@code Plugin} group
     * ({@code Plugin.ZigTrackRules}), before any gameplay config decodes, so a world's own config sets them (R695).
     * Not guarded, as the reward Type is not: a failed registration throws here, at setup (R700).
     */
    private static void registerNativeWorldRules(@Nonnull JavaPlugin plugin) {
        plugin.getCodecRegistry(GameplayConfig.PLUGIN_CODEC).register(TrackRules.class, TrackRules.ID,
                TrackRules.CODEC);
    }

    /**
     * Growth: the track sources on every produced moment (at library-default rank, so a consumer's reaction stacks
     * beside it), the gain plans' epoch on a world change, a permission change and a departure, and the world
     * rules read again when the gameplay configs load.
     */
    private static void registerGrowth(@Nonnull JavaPlugin plugin) {
        try {
            ProgressionRuntime.defaults(OWNER).momentListener(TrackSourceReactions.INSTANCE);
        } catch (Throwable t) {
            SafeLog.warn("[track] the track sources could not be hung on the produced moments, so no source pays "
                    + "this boot", t);
        }
        try {
            plugin.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, GainPlans::onWorldChange);
            plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, GainPlans::onPlayerReady);
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, GainPlans::onDisconnect);
            plugin.getEventRegistry().register(PlayerPermissionChangeEvent.class, GainPlans::onPlayerPermissions);
            plugin.getEventRegistry().register(GroupPermissionChangeEvent.class, GainPlans::onGroupPermissions);
            plugin.getEventRegistry().register(LoadedAssetsEvent.class, GameplayConfig.class,
                    (LoadedAssetsEvent<String, GameplayConfig, AssetMap<String, GameplayConfig>> event) ->
                            TrackWorldRules.invalidate());
        } catch (Throwable t) {
            SafeLog.warn("[track] could not hang the gain plans' epoch hooks, so a world change may keep the old "
                    + "world's rules on a gain until the next fold", t);
        }
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
