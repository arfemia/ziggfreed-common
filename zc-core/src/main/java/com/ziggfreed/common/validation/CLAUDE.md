# validation/ - the ONE audit-finding vocabulary

`zc-core`. Every content validator in this library reports through these three types, so a consumer
folds findings from several domains into one report without learning a shape per validator.

## The types

- **`Severity`** - `ERROR` / `WARNING` / `INFO`, plus `isProblem()` (ERROR or WARNING).
- **`Finding`** - `(severity, code, message, sourceId, domain)`. Factories come in a domain-less
  form (`Finding.error(code, message, sourceId)`) and a domain-stamped one
  (`Finding.error(domain, code, message, sourceId)`); `withDomain(d)` stamps an unstamped finding
  when a consumer folds it into a wider audit, and NEVER relabels one that already carries a domain.
- **`ValidationReport`** - the counting/printing half: `count`/`errorCount`/`warningCount`/
  `infoCount`/`problemCount`/`hasErrors`/`filter`, `format(label, finding)` +
  `formatAll`, `summarize(label, findings)`, and `logAll(label, findings, errorSink, noteSink)`
  (single-sink overload too). Every sink is a `Consumer<String>` of a finished line, so this package
  needs no logger and no engine type.

## The severity contract (this is the load-bearing part)

**An unknown id is a WARNING, never an ERROR.** Whichever mod owns a factor / kind / gate registers
it at its OWN setup, which may run after the audit and may be a mod the author deliberately expects
some servers not to install. Reporting it as an error makes "this applies only where that mod is
present" - the exact thing the value side exists to express - look broken.

- `ERROR` - cannot work whatever else is installed (a formula that never resolves, an id a save
  format cannot store, a hand-in with nothing to hand in).
- `WARNING` - works only if something outside this file turns up.
- `INFO` - works; the file could just say what it means more plainly (two knobs authored where one
  is read, a redundant entry). Excluded from `problemCount`, so a headline never inflates itself.

## Writing a finding

- A `code` is a STABLE machine token. Consumers filter, suppress and re-tier on it, so renaming one
  is a deliberate break, not a tidy-up.
- A `message` is written for the pack author who has to fix it: say what is wrong AND what it costs
  at runtime. "Names is required" alone never tells anyone why their NPC is missing.
- `sourceId` is the asset id, or a caller-supplied context label (`"mmo_hub.Where"`).

## Who reports into it

`WorldSelectorValidator` (`worldselector`), `NpcPlacementValidator` (`placement`),
`DerivedFactorValidator` (`factor`), `DialogueStructureValidator` (`dialogue`), `LootableValidator`
(`lootable`), `QuestPoolValidator` + `QuestAssetStore`/`QuestGeneratorExpander` (`quest`),
`AchievementPoolValidator` (`achievement`). Each exposes its domain as a `DOMAIN` constant.

## There is one shape, and `Finding` is it

Every validator entry point returns `List<Finding>` and nothing else. A consumer that keeps its own
finding type adopts these rather than asking for a second shape here: read `severity`/`code`/
`message`/`sourceId`, and carry `domain` through so a folded report can still say which family a
line came from. Adding a parallel view type is what makes two validators drift, so do not.
