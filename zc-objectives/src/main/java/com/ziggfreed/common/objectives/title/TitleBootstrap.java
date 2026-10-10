package com.ziggfreed.common.objectives.title;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.objectives.title.command.ZigTitleCommand;
import com.ziggfreed.common.objectives.title.page.TitleDestinations;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;
import com.ziggfreed.common.util.SafeLog;

/**
 * Wires the title surface at plugin {@code setup()}, right after the entity module registers the
 * record: the {@code Titles} destination ({@link TitleDestinations}, before any asset decodes), the
 * {@code Title} reward kind, its chip reading and the {@code /zigtitle} family, the two
 * title factors ({@link TitleFactors}), the login payout of changes queued for offline players
 * ({@link TitleQueue}) one beat before the login pass that hands over the titles earned achievements
 * owe ({@link TitleBackfill}), and fills the display-name seam, so a menu shows a player's title
 * beside their name. This module hosts it because it sees every end being joined (the record, the
 * reward vocabulary, the notice engine). Registration only: {@code RootRegistrationOnlyTest} scans
 * every {@code *Bootstrap}.
 */
public final class TitleBootstrap {

    private TitleBootstrap() {
    }

    /**
     * Seed the destination, register the kind, contribute its chip and the factors, register the command
     * family, the queue's payout and the login pass, and fill the name seam.
     */
    public static void registerTitles(@Nonnull PluginBase plugin) {
        try {
            TitleDestinations.register();
        } catch (Throwable t) {
            SafeLog.warn("[title] could not seed the Titles destination; a file opening it fails to load this boot",
                    t);
        }
        try {
            TitleRewardKind.registerInto(RewardKinds.shared());
            RewardChips.contribute(TitleChipReading.source());
            plugin.getCommandRegistry().registerCommand(new ZigTitleCommand());
        } catch (Throwable t) {
            SafeLog.warn("[title] could not wire the Title reward kind and the /zigtitle family;"
                    + " a title reward reads as an unregistered kind this boot", t);
        }
        try {
            TitleFactors.contribute();
        } catch (Throwable t) {
            SafeLog.warn("[title] could not contribute the title factors; a condition on a title stays shut"
                    + " this boot", t);
        }
        try {
            plugin.getEventRegistry().registerGlobal(TitleQueue.READY_PRIORITY, PlayerReadyEvent.class,
                    TitleQueue::onPlayerReady);
        } catch (Throwable t) {
            SafeLog.warn("[title] could not register the payout of queued title changes; they wait for the"
                    + " next boot", t);
        }
        try {
            plugin.getEventRegistry().registerGlobal(EventPriority.LATE, PlayerReadyEvent.class,
                    TitleBackfill::onPlayerReady);
        } catch (Throwable t) {
            SafeLog.warn("[title] could not register the login title pass; a title an earned achievement"
                    + " owes waits for the next boot", t);
        }
        try {
            PlayerDisplayNames.fillDecorator(TitleDisplay.decorator());
        } catch (Throwable t) {
            SafeLog.warn("[title] could not fill the display-name seam, so menus show plain names this boot", t);
        }
    }
}
