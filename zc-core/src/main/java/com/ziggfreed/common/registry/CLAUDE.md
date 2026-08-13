# registry/ - the shared open-registry bookkeeping engine

Router for `com.ziggfreed.common.registry`. One class, and it is the answer to "who owns this id,
and how often has it failed" for every OPEN registry in this library - a registry a third party can
claim an id in.

- **[`RegistryLedger<T>`](RegistryLedger.java)** - a normalized id maps to a value, the owner that
  registered it, a failure count, and the most recent failure message. `put(id, owner, value)` /
  `get` / `isRegistered` / `ids` / `recordFailure` / `info` / `clear`, plus the shared
  `normalize(id)` (trim + lower-case) every registry built on it uses on BOTH sides of a lookup.
  Construct with a label (`new RegistryLedger<>("placement")`) so its warnings say which registry
  they came from.
- **`putQuietly(id, owner, value)`** is `put` minus the overwrite warning, and NOTHING else: same
  replacement, same attribution, same identity-idempotence, and a LATER ordinary `put` over that id
  still warns. It exists for the caller whose own report of the same swap is strictly better than
  the ledger's - the reward-kind fold names the file that took the id over and says what the swap
  cost, so letting the ledger add "two owners wanted this id" above it would print two lines for one
  event, the less useful one first. Reach for it only with a better line in hand.
- **Identity, not equality, decides an overwrite warning.** A consumer re-running its own `setup()`
  passes the SAME provider instance, and that must stay silent; only replacing one DISTINCT instance
  with another logs, once per id, naming both owners. A flapping re-register can therefore never
  spam the log, and an idempotent one never re-attributes the id or drops its failure history.
- **`info()` is a fresh snapshot**, so an admin listing command reads a stable map while
  registration continues around it.

Consumers: [`../factor/FactorRegistry`](../factor/CLAUDE.md), and the three placement registries
through their own `npc.placement.PlacementRegistryLedger` (a thin subclass that only fixes the
`[placement]` log label - every semantic, and the inherited `RegistrationInfo` record, lives here).
**An import must name THIS class** (`RegistryLedger.RegistrationInfo`); a qualified reference
through a subclass (`PlacementRegistryLedger.RegistrationInfo`) resolves normally.

Covered by `RegistryLedgerTest` (zc-core): normalization, the identity-vs-equality overwrite rule,
the quiet put replacing and attributing exactly like the loud one, failure counting, snapshot
freshness, and the ignored-blank-id/null-value cases.
