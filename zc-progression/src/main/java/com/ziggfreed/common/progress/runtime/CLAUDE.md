# `progress/runtime/` - THE shared progression runtime

One `QuestEngine` + `AchievementEngine` pair per server, however many mods contribute to it.

## The rule

There is ONE runtime, always. Nothing decides whether to build it; the only question is which parts
it was built over. A mod does not construct engines and hand them round - it REGISTERS its parts and
reads the pair back from `ProgressionRuntime`.

Two runtimes over two stores is double-tracking: one block break advances two copies of the same
objective, the player sees one of them, and the half a surface reads is not always the half that can
pay out. Registration makes that failure impossible per server rather than merely unlikely per mod.

`QuestEngine.builder()` / `AchievementEngine.builder()` stay public and stay the right tool for a
UNIT TEST and for a consumer that genuinely wants a private engine (a round that dies with the
match). They are the wrong tool for a mod that wants the server's progression.

## The pieces

| file | what it is |
|---|---|
| `ProgressionRuntime` | the holder: registration entry points, the engine pair, content publishing, `ensureBuilt`, the boot diagnostic |
| `ProgressionRegistrar` | what a consumer calls; fluent, idempotent, conflict-policed |
| `ProgressionParts` | the resolved snapshot + the FORWARDERS the engines are built over + gate/tap composition |
| `ProducerClaims` | the keyed claim: which objective kinds a consumer fires, so the library's own generic producer stands down for exactly those |
| `ContentLayers` | per-owner content layers and the merge, so a reload replaces one owner's layer |
| `ProgressionSubjectSource` | how a player becomes the subject the ACTIVE stores understand |
| `ProgressionCallScope` | what a consumer publishes around a mutating call, so a shared surface fires what its own menu would |
| `ProgressionTextSource` | how a surface with no catalogue NAMES a piece of content |
| `ProgressionFactors` | the four `ziggfreedcommon:` READINGS of this runtime, claimed process-wide so any content can gate on finished progression with no Java |

## Three shapes of registration, and they behave differently

- **one-slot** (store, subjects, factor context, scopes, ...): consumer beats library default silently;
  a SECOND consumer is REFUSED with a SEVERE naming both. Never resolve that silently.
- **contribution** (gates, taps, text sources): every registration applies. Gates AND with
  `accepts` collecting EVERY reason (no short-circuit), `preSatisfiedAmount` folding as a MAX; taps
  fan out, each individually guarded; text sources answer in order, first non-null wins.
- **keyed replacement** (`producesKind`): the library's own generic producer stands down
  for exactly that key.

The three shared VOCABULARIES are not registrar methods - `objectiveKinds()`, `rewardKinds()` and
`gateKinds()` hand out the live registries and a consumer registers into them. There is no slot to
conflict over, which is what a registry is for.

## Rules that bite

- **A producer's claim set is written out EXPLICITLY**, never derived from what content may author.
  Those are different questions, and deriving it stands a producer down for a kind nobody fires - the
  progress then simply stops with nothing logged. **The day a fifth generic producer ships, every
  consumer's claim set must be extended in the same change.**
- **A surface asks the runtime for its subject.** Building one locally works on the server it was
  written against and silently drops every write on a server where another mod's store is active.
- **A surface wraps mutating calls in the registered scope**, or a claim from it pays out in silence
  while the same claim from the owning mod's own menu does everything.
- **Sealed parts** (`factors`, both match flavors, the three caps) are read ONCE, when the engines
  are built. A late one is refused, loudly. An identical value is silent, which is why two mods
  agreeing costs nothing.
- **The engines are never rebuilt.** Everything they reach the world through is a forwarder over the
  parts snapshot, so late registration is live AND every cached engine reference stays valid.
- **A factor READ never builds the runtime.** `ProgressionFactors` answers nothing until
  `isBuilt()`, because a gate evaluated early - a placement sweep, a content audit - would otherwise
  seal every sealed part before the consumers that own them had registered. A moment of shut gates
  is recoverable; a boot that moved where player data lives is not.

## The readings, and why they are contributed rather than registered

`ProgressionFactors` turns this runtime into four ordinary factor ids
(`ziggfreedcommon:quest_completed` / `quest_completions` / `achievement_earned` /
`achievement_points`), claimed process-wide through `FactorContributions` by ONE `contribute()` call
from the wiring root. That is the shape because there is one progression per server and any number of
vocabularies reading it: a storefront, a board, an NPC placement, a conversation and a loot roll all
resolve the same ids without a single registration between them, and a consumer that genuinely wants
its own engine answered instead uses `registerInto` on its own registry, which always outranks a
contribution.

Two rules hold them honest, both pinned by `ProgressionFactorsTest`:

- **The reads are NARROW** (`Reads`: six questions, every one an answer about a player). A factor
  read may never reach a mutating engine - the same discipline `QuestStateReader` keeps for a
  conversation - so nothing here can accept, pay out or write.
- **An id nothing knows answers nothing.** Record first, catalogue second, then null. A typo must
  never read as "they have not done it", which would open a bounds-less gate authored to mean "only
  where that content exists". "Finished" is the stored status `COMPLETED` - the quest is done AND its
  reward has been collected - which is the same rule the `Requires` block's `Quests` prerequisite is
  answered by, so the two spellings cannot disagree. A quest waiting in `COMPLETED_UNCLAIMED`
  satisfies neither.

## Where the defaults live

Not here. `zc-objectives`' `ProgressionDefaults` registers the library's own store, subjects, gate,
hand-in probes and text through this same public surface, at library-default rank. This package
therefore knows nothing about them, and there is no module edge in either direction.
