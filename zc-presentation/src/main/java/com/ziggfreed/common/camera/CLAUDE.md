# camera/

- Both services only write packets (`writeNoCache` is thread-safe), so they are safe off the world thread once a `PlayerRef` is in hand.
- Always call `ServerCameraService.reset` on death, disconnect and round end, or the player stays locked in the server camera.
