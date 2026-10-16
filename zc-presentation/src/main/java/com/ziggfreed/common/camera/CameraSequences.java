package com.ziggfreed.common.camera;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.CameraSequenceFlags;
import com.hypixel.hytale.protocol.EasingType;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.modules.camera.CameraKeyframeBuilder;
import com.hypixel.hytale.server.core.modules.camera.CameraSequenceBuilder;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.util.SafeLog;

/**
 * Plays a {@link CameraSequenceAsset} on one player's client through the engine's native camera sequence, and
 * makes sure the view always comes back to them.
 *
 * <p>The sequence carries the engine's {@code ReturnToGameplayCameraOnEnd} flag, so the client hands the camera
 * back by itself when the last keyframe lands, and input (held under {@code LockInput}) returns with it. Two
 * guards cover the paths where that might not happen:
 * <ul>
 *   <li>{@link #release} ends a playing sequence at once, by starting a one-keyframe sequence back at the
 *       player's eyes with no input lock (the engine lets a new sequence replace the one playing). A caller
 *       calls it when the player leaves the place the sequence belonged to.</li>
 *   <li>a watchdog fires {@link #GUARD_MS} after the sequence should have ended and releases it if the client
 *       never reported the end; it keeps trying each second while the player is between worlds, and gives up
 *       once they are offline (a client that reconnects starts on its own camera).</li>
 * </ul>
 *
 * <p>Call {@link #play} on the player's world thread (the engine's camera handler is confined to it); the
 * other entry points may be called from anywhere. Everything here logs and swallows its own failures.
 */
public final class CameraSequences {

    /** How long past a sequence's planned end the watchdog waits for the client's end report. */
    static final long GUARD_MS = 2_000L;

    /** How often the watchdog retries a release while the player is between worlds. */
    static final long RETRY_MS = 1_000L;

    /** How many retries before the watchdog stops, the player having stayed out of every world. */
    static final int MAX_RETRIES = 30;

    /** The release's one keyframe: back at the player's eyes this fast, which reads as a cut. */
    private static final float RELEASE_SECONDS = 0.05f;

    private static final String LOG = "[camera] ";

    /** The sequence each player is watching now, by a token unique to that play. */
    private static final Map<UUID, Object> PLAYING = new ConcurrentHashMap<>();

    private CameraSequences() {
    }

    /** Is {@code player} watching a sequence this class started and has not seen end? */
    public static boolean playing(@Nonnull UUID player) {
        return PLAYING.containsKey(player);
    }

    /**
     * Play {@code sequence} for {@code playerRef}, its positions measured from {@code origin}. On the player's world
     * thread. Answers whether it began; a sequence with no keyframes, or a player not in a world, plays nothing.
     */
    public static boolean play(@Nonnull PlayerRef playerRef, @Nonnull CameraSequenceAsset sequence,
            @Nonnull Vector3dc origin) {
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid() || sequence.keyframes().isEmpty()) {
                return false;
            }
            Store<EntityStore> store = ref.getStore();
            View view = viewOf(ref, store);
            if (view == null) {
                return false;
            }
            UUID player = playerRef.getUuid();
            Object token = new Object();

            CameraSequenceBuilder builder = new CameraSequenceBuilder();
            for (CameraSequenceAsset.Keyframe k : sequence.keyframes()) {
                builder.keyframe(keyframe(k, origin, view));
            }
            if (sequence.lockInput()) {
                builder.addFlag(CameraSequenceFlags.LockInput);
            }
            if (sequence.hidePlayer()) {
                builder.addFlag(CameraSequenceFlags.HideLocalPlayer);
            }
            builder.addFlag(CameraSequenceFlags.ReturnToGameplayCameraOnEnd)
                    .onComplete(done -> PLAYING.remove(done.getUuid(), token));

