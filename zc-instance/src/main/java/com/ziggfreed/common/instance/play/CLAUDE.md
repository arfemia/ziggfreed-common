# instance/play/ - the Public, Party and Solo play screen

- The live countdown push updates only the template scalars (`#Status`, `#PlayerCount`), never the appended roster rows, and checks `isDismissed()` first: a push to a stale page crashes the client.
- Queue-mode content (which modes, their icons, order and labels) lives in the consumer's `Server/ZiggfreedCommon/Instances/*.json` `QueueModes` block; `QueueModeSet.fallback()` is a structural net, never content.
