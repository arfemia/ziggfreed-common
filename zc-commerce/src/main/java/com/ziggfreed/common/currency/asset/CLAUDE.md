# currency/asset/ - a wallet as a file

- A share leaf (`OnDeath.LossPercent`, `Decay.PerDayPercent`) is a fraction from 0 to 1, clamped and reported: 10 would wipe a wallet.
- `Requires` on a wallet gates whether the balance is shown, never whether it is earned.
- A knob only one mod understands goes in `Meta` under that mod's namespace; a new economy knob is a nested nullable group beside `OnDeath` and `Decay`.
