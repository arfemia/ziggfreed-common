# currency/asset/ - a wallet as a file

- The library ships one wallet, `Life_Essence` (`src/main/resources/Server/ZiggfreedCommon/Currencies/ZiggfreedCommon/`, the game's own `Ingredient_Life_Essence` as an item-backed wallet), so every mod prices in the same one; a consumer names it in its prices and ships no copy, since a second file with the id reads over this one (the MMO's `LifeEssenceWalletTest` fails a family member that ships one).
- A share leaf (`OnDeath.LossPercent`, `Decay.PerDayPercent`) is a fraction from 0 to 1, clamped and reported: 10 would wipe a wallet.
- `Requires` on a wallet gates whether the balance is shown, never whether it is earned.
- A knob only one mod understands goes in `Meta` under that mod's namespace; a new economy knob is a nested nullable group beside `OnDeath` and `Decay`.
- `Text.TitleKey` names a wallet (else `currency.<id>.name`, or an item-backed wallet's item) and `Text.FlavorKey` says what it is for; the fold carries only an authored `FlavorKey` (`CurrencyDef.descriptionKey`), never a probed convention key, and only a wallet with one gets the second hover line and the first-earn notice.
- A wallet's hide axis (`isListed()`) only takes it out of listings (`CurrencyConfig.enabled()`, which every wallet listing in the family reads); it still resolves for a credit or a charge, so a seasonal wallet's balances carry over. The economy passes walk `inCirculation()`.
