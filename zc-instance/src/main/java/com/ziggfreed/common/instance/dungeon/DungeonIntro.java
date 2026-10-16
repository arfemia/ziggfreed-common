package com.ziggfreed.common.instance.dungeon;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;
import org.joml.Vector3i;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.camera.CameraSequenceAsset;
import com.ziggfreed.common.camera.CameraSequences;
import com.ziggfreed.common.instance.world.CopyKey;
import com.ziggfreed.common.util.SafeLog;

/**
 * The camera intro a dungeon area plays for each player as they arrive: the sequence its {@code Area.IntroCamera}
 * names, measured from the spot the area's prefab was pasted at, so one sequence fits every area of every copy.
 * A dungeon that names none keeps the plain arrival.
 *
 * <p>{@link AreaMembers} calls {@link #playSoon} when a player arrives and {@link #stop} when they leave or never
 * arrive. The intro waits {@link #DELAY_MS} so it does not play under the loading screen, then plays only if the
 * player still stands in that area. While it plays the player's input is held (the sequence's {@code LockInput}),
 * and {@code CameraSequences} hands the view back at its end, when they leave, or when the client never reports
 * the end. The arrival's goal line waits for it ({@link #goalDelayMs}).
 */
final class DungeonIntro {

    /** How long after the arrival the intro starts: past the loading screen the teleport ends on. */
    static final long DELAY_MS = 2_000L;

    /** How long after the intro ends the goal line shows, so it lands once the view is back. */
    static final long GOAL_AFTER_MS = 500L;

    private static final String LOG = "[dungeon] ";

    /** Sequence ids already reported missing, so a typo logs once rather than at every arrival. */
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    private DungeonIntro() {
    }

    /**
     * {@code player} arrived in {@code area} of a copy laid out by {@code layout}: play its dungeon's intro, if it
     * names one, {@link #DELAY_MS} from now. Never throws.
     */
    static void playSoon(@Nonnull UUID player, @Nonnull CopyKey area, @Nullable AreaLayout layout) {
        try {
            if (layout == null || sequenceOf(area) == null) {
                return;
            }
            Vector3i at = layout.origin(area.area());
            Vector3d origin = new Vector3d(at.x, at.y, at.z);
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> playIfStillThere(player, area, origin), DELAY_MS,
                    TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the intro of " + area.world() + " could not be queued for " + player, t);
        }
    }

    /** {@code player} left their area or never reached it: hand their view back if an intro is still playing. */
    static void stop(@Nonnull UUID player) {
        CameraSequences.release(player);
    }

    /**
     * How long after an arrival in {@code dungeonId} its goal line should show: {@code DungeonGoals.DELAY_MS}
     * with no intro, else once the intro has played and the view is back.
     */
    static long goalDelayMs(@Nullable String dungeonId) {
        try {
            CameraSequenceAsset sequence = dungeonId == null ? null : sequenceOf(dungeonId);
            if (sequence == null || sequence.keyframes().isEmpty()) {
                return DungeonGoals.DELAY_MS;
            }
            long introEnd = DELAY_MS + Math.round(sequence.totalSeconds() * 1000.0) + GOAL_AFTER_MS;
            return Math.max(DungeonGoals.DELAY_MS, introEnd);
        } catch (Throwable t) {
            return DungeonGoals.DELAY_MS;
        }
    }

    /** The delayed turn: play the intro on the player's world thread if they still stand in {@code area}. */
    private static void playIfStillThere(@Nonnull UUID player, @Nonnull CopyKey area, @Nonnull Vector3d origin) {
        try {
            if (!area.equals(AreaMembers.shared().presentArea(player))) {
                return;
            }
            CameraSequenceAsset sequence = sequenceOf(area);
            Universe universe = Universe.get();
            PlayerRef playerRef = universe == null ? null : universe.getPlayer(player);
            Ref<EntityStore> ref = playerRef == null ? null : playerRef.getReference();
            if (sequence == null || ref == null || !ref.isValid()) {
                return;
            }
            World world = ref.getStore().getExternalData().getWorld();
            world.execute(() -> {
                if (area.equals(AreaMembers.shared().presentArea(player))
                        && CameraSequences.play(playerRef, sequence, origin)) {
                    SafeLog.info(LOG + "playing the intro " + sequence.getId() + " for " + player + " in "
                            + area.world() + " area " + area.area() + " (" + sequence.totalSeconds() + " s)");
                }
            });
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the intro of " + area.world() + " could not play for " + player, t);
        }
    }

    /** The intro sequence of the dungeon {@code area}'s copy belongs to, or null for none. */
    @Nullable
    private static CameraSequenceAsset sequenceOf(@Nonnull CopyKey area) {
        String dungeonId = CopyRouter.dungeonOf(area.world());
        return dungeonId == null ? null : sequenceOf(dungeonId);
    }

    /** The intro sequence {@code dungeonId} names, or null; a name no pack loaded is reported once. */
    @Nullable
    private static CameraSequenceAsset sequenceOf(@Nonnull String dungeonId) {
        DungeonAsset dungeon = DungeonConfig.getInstance().resolve(dungeonId);
        String id = dungeon == null ? null : dungeon.introCamera();
        if (id == null) {
            return null;
        }
        CameraSequenceAsset sequence = CameraSequenceAsset.get(id);
        if (sequence == null && MISSING.add(id)) {
            SafeLog.warn(LOG + "the dungeon " + dungeonId + " names the intro camera " + id
                    + ", which no loaded pack ships under Server/ZiggfreedCommon/CameraSequences/; it plays none");
        }
        return sequence;
    }
}
