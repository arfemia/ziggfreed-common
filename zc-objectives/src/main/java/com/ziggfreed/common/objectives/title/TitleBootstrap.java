package com.ziggfreed.common.objectives.title;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.objectives.title.command.ZigTitleCommand;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;
import com.ziggfreed.common.util.SafeLog;

/**
 * Wires the title surface at plugin {@code setup()}, right after the entity module registers the
 * record: the {@code Title} reward kind, its chip reading and the {@code /zigtitle} family, and
 * fills the display-name seam, so a menu shows a player's title beside their name. This module
 * hosts it because it sees every end being joined (the record, the reward vocabulary, the notice
 * engine). Registration only: {@code RootRegistrationOnlyTest} scans every {@code *Bootstrap}.
 */
public final class TitleBootstrap {

    private TitleBootstrap() {
    }

    /** Register the kind, contribute its chip, register the command family and fill the name seam. */
    public static void registerTitles(@Nonnull PluginBase plugin) {
        try {
            TitleRewardKind.registerInto(RewardKinds.shared());
            RewardChips.contribute(TitleChipReading.source());
            plugin.getCommandRegistry().registerCommand(new ZigTitleCommand());
        } catch (Throwable t) {
            SafeLog.warn("[title] could not wire the Title reward kind and the /zigtitle family;"
                    + " a title reward reads as an unregistered kind this boot", t);
        }
        try {
            PlayerDisplayNames.fillDecorator(TitleDisplay.decorator());
        } catch (Throwable t) {
            SafeLog.warn("[title] could not fill the display-name seam, so menus show plain names this boot", t);
        }
    }
}
