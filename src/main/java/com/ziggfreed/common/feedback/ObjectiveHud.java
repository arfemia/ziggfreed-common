package com.ziggfreed.common.feedback;

import com.hypixel.hytale.protocol.Objective;
import com.hypixel.hytale.protocol.ObjectiveTask;
import com.hypixel.hytale.protocol.packets.assets.TrackOrUpdateObjective;
import com.hypixel.hytale.protocol.packets.assets.UntrackObjective;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Drives the NATIVE client objective HUD (the tracked-objective list with per-line "N / M"
 * progress) DIRECTLY via the engine {@code TrackOrUpdateObjective} / {@code UntrackObjective}
 * packets, WITHOUT the heavyweight {@code ObjectiveAsset} + {@code ObjectiveDataStore} quest
 * machinery in the {@code Hytale:Objectives} builtin. A consumer owns the objective
 * {@link UUID} and re-sends {@link #track} with updated task progress to refresh the on-screen
 * counters; {@link #untrack} removes the entry.
 *
 * <p>The engine path this mirrors: {@code ObjectivePlugin#startObjective} ends in
 * {@code playerRef.getPacketHandler().writeNoCache(new TrackOrUpdateObjective(objective.toPacket()))},
 * and {@code untrackObjectiveForPlayer} in {@code writeNoCache(new UntrackObjective(uuid))}. We
 * hand-build the same {@code protocol.Objective} ({@code toPacket()} just sets uuid + title +
 * description + tasks), so no asset registration or disk-backed datastore is required.
 *
 * <p>Packet writes are thread-safe (no Store/Ref access here), so this is callable off the world
 * thread. Fully try-guarded: a bad ref / inactive handler degrades to a no-op, never a throw.
 *
 * <p>The localized {@link Message}s are client-resolved (the caller builds them; this reads no
 * locale). This is the reusable persistent-objective-HUD primitive (keystone for onboarding arcs,
 * season-rank readouts, co-op squad progress, boss timers, instanced-content goals).
 */
public final class ObjectiveHud {

    private ObjectiveHud() {
    }

    /**
     * Build one HUD progress line: a localized label plus a "current / needed" counter the client
     * renders (e.g. "Mine Iron Ore  3 / 10"). Pass {@code needed <= 0} for a label-only line.
     */
    @Nonnull
    public static ObjectiveTask task(@Nonnull Message label, int current, int needed) {
        return new ObjectiveTask(label.getFormattedMessage(), current, needed);
    }

    /**
     * Track an objective on the player's native HUD, OR update it in place when re-sent with the
     * SAME {@code uuid} (the client keys the tracked entry on the objective uuid). Re-send after a
     * progress change to advance the on-screen counters.
     *
     * @param uuid        stable id the consumer owns; the same uuid updates, a new uuid adds a row
     * @param title       the objective header
     * @param description optional sub-line under the title, or {@code null}
     * @param tasks       the progress lines, built via {@link #task} (at least one)
     */
    public static void track(@Nonnull PlayerRef playerRef, @Nonnull UUID uuid, @Nonnull Message title,
                             @Nullable Message description, @Nonnull ObjectiveTask... tasks) {
        try {
            final var objective = new Objective(
                    uuid,
                    title.getFormattedMessage(),
                    description != null ? description.getFormattedMessage() : null,
                    null,
                    tasks);
            playerRef.getPacketHandler().writeNoCache(new TrackOrUpdateObjective(objective));
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("ObjectiveHud.track failed: " + t.getMessage());
        }
    }

    /** Remove a tracked objective from the player's native HUD (no-op if not tracked). */
    public static void untrack(@Nonnull PlayerRef playerRef, @Nonnull UUID uuid) {
        try {
            playerRef.getPacketHandler().writeNoCache(new UntrackObjective(uuid));
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("ObjectiveHud.untrack failed: " + t.getMessage());
        }
    }
}
