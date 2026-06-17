package com.ziggfreed.common.camera;

import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.camera.CameraEffect;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Sends a one-shot camera-shake to a single player by referencing a
 * {@code CameraEffect} asset, mirroring the engine's own damage-shake path
 * ({@code builtin.adventure.camera.system.CameraEffectSystem}). Pure server-side,
 * zero reflection: look the effect up in the asset map and write its packet to the
 * player's packet handler.
 *
 * <p>Every entry point degrades to a silent no-op on a missing asset / null ref, so
 * the channel can be wired everywhere without shaking anyone unless an effect id is
 * actually supplied. {@code writeNoCache} is thread-safe, so this is safe to call
 * off the world thread once a {@link PlayerRef} is in hand.
 */
public final class CameraShakeService {

    private CameraShakeService() {
    }

    /**
     * @param playerRef      the player to shake (their client renders it); null = no-op
     * @param cameraEffectId a {@code CameraEffect} asset id; null/empty/missing = no-op
     * @param intensity      contextual intensity in the engine's 0..1 space
     */
    public static void shake(@Nullable PlayerRef playerRef, @Nullable String cameraEffectId, float intensity) {
        if (playerRef == null || cameraEffectId == null || cameraEffectId.isEmpty()) {
            return;
        }
        try {
            int idx = CameraEffect.getAssetMap().getIndex(cameraEffectId);
            if (idx == Integer.MIN_VALUE) {
                return;
            }
            CameraEffect effect = CameraEffect.getAssetMap().getAsset(idx);
            if (effect == null) {
                return;
            }
            playerRef.getPacketHandler().writeNoCache(effect.createCameraShakePacket(intensity));
        } catch (Throwable t) {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("CameraShakeService.shake failed: " + t.getMessage());
        }
    }
}
