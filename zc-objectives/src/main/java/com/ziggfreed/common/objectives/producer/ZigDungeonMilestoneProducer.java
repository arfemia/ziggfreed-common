package com.ziggfreed.common.objectives.producer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.instance.dungeon.DungeonMilestoneEvent;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns a dungeon run's milestone into quest and achievement progress: one {@code DUNGEON_MILESTONE} for every
 * player the milestone names ({@link DungeonMilestoneEvent#members()}, and the player whose step fired it if they
 * stand outside that list), Target the milestone id as the event spells it, Qualifier the dungeon's id as its file
 * names it, Amount 1. The kind's file ({@code ObjectiveKinds/Dungeon_Milestone.json}) ships with the dungeon runs in
 * zc-instance, which raise the event: a room's {@code ZigRunMilestone} volumes ({@code Room_Cleared},
 * {@code Puzzle_Solved}, {@code Secret_Found} or a pack's own id) and a clear's own two ({@code Clear_Flawless},
 * {@code Clear_Under_Par}).
 *
 * <p>An EVENT-BUS listener like {@link ZigInstanceRoundProducer}: the event names players rather than happening to
 * one entity, so each is found on their own world thread ({@link PlayerMomentDispatch}), inline in the usual case,
 * since a milestone fires on the copy's world thread its members stand in. A consumer never fires the kind itself,
 * or each milestone counts twice; zc-stats' {@code Dungeon_Milestones} counts off the event, not this moment.
 */
public final class ZigDungeonMilestoneProducer {

    /** Fired once per player a milestone names. */
    public static final String KIND = "DUNGEON_MILESTONE";

    /** One milestone is one moment for each player it names. */
    private static final long AMOUNT = 1L;

    private static final String LABEL = "dungeon-milestone";

    private ZigDungeonMilestoneProducer() {
    }

    /** Listen for milestones on the shared bus. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().registerGlobal(DungeonMilestoneEvent.class, ZigDungeonMilestoneProducer::onMilestone);
    }

    /** Guarded whole: a producer that throws would take the milestone's other listeners down with it. */
    static void onMilestone(@Nonnull DungeonMilestoneEvent event) {
        try {
            fanOut(event, (playerId, kindId, target, qualifier) ->
                    PlayerMomentDispatch.fire(LABEL, playerId, kindId, target, qualifier, AMOUNT, null));
        } catch (Throwable t) {
            SafeLog.warn("[progression] dungeon milestone progress failed", t);
        }
    }

    /** Where one player's moment goes. A seam purely so the fan-out needs no server to test. */
    @FunctionalInterface
    interface Sink {

        void accept(@Nonnull UUID playerId, @Nonnull String kindId, @Nonnull String target,
                @Nullable String qualifier);
    }

    /**
     * One moment for each member the milestone names, then for the player whose step fired it when they are not
     * among them, each once; none for a milestone with no id.
     *
     * @return how many moments went out
     */
    static int fanOut(@Nonnull DungeonMilestoneEvent event, @Nonnull Sink sink) {
        String target = event.milestoneId().trim();
        if (target.isEmpty()) {
            return 0;
        }
        String dungeon = event.dungeonId().trim();
        String qualifier = dungeon.isEmpty() ? null : dungeon;
        Set<UUID> players = new LinkedHashSet<>(event.members());
        if (event.player() != null) {
            players.add(event.player());
        }
        for (UUID player : players) {
            sink.accept(player, KIND, target, qualifier);
        }
        return players.size();
    }
}
