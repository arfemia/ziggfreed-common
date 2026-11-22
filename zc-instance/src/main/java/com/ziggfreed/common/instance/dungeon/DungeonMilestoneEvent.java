package com.ziggfreed.common.instance.dungeon;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.event.IEventDispatcher;
import com.hypixel.hytale.server.core.HytaleServer;
import com.ziggfreed.common.util.SafeLog;

/**
 * A dungeon run reached a milestone: a trigger volume's {@code ZigRunMilestone} effect fired for the first time in
 * the run for its id and placement ({@code dungeon/kit/ZigRunMilestoneEffect}). A room cleared, a puzzle solved, a
 * secret found, or any beat a pack names; the library's three are {@link #ROOM_CLEARED}, {@link #PUZZLE_SOLVED} and
 * {@link #SECRET_FOUND}, which the results screen lists. A clear raises two of its own for its winners, with no
 * volume and no player: {@link #CLEAR_FLAWLESS} and {@link #CLEAR_UNDER_PAR}. zc-stats counts it for each member as
 * {@code Dungeon_Milestones}, one line per milestone id; zc-objectives turns it into a {@code DUNGEON_MILESTONE}
 * moment for each of them, which a quest step or an achievement counts; a consumer pays from it (a skill lock's XP,
 * say).
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired on the copy's world thread, once
 * per run per milestone id and placement, until the run's party leaves (after its end too).
 *
 * @param dungeonId   the dungeon's id, as its file names it
 * @param difficulty  its {@code Difficulty} label, empty for normal
 * @param runId       which run
 * @param milestoneId the milestone's id as the volume writes it, trimmed; match it without regard to case
 * @param player      the player whose step fired it, or null when the volume fired with no player (a signal, a clear)
 * @param members     who the milestone credits: every member in the run's area when it fired, in the order they came
 *                    in; for a volume whose {@code ZigRunMilestone} sets {@code PlayerOnly}, the player who set it off
 *                    alone, or nobody when it fired with no player (stats and a {@code DUNGEON_MILESTONE} step count it
 *                    for these players only)
 */
public record DungeonMilestoneEvent(@Nonnull String dungeonId, @Nonnull String difficulty, @Nonnull String runId,
        @Nonnull String milestoneId, @Nullable UUID player, @Nonnull List<UUID> members) implements IEvent<Void> {

    /** A room's foes are down, or whatever a room asks is done: the results screen's rooms line. */
    public static final String ROOM_CLEARED = "Room_Cleared";

    /** A puzzle opened its way: the results screen's puzzles line. */
    public static final String PUZZLE_SOLVED = "Puzzle_Solved";

    /** A secret was found: the results screen's secrets line. */
    public static final String SECRET_FOUND = "Secret_Found";

    /** The run cleared with no member dying in its area during it; raised by the clear for its winners. */
    public static final String CLEAR_FLAWLESS = "Clear_Flawless";

    /**
     * The run cleared in less time than its dungeon's {@code Complete.ParSeconds}, counted from its first arrival;
     * raised by the clear for its winners, never for a dungeon with no par.
     */
    public static final String CLEAR_UNDER_PAR = "Clear_Under_Par";

    public DungeonMilestoneEvent {
        members = List.copyOf(members);
    }

    /** Whether this is milestone {@code id}, matched without regard to case. */
    public boolean is(@Nonnull String id) {
        return milestoneId.equalsIgnoreCase(id);
    }

    /** Announce {@code event}: a no-op when nothing listens, and never a throw back to the caller. */
    static void fire(@Nonnull DungeonMilestoneEvent event) {
        try {
            IEventDispatcher<DungeonMilestoneEvent, DungeonMilestoneEvent> dispatcher =
                    HytaleServer.get().getEventBus().dispatchFor(DungeonMilestoneEvent.class);
            if (dispatcher.hasListener()) {
                dispatcher.dispatch(event);
            }
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] failed to fire DungeonMilestone event", t);
        }
    }
}
