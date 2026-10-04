# objectives/title/ - titles a player earns and shows

- A title is a `TitleAsset` (`Server/ZiggfreedCommon/Titles/<Id>.json`; the file name is the id, lower-cased): `Enabled` (false hides it everywhere without taking it from anybody), `Text`, `Order`; owner file `mods/ziggfreedcommon/titles.json` through `TitleOwnerLayers`. `TitleConfig.shown` is the one "is this title on offer" read and `listing` the one picker order (Order, then id).
- What a title is called is `TitleText`: `Text.TitleKey`, else `title.<id>.name` from any loaded lang file, else `Text.DisplayName`, else the id spelled out; `title.<id>.flavor` describes it, and `title.<id>.display` places it around a player's name (`{0}`), else the shared `display` line follows the name after a comma.
