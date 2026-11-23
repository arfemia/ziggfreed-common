package com.ziggfreed.common.track.boost;

import javax.annotation.Nonnull;

import com.hypixel.hytale.builtin.rewards.asset.RewardAsset;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.effect.buff.TimedBuffs;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.owner.OwnerFileBindings;
import com.ziggfreed.common.track.command.ZigBoostCommand;
import com.ziggfreed.common.track.gain.GainFactors;
import com.ziggfreed.common.ui.owner.OwnerMenu;
import com.ziggfreed.common.ui.owner.OwnerTile;

/**
 * Registers the boosts at library setup, from the track module's bootstrap: the {@code Boost} gain factor over the
 * neutral one, the tiles on the buff bar, the native {@code ZigBoost} interaction Type and reward Type, the server
 * boosts' read from their file on {@code BootEvent} ({@link ServerBoosts#onBoot}), the {@code /zigboost} family,
 * the two owner files' owner-form bindings (the settings, and the boosts edited one at a time) and the owner menu's
 * Award Boost tile ({@link BoostGivePage}); the plugin-free half ({@link #registerVocabulary}) is the
 * {@code Boost} reward kind and its chip. The two stores (boosts, settings) are registered with the other
 * framework stores. Registration only ({@code RootRegistrationOnlyTest}).
 */
public final class BoostBootstrap {

    /** The Award Boost tile's place in the library's section of the owner menu, after the two boost forms. */
    public static final int GIVE_TILE_ORDER = 60;

    private BoostBootstrap() {
    }

    /**
     * Everything the boosts register, in order. Not guarded: a failed registration throws here, at setup, rather
     * than as a boost that never lifts a gain later.
     */
    public static void install(@Nonnull JavaPlugin plugin) {
        registerNativeInteraction(plugin);
        registerNativeReward(plugin);
        GainFactors.register(GainFactors.BOOST, BoostFactor.INSTANCE);
        TimedBuffs.register(BoostTiles.INSTANCE);
        plugin.getEventRegistry().register(BootEvent.class, ServerBoosts::onBoot);
        plugin.getCommandRegistry().registerCommand(new ZigBoostCommand());
        // The settings file an owner form may write (the boost settings form), its Default read off the settings
        // store under the owner's layer, its reload the store's own owner read.
        OwnerFileBindings.register(OwnerFileBinding.keyedStore(BoostOwnerLayers.SETTINGS_BINDING,
                BoostOwnerLayers::settingsFile, BoostSettingsConfig.getInstance(), BoostSettingsAsset.CODEC,
                BoostOwnerLayers::reloadSettings));
        // The boosts file, edited one boost at a time (the boost templates form): each Default read off the boosts
        // store under the owner's layer, each write through the store's own entry writer, its reload the owner read.
        OwnerFileBindings.register(OwnerFileBinding.keyedStore(BoostOwnerLayers.BOOSTS_BINDING,
                BoostOwnerLayers::file, BoostConfig.getInstance(), BoostAsset.CODEC, BoostOwnerLayers::reload));
        OwnerMenu.add(OwnerTile.screen(OwnerMenu.LIBRARY, "ziggfreedcommon:award_boost",
                        BoostText.line("give.title"), BoostGivePage::open)
                .order(GIVE_TILE_ORDER)
                .line(BoostText.line("give.tile.line"))
                .icon("Potion_Empty"));
    }

    /** The plugin-free half: the Boost reward kind and how it reads. Not guarded, as {@link #install} is not. */
    public static void registerVocabulary() {
        BoostRewardKind.registerInto(RewardKinds.shared());
        RewardChips.contribute(BoostRewardKind.chips());
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
