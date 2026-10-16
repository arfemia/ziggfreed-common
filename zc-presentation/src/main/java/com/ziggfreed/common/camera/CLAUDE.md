# camera/

- Both services only write packets (`writeNoCache` is thread-safe), so they are safe off the world thread once a `PlayerRef` is in hand.
- Always call `ServerCameraService.reset` on death, disconnect and round end, or the player stays locked in the server camera.
- An authored camera move is a `CameraSequenceAsset` played through `CameraSequences` (the engine's native camera sequence, its javadoc the reference); `play` runs on the player's world thread, and a caller that knows the player left the place calls `release`, the watchdog covering the rest.
