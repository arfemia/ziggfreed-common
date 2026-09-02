# match/ - the shared name-pattern grammar, specificity ladder + item-identity core

Router for `com.ziggfreed.common.match`. Three pure classes: `NamePattern` + `NameMatchRank` answer
the two questions every "which one of these does this name mean?" surface in the library asks -
**does this pattern match**, and **when several match, which wins** - and `ItemMatch` answers
"does this item satisfy this authored reference?" over the three item-identity routes. Zero engine
types, zero domain vocabulary.

## The grammar

- **[`NamePattern`](NamePattern.java)** - `parse` pre-parses an authored string into a `Kind` plus a
  lower-cased literal `core()`, then `matches` / `matchesExact` / `matchesPartial` test a candidate.
  The five kinds: `"Foo"` exact, `"Foo*"` prefix, `"*Foo"` suffix, `"*Foo*"` contains, `"*"` the
  catch-all (`isDefaultRule()`).
  - **`*` is the only metacharacter, and only at the ends.** The names being matched carry their
    identifying token at the front, the back, or buried in the middle, and those three cases are the
    whole of what an author needs. A regex would cover them too and would also let one authored line
    cost unbounded time on every lookup.
  - **The contains form is the one that reaches a DECORATED name.** An instance world spawns as
    `instance-KweebecNightmare_Chase-<uuid>`; a trailing-`*` prefix cannot reach it because the name
    starts with `instance-`, and a leading-`*` suffix cannot because it ends with a random uuid.
  - **The caller lower-cases the candidate once, not per pattern.** A candidate is normally tested
    against many patterns in a row, so `matches*` take an already-lower-cased string and the pattern's
    own core is lower-cased at parse time. Matching is therefore case-insensitive with no per-rule
    allocation.
  - **It parses and scores; it never SELECTS.** Picking a winner is `NameMatchRank` plus whichever
    consumer owns the candidate list.

## The ladder

- **[`NameMatchRank`](NameMatchRank.java)** - `(band, coreLength, anchorOrdinal)`, most specific
  first: **band 0** a consumer's own axis above every pattern (`abovePatterns()`), **band 1** exact,
  **band 2** partial ordered by literal core length, **band 3** the bare `*`. Comparison is total and
  deterministic (band ASC, core length DESC, anchor ordinal ASC; non-partial bands normalize both
  tie-breakers to 0) and natural order is most-specific-first, so sorting puts the winner at index 0.
  - **Core length dominates anchoring inside band 2**, because the contains form is the only one that
    reaches a decorated name: ranking by anchoring would let a vague `inst*` beat a precise
    `*Forgotten_Temple*`, which is backwards. The second author named the thing; the first guessed at
    its prefix.
  - **`moreSpecific(current, candidate)` keeps the FIRST of two equal ranks**, so where the ladder has
    nothing left to say, authoring order decides and a server owner can read the winner off the files
    rather than off map iteration order.
  - **Band 0 is a reserved rung, not a lever.** Use it for an identifier that cannot be a coincidence
    (a world's `GameplayConfig` machine key), never as a shortcut for "I want this rule to win".

## The item-identity core

- **[`ItemMatch`](ItemMatch.java)** - three route predicates over a candidate item's
  already-resolved identity (its id, its raw tag map, its resource-family ids): `itemId` (exact,
  case-insensitive), `tags` (the shared `TagMatch` map - ANY-of family+values, PLUS the presence
  form where an authored family with an EMPTY value array matches on the key alone, which is
  exactly how the engine's expanded raw-tag map answers a single native tag), and `resourceFamily`
  (any of the candidate's family set). `any` is the OR-composition, parameter-ordered to the
  native consumption precedence (exact > tag > family, `InternalContainerUtilMaterial`).
  - **A route not taken answers false, never true.** Each predicate is false on a null/blank/empty
    required side, and `any` is false when NO route is authored - what a route-less reference
    MEANS (catch-all, match-anything ingredient, closed gate) is the consumer's decision at the
    call site, never this core's.
  - **The consumer keeps its leaf; this core keeps the loops.** A codec's field names, its
    exactly-one-of or any-of rule, and its live identity resolution all stay consumer-side; only
    the comparing lives here, so two consumers can never drift one route apart.

## Consumers

- **[`world/`](../../../../../../../../zc-world/src/main/java/com/ziggfreed/common/world/CLAUDE.md)** -
  `WorldNameMatcher.Pattern` IS a `NamePattern` (it subclasses it so `Pattern.parse` keeps answering
  with the world-side type its callers already speak), and `MatchRank` is a `NameMatchRank` plus the
  one world-specific rung, the `GameplayConfig` band above every pattern.
- **consumer trigger keys** - a `When.Match` naming the block broken or the mob killed reads the
  same grammar (the MMO's `BonusDropsAsset` and mob-scaling's validator name it directly), which is
  why it lives here rather than in the world module: a loot or consumer surface needs no
  `zc-world` edge to speak a pattern.
- **rpg-stations** - its `Ingredient` (recipe rows, step Consume/Produce) and `ActionInput` (held
  -item action selection, custody placement acceptance) both delegate their route answering to
  `ItemMatch`, route-parity-tested on the consumer side so the two leaves can never drift apart.

**Do not extend `NamePattern` to change how a pattern parses or matches.** It is subclassable for
exactly one reason - a consumer that published its own pattern TYPE before the grammar was shared can
keep that name - and a second grammar wearing this type is the thing the class exists to prevent.

## Tests

`NamePatternTest` pins the five kinds, the core extraction (trimmed, lower-cased), each kind's match
contract, the decorated-name case that only contains reaches, and the catch-all.
`NameMatchRankTest` pins the band order, core-length-dominates-anchoring, the anchoring tie-break,
the first-wins-on-tie fold, and the whole ladder exercised as a SELECTION over a rule list, which is
how every consumer actually uses it. `ItemMatchTest` pins each identity route's semantics, the tag
presence form, the route-not-taken false contract, and the `any` OR including the no-route-authored
answer. Pure decision cores: no engine, no server, no balance data.
