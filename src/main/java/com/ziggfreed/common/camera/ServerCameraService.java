package com.ziggfreed.common.camera;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.MovementForceRotationType;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import org.joml.Vector3f;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Server-driven camera override. Sends the {@code SetServerCamera} packet (280) via
 * the player's packet handler - the exact transport {@link CameraShakeService} uses.
 * The top-down construction mirrors the native {@code PlayerCameraTopdownCommand}
 * field-for-field; {@link #reset} mirrors {@code PlayerCameraResetCommand}.
 *
 * <p>ALWAYS {@link #reset} on death / disconnect / round-end or the player is
 * stranded in the locked camera. {@code writeNoCache} is thread-safe; only the
 * {@link PlayerRef} acquisition is world-thread.
 */
public final class ServerCameraService {

    private ServerCameraService() {
    }

    /** Generic apply: send any (view, locked, settings) to one player. */
    public static void apply(@Nonnull PlayerRef playerRef, @Nonnull ClientCameraView view,
                             boolean isLocked, @Nullable ServerCameraSettings settings) {
        try {
            playerRef.getPacketHandler().writeNoCache(new SetServerCamera(view, isLocked, settings));
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("ServerCameraService apply failed: " + t.getMessage());
        }
    }

    /** Top-down: locked overhead camera (exact engine recipe). */
    public static void topdown(@Nonnull PlayerRef playerRef) {
        ServerCameraSettings s = new ServerCameraSettings();
        s.positionLerpSpeed = 0.2F;
        s.rotationLerpSpeed = 0.2F;
        s.distance = 20.0F;
        s.displayCursor = true;
        s.isFirstPerson = false;
        s.movementForceRotationType = MovementForceRotationType.Custom;
        s.eyeOffset = true;
        s.positionDistanceOffsetType = PositionDistanceOffsetType.DistanceOffset;
        s.rotationType = RotationType.Custom;
        s.rotation = new Direction(0.0F, (float) (-Math.PI / 2), 0.0F); // yaw, pitch=-PI/2, roll
        s.mouseInputType = MouseInputType.LookAtPlane;
        s.planeNormal = new Vector3f(0.0F, 1.0F, 0.0F);
        apply(playerRef, ClientCameraView.Custom, true, s);
    }

    /** Return the player to default first-person/third-person control. */
    public static void reset(@Nonnull PlayerRef playerRef) {
        apply(playerRef, ClientCameraView.Custom, false, null);
    }
}
