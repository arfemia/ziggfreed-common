package com.ziggfreed.common.instance.dungeon;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.event.IEventDispatcher;
import com.hypixel.hytale.server.core.HytaleServer;
import com.ziggfreed.common.util.SafeLog;

/**
 * A player's part in a dungeon's endless wave run ended ({@code Run.Endless}): they left the area, or the run ended
 * with them in it. {@link #waves()} is every wave cleared while they stood in the area, the boss waves among them
 * ({@link #bossWaves()}): what their chest held, and their reach in that run, which the library's
 * {@code Dungeon_Best_Wave} stat keeps as their best on the dungeon's line ({@code dungeon:<difficulty>@<id>}, the
 * round contract's spelling).
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired on the copy's world thread, once per
 * player per run, and only for a player who cleared a wave there.
 *
 * @param dungeonId  the dungeon's id, as its file names it
 * @param difficulty its {@code Difficulty} label, empty for normal
 * @param runId      which run
 * @param player     whose part ended
 * @param waves      the waves cleared while they stood in the area, 1 or more
 * @param bossWaves  how many of those were boss waves
 * @param left       true when they left the area while the run went on, false when the run ended with them in it
 */
public record DungeonWavesEvent(@Nonnull String dungeonId, @Nonnull String difficulty, @Nonnull String runId,
        @Nonnull UUID player, int waves, int bossWaves, boolean left) implements IEvent<Void> {

    /** Announce {@code event}: a no-op when nothing listens, and never a throw back to the caller. */
    static void fire(@Nonnull DungeonWavesEvent event) {
        try {
            IEventDispatcher<DungeonWavesEvent, DungeonWavesEvent> dispatcher =
                    HytaleServer.get().getEventBus().dispatchFor(DungeonWavesEvent.class);
            if (dispatcher.hasListener()) {
                dispatcher.dispatch(event);
            }
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] failed to fire DungeonWaves event", t);
        }
    }
}
