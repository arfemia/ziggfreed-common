# zc-core

- `LibraryOwner.NAME` (the hyphenated `ziggfreed-common`) is an attribution for logs and admin output, never a lookup key. The per-domain `OWNER` constants carry the unhyphenated `ziggfreedcommon` ids (registry keys, `mods/ziggfreedcommon/`, `Server/ZiggfreedCommon/`).
- Code here is the most likely to run in a bare unit-test JVM with no Hytale bootstrap, so keep every engine-touching try-guard real.
