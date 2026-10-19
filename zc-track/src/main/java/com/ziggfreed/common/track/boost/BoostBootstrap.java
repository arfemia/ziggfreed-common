package com.ziggfreed.common.track.boost;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.rewards.asset.RewardAsset;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.effect.buff.TimedBuffs;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.track.command.ZigBoostCommand;
import com.ziggfreed.common.track.gain.GainFactors;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the boosts at library setup, from the track module's bootstrap: the {@code Boost} gain factor over the
 * neutral one, the tiles on the buff bar, the native {@code ZigBoost} interaction Type and reward Type, the server
 * boosts' read from their file on {@code BootEvent} ({@link ServerBoosts#onBoot}), and the {@code /zigboost} family;
 * the plugin-free half ({@link #registerVocabulary}) is the
 * {@code Boost} reward kind and its chip. The two stores (boosts, settings) are registered with the other
 * framework stores. Registration only ({@code RootRegistrationOnlyTest}).
 */
public final class BoostBootstrap {

    private BoostBootstrap() {
    }

    /** Everything the boosts register, in order. */
    public static void install(@Nonnull JavaPlugin plugin) {
        registerNativeInteraction(plugin);
        registerNativeReward(plugin);
        try {
            GainFactors.register(GainFactors.BOOST, BoostFactor.INSTANCE);
            TimedBuffs.register(BoostTiles.INSTANCE);
            plugin.getEventRegistry().register(BootEvent.class, ServerBoosts::onBoot);
            plugin.getCommandRegistry().registerCommand(new ZigBoostCommand());
        } catch (Throwable t) {
            SafeLog.warn("[boost] could not hang the boost factor, its tiles, the server boosts' boot read or "
                    + "/zigboost", t);
        }
    }

    /** The plugin-free half: the Boost reward kind and how it reads. */
    public static void registerVocabulary() {
        try {
            BoostRewardKind.registerInto(RewardKinds.shared());
            RewardChips.contribute(BoostRewardKind.chips());
        } catch (Throwable t) {
            SafeLog.warn("[boost] could not declare the Boost reward kind", t);
        }
    }

    /**
     * The boost token's Type, into the engine's own interaction codec, so an item's chain starts a boost. Not
     * guarded, as the track module's native reward Type is not: a failed registration throws here, at setup, rather
     * than as an unknown Type inside an owner's item later (R700).
     */
    private static void registerNativeInteraction(@Nonnull JavaPlugin plugin) {
        plugin.getCodecRegistry(Interaction.CODEC).register(ZigBoostInteraction.TYPE_NAME, ZigBoostInteraction.class,
                ZigBoostInteraction.getCODEC());
    }

    /**
     * The boost reward's Type, into the engine's own reward codec, so a {@code Server/Rewards} file starts a boost
     * once collected. Not guarded, for the same reason (R700).
     */
    private static void registerNativeReward(@Nonnull JavaPlugin plugin) {
        plugin.getCodecRegistry(RewardAsset.CODEC).register(ZigBoostReward.TYPE, ZigBoostReward.class,
                ZigBoostReward.CODEC);
    }
}
