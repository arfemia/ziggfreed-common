# instance/play/ - the Public, Party and Solo play screen

- The roster's Labels (`#Subtitle`, `#PlayerCount`, `#Status`, `#WaitEstimate`) take a consumer's parameterized lines, so they are painted on `.TextSpans`, never `.Text` (which prints `{0}`).
- The live countdown push updates only the template scalars (`#Status`, `#PlayerCount`), never the appended roster rows, and checks `isDismissed()` on the world thread first (a push to a stale page crashes the client); it writes through `ToastablePage.writeInPlace`.
- Queue-mode content (which modes, their icons, order and labels) lives in the consumer's `Server/ZiggfreedCommon/Instances/*.json` `QueueModes` block; `QueueModeSet.fallback()` is a structural net, never content.
