# ui/hud/card/

- A card's colour is one hex multiplied over the shipped frame, pushed on the root `.Background.Color` through `UiRetint.retintColor`: `#ffffffff` is the identity and pushes nothing, and an eight-digit hex carries alpha in its last two digits.
- Each card folds its own leaf over the shared `HudCards/Default.json`: the bar panels shared < spot `Color` < panel `Color` (`HudSpot`); the quest tracker shared < `TrackedQuestHudDeps.color`; the RPG Stations summary shared < `SummaryHud.Color`. The card colour lands after a theme's build-time paint, so the owner's file is the last word.
- The bar dressing follows only the card's opacity, derived in `panel/HudBarDressing` with no authored leaf; its alphas mirror both bar documents (`HudBarDressingTest`).
- Read an authored hex through `HudCardLook.authored`: a malformed value is ignored with one `SafeLog` warn naming its source.
