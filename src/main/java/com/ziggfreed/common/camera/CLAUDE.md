# camera/ - one-shot shake + server-driven camera override

Router for `camera/`. Packet-only, so safe to call off the world thread once a `PlayerRef` is in hand (`writeNoCache` is thread-safe; only the `PlayerRef` acquisition is world-thread). Fully try-guarded.

- **[`CameraShakeService.shake`](CameraShakeService.java)** writes a `CameraEffect.createCameraShakePacket(intensity)` to one player (mirrors the engine `CameraEffectSystem` damage-shake path). Missing asset / null ref / empty id = silent no-op, so the channel can be wired everywhere and only fires when an effect id is supplied.
- **[`ServerCameraService`](ServerCameraService.java) sends `SetServerCamera` (packet 280)** via `playerRef.getPacketHandler().writeNoCache` (the same transport as the shake). `topdown` mirrors the native `PlayerCameraTopdownCommand` field-for-field (`Custom` view, locked, `distance 20`, `pitch -PI/2`, `LookAtPlane` on the up-normal); `reset` mirrors `PlayerCameraResetCommand` (`Custom`, unlocked, null settings); `apply` is the generic (view, locked, settings) escape hatch.
- **ALWAYS `reset` on death / disconnect / round-end** or the player is stranded in the locked camera.