            PLAYING.put(player, token);
            builder.sendTo(playerRef);
            long guardMs = Math.round(sequence.totalSeconds() * 1000.0) + GUARD_MS;
            schedule(() -> guard(player, token, 0), guardMs);
            return true;
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the camera sequence " + sequence.getId() + " could not start", t);
            return false;
        }
    }

    /**
     * Hand {@code player}'s view back now if a sequence this class started is still playing for them; a no-op
     * otherwise. Safe from any thread; the release itself runs on the player's world thread.
     */
    public static void release(@Nonnull UUID player) {
        Object token = PLAYING.get(player);
        if (token != null) {
            tryRelease(player, token);
        }
    }

    /** The watchdog's turn: release a sequence the client never reported ending, retrying while between worlds. */
    private static void guard(@Nonnull UUID player, @Nonnull Object token, int tries) {
        if (PLAYING.get(player) != token) {
            return;
        }
        if (!tryRelease(player, token) && PLAYING.get(player) == token) {
            if (tries >= MAX_RETRIES) {
                PLAYING.remove(player, token);
                SafeLog.warn(LOG + "gave up handing the camera back to " + player + ", who stayed out of every "
                        + "world; their client returns to its own camera when it next joins one");
                return;
            }
            schedule(() -> guard(player, token, tries + 1), RETRY_MS);
        }
    }

    /**
     * Queue the release on the player's world thread. Answers false when the player is in no world right now
     * (between worlds), so the watchdog tries again; true once queued, or once they are offline and nothing is
     * left to release.
     */
    private static boolean tryRelease(@Nonnull UUID player, @Nonnull Object token) {
        try {
            Universe universe = Universe.get();
            PlayerRef playerRef = universe == null ? null : universe.getPlayer(player);
            if (playerRef == null) {
                PLAYING.remove(player, token);
                return true;
            }
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                return false;
            }
            World world = ref.getStore().getExternalData().getWorld();
            world.execute(() -> releaseNow(playerRef, token));
            return true;
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the camera could not be handed back to " + player, t);
            return false;
        }
    }

    /**
     * On the world thread: replace the playing sequence with a cut back to the player's own view. A player who
     * moved on meanwhile keeps their entry, so the watchdog tries again wherever they land.
     */
    private static void releaseNow(@Nonnull PlayerRef playerRef, @Nonnull Object token) {
        UUID player = playerRef.getUuid();
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (PLAYING.get(player) != token || ref == null || !ref.isValid()) {
                return;
            }
            View view = viewOf(ref, ref.getStore());
            // Without a view to cut back to, keep the entry so the watchdog tries again.
            if (view == null || !PLAYING.remove(player, token)) {
                return;
            }
            new CameraSequenceBuilder()
                    .keyframe(view.eye, view.look, RELEASE_SECONDS, EasingType.Linear)
                    .addFlag(CameraSequenceFlags.ReturnToGameplayCameraOnEnd)
                    .sendTo(playerRef);
            SafeLog.info(LOG + "handed the camera back to " + player + " before the sequence ended");
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the camera could not be handed back to " + player, t);
        }
    }

    /** The engine keyframe {@code k} describes, its positions from {@code origin}, its {@code ToPlayer} from {@code view}. */
    @Nonnull
    static CameraKeyframeBuilder keyframe(@Nonnull CameraSequenceAsset.Keyframe k, @Nonnull Vector3dc origin,
            @Nonnull View view) {
        CameraKeyframeBuilder out = new CameraKeyframeBuilder(k.seconds(), k.easing());
        if (k.toPlayer()) {
            out.position(new Vector3d(view.eye)).look(new Rotation3f(view.look));
        } else {
            Vec3 at = k.position();
            if (at != null) {
                out.position(offset(origin, at));
            }
            Vec3 look = k.lookAt();
            if (look != null) {
                out.lookAt(offset(origin, look));
            }
        }
        Float fov = k.fov();
        if (fov != null && Float.isFinite(fov)) {
            out.fov(fov);
        }
        return out;
    }

    @Nonnull
    private static Vector3d offset(@Nonnull Vector3dc origin, @Nonnull Vec3 by) {
        return new Vector3d(origin.x() + by.effectiveX(), origin.y() + by.effectiveY(), origin.z() + by.effectiveZ());
    }

    /** Where a player's eyes are and which way they look, as the game's cinematic teleport reads them. */
    record View(@Nonnull Vector3d eye, @Nonnull Rotation3f look) {
    }

    @Nullable
    private static View viewOf(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
        if (transform == null || head == null) {
            return null;
        }
        ModelComponent model = store.getComponent(ref, ModelComponent.getComponentType());
        float eyeHeight = model != null ? model.getModel().getEyeHeight(ref, store) : 0f;
        Vector3d position = transform.getPosition();
        return new View(new Vector3d(position.x(), position.y() + eyeHeight, position.z()),
                new Rotation3f(head.getRotation()));
    }

    private static void schedule(@Nonnull Runnable task, long delayMs) {
        try {
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    SafeLog.warn(LOG + "a camera watchdog turn failed", t);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a camera watchdog could not be queued", t);
        }
    }
}
