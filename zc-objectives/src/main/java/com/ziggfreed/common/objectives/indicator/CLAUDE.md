# objectives/indicator/ - what a character shows over its head and on the map

- A character's situations are the NPC quest page's own sections, read through the same `questlist/CharacterQuestListing`, in precedence order Collect > TurnIn > Available > InProgress.
- The knob merges per leaf, narrowest winning: the global word (`Server/ZiggfreedCommon/QuestIndicators/Default.json`, plus the owner file `mods/ziggfreedcommon/quest-indicators.json`) < the quest's `Indicator` block < the step's. The arithmetic is zc-progression's `QuestIndicatorSpec.merge` and `resolve`.
- A state names a look at `Server/ZiggfreedCommon/OverheadIndicators/<State>.json` (zc-entity's store). This module ships one for every state its `QuestIndicators/Default.json` can show, a repeating quest's included, each floating a `Ziggfreed_Marker_*` item (a `Parent` child of the pictured vanilla item that carries the halo); `ShippedQuestLooksTest` keeps the two in step.
- The library reads `overheadFor` and `mapMarks`. The older `overheadAt` and `mapMarksFor` are deprecated legacy reads: a call marks the boot as a consumer drawing its own marks (`QuestMarkYield`), so never call them from library code.
- `QuestIndicatorValidator` belongs in a consumer's late audit, never in `publishAssetContent`, where the look store may not have loaded yet.
