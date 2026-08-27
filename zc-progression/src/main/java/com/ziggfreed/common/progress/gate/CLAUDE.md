# CLAUDE.md - `progress/gate/` (module `zc-progression`)

The ONE requirement model behind every gated thing in this module: what a `Requires` block may say,
and who answers it. Shared, so a requirement written on one kind of content means exactly what it
means on the next.

Package root `com.ziggfreed.common.progress.gate`.

| Class | What it is |
|---|---|
| `GateClause` (+ `appendLeaves`, `of`) | one group of requirements, ALL of which must pass: `Factors` / `Permission` / `Quests` / `Custom` |
| `GateSpec` (+ `of`) | the whole `Requires` block: the leaves plus `AllOf`, `AnyOf` and `Not` |
| `GateKind`, `GateKindRegistry` | the OPEN `Requires.Custom` vocabulary a consumer extends, either desugaring to factor conditions or answering directly |
| `GateEvaluator` | who answers a block for one subject, and the opaque reason token naming what shut it (`firstFailure` stops at the first; `allRefusals` reports every one as a structured `GateRefusal`, and `allFailures` is that walk's token projection). There is ONE per server, held by [`../runtime/ProgressionGates`](../runtime/CLAUDE.md) over the runtime's REGISTERED factor registry, factor context and gate kinds, all read LIVE so a consumer registering its own vocabulary feeds it; `builder()` is for a test or a genuinely private evaluator, and a consumer building a second one is building a second DECISION over one model |
| `GateRefusal` (+ `.Kind`) | ONE unmet requirement, structured: the factor id + authored `Param` + BOTH bounds + the `value` the walk itself RESOLVED (what the player currently has; null when unanswerable) for a factor condition, the quest id, the custom kind id, and the payload-less kinds (permission, `any_of`, `not`). `GateEvaluator.allRefusals` produces them (the value threads through `FactorConditions.allFailuresResolved`, one resolution per condition, never a second lookup); `allFailures` is their `token()` projection byte for byte, so the records and the tokens can never disagree and the bounds and value live ONLY on the record - a token is an opaque id, never a channel for numbers. `fromToken` lifts a gate token back into a (bound-less, value-less) record for a caller holding only strings; it answers null for anything outside this vocabulary |
| `FeatureLift` | the one transformation a consumer with a HIDE axis applies before evaluating: a TOP-LEVEL plain condition on the caller's own `<namespace>:feature` factor (the id `factor.FeatureFlags` contributes) or on `hytale:mod_installed` - bounds-less or `Min: 1` - is lifted into the definition's feature list and REMOVED from the block, because both decide whether content EXISTS on this server (hide) rather than whether a player earned it (lock). Only the top level and only that plain form; nested or upper-bounded conditions stay live requirements, and they need an explicit `Min: 1` because both ids answer a definite 0 for their absent case. A feature id lifts lower-cased; a mod `Group:Name` keeps its case (the plugin table matches case-sensitively) |
| `GateValidator` | the shared `Requires` audit every domain reports through (blank requirements, unknown factors / prerequisites / gate kinds), so a shop's gate and a quest's gate are checked by ONE pass |

## Rules to keep

- **Every unwired seam REFUSES.** A gate that cannot be evaluated must never open, or the first
  server missing a dependency hands out content the author gated. Content authoring no requirements
  needs no wiring at all and is open to everyone.
- **A leaf that has a factor spelling is EVALUATED as that factor**, never answered a second way
  beside it. `Permission` is the worked case: it reads as `hytale:permission` through the same
  registry lookup a `Factors` entry takes, so the two spellings cannot drift, and the only thing a
  consumer wires for it is the factor vocabulary it was going to wire anyway.
- **A refusal is a TOKEN, never a sentence** (`"factor:yourmod:rank"`, `"permission"`,
  `"quest:intro_1"`, `"gate:yourmod:reputation"`, `"any_of"`, `"not"`). Turning one into text a
  player reads is the consumer's job, because only the consumer knows the player's language and its
  own wording.
- **TWO ways to ask, and they answer different questions.** `passes` / `firstFailure` SHORT-CIRCUIT
  at the first unmet requirement, which is what a per-row boolean check in a loop wants and what
  every gate consumer in this library takes. `allFailures` walks the whole block and returns EVERY
  unmet requirement as a list of tokens, in the same leaf-then-`AllOf`-then-`Not`-then-`AnyOf` order,
  for a consumer painting a locked-content detail panel: somebody planning a long-term goal needs the
  whole picture rather than one line at a time as they clear each requirement. The short-circuit pair
  is not a legacy path, and routing a boolean check through the collect-all walk buys nothing and
  costs the rest of the block. **What the two may never do is disagree about whether a block is
  shut**: an empty `allFailures` means exactly what a null `firstFailure` means. That has to be held
  deliberately at the fail-closed branches, where there is no failing condition to name - a clause
  whose every `Factors` entry is blank, evaluated with no vocabulary wired, still contributes one
  bare token on both walks rather than reading as open on one of them. With NO vocabulary wired the
  collect-all walk also STOPS at the `Factors` leaf exactly as the short-circuit walk does, rather
  than reading on into `Permission`/`Quests`/`Custom`: reporting a requirement that was never
  evaluated is worse than reporting fewer.
- **The collect-all walk EVALUATES every leaf in a clause, including ones an earlier failure would
  have spared.** So a registered `Custom` kind sitting behind an already-failing bound is now
  actually called: if it throws, its failure is recorded on the `GateKindRegistry` (what a validate
  command reads) and warned once per collect-all call, where the short-circuit walk would never have
  reached it. That is inherent to answering "everything that is unmet", and it is a second reason a
  per-row boolean check must take `firstFailure` rather than this.
- **A `factor:` token from the collect-all walk carries the condition's `Param` after an `@`**
  (`"factor:yourmod:stat@mining"`), because one clause may bound one factor id twice under two
  different `Param`s. Without the suffix a consumer looking the failing condition back up by bare id
  answers both tokens with whichever was written first, rendering that one twice and dropping the
  other, which is WORSE than reporting one reason. A `Param`-less condition keeps the bare spelling,
  which a consumer reads back as "the bound that authored no `Param`" rather than as any bound on the
  id. `Quests` and `Custom` tokens embed their own id and need nothing. A consumer splits at the
  FIRST `@`, so a `Param` may contain any number of its own and still round-trips; what the spelling
  relies on is that a namespaced FACTOR ID never contains one, and nothing enforces that. The suffix
  separates conditions by `Param`, and by nothing finer: two bounds sharing one factor id AND one
  `Param` across two clauses still answer with whichever was written first. Author one bound
  per `(Factor, Param)` pair in a block - a second is redundant, since the tighter of two `Min`s is
  the only one that can ever be the binding requirement.
- **An `AnyOf` block contributes exactly ONE token however many routes it offers**, on both paths.
  The routes are alternatives, so one token per route would read as several separate things to go and
  do rather than one choice. A passing `Not` group and an unresolved `Custom` kind DO contribute one
  token each, so two different passing `Not` groups legitimately render as the same generic line
  twice - there is no richer wording to offer about either, which is not the same as the factor case.
- **Nesting stops at one level.** `AllOf` plus `AnyOf` plus `Not` expresses "these, plus one of
  those, and none of these", which is the shape real requirements take. A requirement that genuinely
  needs more is better written as a registered `Custom` kind whose rule lives in code. `Not` takes a
  list of CLAUSES for that reason, never a nested `GateSpec`.
- **A `Not` group shuts the gate by PASSING**, and each entry is one whole group that has to fail:
  one group listing two things means "not both of those", two groups mean "neither of them". A
  numeric requirement can already be negated with a `Max` bound, but a prerequisite, a permission or
  a registered kind has no such spelling, which is why the combinator exists.
- **An EMPTY `Not` group shuts the content for everybody**, because a group asking for nothing
  passes for everyone. That is a real requirement rather than an absent one, so `isEmpty` counts it
  and `GateValidator` reports it as `BLANK_REQUIREMENT` - it reads as harmless in the file, which is
  exactly why it has to be said out loud.
- **Four leaves, and a new one is a high bar.** Anything narrower is a registered `GateKind`. Only a
  genuinely universal requirement earns a leaf, because a leaf is a field every author sees forever.
- **The completion probe is settable AFTER the build** on purpose: the usual answer comes from an
  engine that cannot exist until its gates already do, so the engine wires itself in afterwards.
- **Field names are frozen by sharing.** Both lifecycle engines decode the same `Requires` block, so
  a rename here is a rename in every authored file of both.

## Every leaf also has a FACTOR spelling, and both mean one thing

`Factors` is the only leaf a content type outside this module needs: content elsewhere in the
library carries factor conditions and nothing else, so the same requirement has to read identically
written either way. It does, deliberately - by being the same evaluation where that is possible, and
by a pinned agreement where it is not:

| leaf | the same requirement as a factor condition | how they meet |
|---|---|---|
| `Permission: "yourmod.vip"` | `{"Factor": "hytale:permission", "Param": "yourmod.vip", "Min": 1}` | ONE code path: the leaf is evaluated AS the condition |
| `Quests: ["intro_1"]` | `{"Factor": "ziggfreedcommon:quest_completed", "Param": "intro_1", "Min": 1}` | two paths, both asking for the stored status `COMPLETED` (a CLAIMED quest), agreement pinned by a test |
| (no leaf) an earned achievement | `{"Factor": "ziggfreedcommon:achievement_earned", "Param": "first_blood", "Min": 1}` | factor only |
| (no leaf) a level | `{"Factor": "hytale:stat", "Param": "<a mirrored stat channel>", "Min": 30}` | factor only |

### `Permission` IS the factor, spelled short

What an author writes is unchanged - `"Permission": "yourmod.vip"` is still the whole leaf - but
there is no second answer behind it. The evaluator builds
`{"Factor": "hytale:permission", "Param": "<the node>", "Min": 1}` and resolves it through the very
registry every other factor condition goes through, so one permission question has one answer
wherever it is asked: a quest's `Requires`, a storefront's, an NPC placement gate, a dialogue
condition. What follows from that:

- **Nothing is wired for it.** Register the portable `hytale:` standard library as the runtime's
  factor vocabulary and register a factor context carrying the player's store and ref, and the leaf
  works - the ONE evaluator reads both slots live. (On a private evaluator the same two go to
  `GateEvaluator.Builder#factors` / `#factorContext`.) There is no probe to supply and none to
  forget.
- **It fails closed the way the factor does**, which is the same set of refusals as before: no
  vocabulary, nothing registered under `hytale:permission`, no live player behind the subject, a
  subject that is not a player at all, or a blank node. Any of them reads as "cannot tell", and
  cannot-tell never opens a gate.
- **The refusal still names the LEAF.** A `Permission` that shuts a gate reports `"permission"`, not
  `"factor:hytale:permission"`, because the token names what the author wrote and can go and fix.
- **A blank node asks for something and refuses**, rather than reading as an absent requirement.
  `"Permission": ""` names no node anybody can hold; leave the key out to ask for no permission.
- **The id is named as a plain string** (`GateEvaluator.PERMISSION_FACTOR`), never as a reference to
  the class that registers it: that class sits in a module above this one. Nothing is lost, because
  the id is resolved through the registry at evaluation time exactly as an authored id is - which is
  what makes this the same requirement rather than two that happen to agree.

### A completion prerequisite means a CLAIMED quest

Both completion routes ask for the stored status `COMPLETED` and nothing else. A quest sitting in
`COMPLETED_UNCLAIMED` - objectives done, reward not yet taken, which is exactly where a quest
with `Claim` rewards waits - satisfies NEITHER the `Quests` leaf nor the
`ziggfreedcommon:quest_completed` factor. Write either spelling and a player who has finished a quest
but walked away without collecting it is still held back, in both places, until they collect.

That is the stricter of the two possible readings, and it is the one both use so an author never has
to know which spelling they picked. `QuestLifecycle.isFinished` (`COMPLETED || COMPLETED_UNCLAIMED`)
answers a different question - "are the objectives behind them" - and is deliberately not what a
prerequisite consults.

The COUNT reads the same way about a PARKED reward. `ziggfreedcommon:quest_completions` answers how
many times the reward was COLLECTED, so an author writing "come back once you have run this three
times" no longer opens on the third finish, ahead of the third payout. The repeat rules underneath
(`MaxCompletions`, a calendar `Reset` allowance) keep counting FINISHES. No PLAYER can make those two
readings disagree: a parked quest is not offered and `canAccept` refuses it until it has been
collected. A deliberate force can - an accept that skips the check on purpose (a scripted start, an
administrator) or a re-arm that clears the parked status - and counting the FINISH is why that is the
safe half: the run happened and spent its slot, so a reward nobody came back for cannot buy a second
one.

What the count and the flag are NOT is interchangeable, and an author writing "run this three times"
content needs the difference. The flag is a CURRENT status and the count is a LIFETIME tally, so a
repeatable that has come off its cooldown reads 0 on `quest_completed` (its status was re-armed)
while `quest_completions` still reads every run it paid out - that divergence is the accepted
limitation, not a defect. And a ONE-SHOT keeps no completion record at all, so it reads 0 on the
count however many times the flag reads 1: gate a one-shot on the flag.

The two routes ARE separate pieces of code, so their agreement is pinned by a test rather than
assumed (`ProgressionFactorsTest`). The permission pair needs no such pin for correctness and carries
one anyway (`QuestGateTest`), because the day somebody re-grows a second answer here is the day it
should fail the build.
