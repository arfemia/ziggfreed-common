# i18n/ - client-resolved messages and lang keys

- `I18nModule` prefixes every key with its `.lang` file name (dots kept verbatim, after any subfolder of the locale folder), so an authored content key is not the registered id: resolve it through `ContentKeys` (`resolved`, `tr`, `known`, `pick`) before it reaches the client, or the raw key renders.
- `ContentKeys` needs no registration: the loaded catalogue is the attribution. A consumer's key outranks the library's own `ziggfreedcommon.` one, and between two mods the alphabetically first registered id wins, so shared-store content ids stay owner-prefixed.
- `Msg.tr` and `Msg.key` wrap a plain String arg as a nested `Message.raw`. Never call the engine's bare `param(key, String)` overload yourself: on a plain-String sink the client cannot read it and disconnects.
- Keep a `.lang` file in the module whose code reads it. When splitting one, move entries, never copy: a second definition of a key loses to the first with only a warning.
- `LangCatalog` is the one existence probe (default language), and `overrideForTests` its one test seam.
- `GeneratedLangPack` registers an immutable zip, never a watched directory: a change to a watched `.lang` file prunes every key under its prefix, vanilla ones included. `registerZipPack` unregisters before it rebuilds, so keep that order, and call `unregisterZipPack` from the owner's `shutdown()`. A generated pack cannot satisfy a key the engine validates at asset decode.
- `PlainText` is only for String-only sinks and server-side reads; display text stays a client-resolved `Message`.
