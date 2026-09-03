package com.ziggfreed.common.objectives.flair;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.util.SafeLog;

/**
 * Wires the library's flair GRANT surface at plugin {@code setup()}: the {@code Flair} reward kind
 * into the shared reward vocabulary, its chip reading into the shared chip ladder, and the
 * {@code /zigflair} family into the command registry. One ordered phase, called once from
 * {@code ZiggfreedCommonPlugin.setup()} right after the entity module registers the component the
 * three of them write to.
 *
 * <p>This module can host it because it is the one that sees every end being joined: the
 * per-player record (zc-entity), the reward vocabulary and the chip ladder (zc-loot), the toast
 * engine the notice draws through (zc-presentation), and the shared target-player command walk
 * (zc-core). The kind is registered here rather than in the wiring root's loot-vocabulary phase for
 * the same reason the progression family is: a kind that announces and draws is more than a
 * vocabulary entry, and the module that owns its write path owns its registration.
 */
public final class FlairBootstrap {

    private FlairBootstrap() {
    }

    /**
     * Register the kind, contribute its chip reading, and register the command family. Every
     * registration is unconditional and decides nothing; the kind and the verbs share one write
     * path, so a server running this library alone can grant, revoke and list flairs out of the box
     * and any consumer's alias calls the same path.
     */
    public static void registerFlairRewards(@Nonnull PluginBase plugin) {
        try {
            FlairRewardKind.registerInto(RewardKinds.shared());
            RewardChips.contribute(FlairChipReading.source());
            plugin.getCommandRegistry().registerCommand(new ZigFlairCommand());
        } catch (Throwable t) {
            SafeLog.warn("[flair] could not wire the Flair reward kind and the /zigflair family - "
                    + "a flair reward will be reported as an unregistered kind this boot", t);
        }
    }
}
