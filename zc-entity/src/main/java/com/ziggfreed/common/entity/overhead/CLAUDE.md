# entity/overhead/ - a picture over an entity's head that only some players see

Router for `com.ziggfreed.common.entity.overhead` (module `zc-entity`). A per-viewer, world-space,
entity-anchored visual: any consumer hangs an authored LOOK over any entity for one player, a set
of players, or everyone, and takes it down again, with a lifetime and the look's own offset. It
knows nothing about quests, and nothing about any consumer.

**Built for (the non-quest consumers this exists to serve, beside the quest indicators):** a
zc-encounter boss phase or telegraph cue (`Boss_Enraged` over the boss for everyone in the fight,
`Boss_Targeting_You` over it for the one player it is winding up at), a Kweebec Nightmare hunter or
shrine cue (`Hunter_Near` for the hunted player alone, `Shrine_Lit` for everyone), an
mmo-mob-scaling rarity or affix badge over a creature (shown to everyone, with a lifetime the
creature's own), an rpg-stations station-busy or output-ready icon over a working station
(`Station_Busy` for everyone, `Output_Ready` for the player whose work it is), a shop or trainer
glyph over a merchant. Each is one `show` call and one look file; none of them needs a line here.

## The api, as a consumer sees it

```java
// on the host's world thread; `accessor` is the live Store from a world task, or the
// CommandBuffer you were handed when you are inside a system tick
OverheadIndicators.show(accessor, bossRef, "Boss_Enraged", IndicatorAudience.everyone());
OverheadIndicators.show(accessor, bossRef, "Boss_Targeting_You", IndicatorAudience.of(playerId),
        IndicatorLifetime.forMillis(3_000));
OverheadIndicators.hide(bossRef, IndicatorAudience.everyone());   // takes down the shared cue only
OverheadIndicators.clear(bossRef);                                 // everything over that host
OverheadIndicators.forgetViewer(playerId);                          // a disconnect, any thread
```

- **[`OverheadIndicators`](OverheadIndicators.java)** - the static facade. `show(accessor, hostRef,
  stateId, audience[, lifetime])` records what is wanted and puts the marker entity for that state
  under the host if none stands yet; `hide(hostRef, audience)` takes down exactly what was shown
  that way (a per-viewer cue over a shared one goes back to the shared one); `clear(hostRef)`
  takes down everything; `forgetViewer(uuid)` drops a viewer's own entries over every host in
  every world; `stateShownTo(hostRef, uuid)` is the read. A state nothing describes shows nothing
  and is reported ONCE per id. `followCadenceMs` is the reposition knob (250 ms by default).
  Every engine-touching call is try-guarded to a warning, never a throw into the caller.
- **[`IndicatorAudience`](IndicatorAudience.java)** - one viewer, a set of viewers, or
  `everyone()`. The two shapes LAYER: a viewer's own entry wins over the shared one for as long as
  it lives. Exactly one indicator per host per viewer at a time; which one, when a consumer has
  several reasons, is that consumer's precedence to settle before it calls.
- **[`IndicatorLifetime`](IndicatorLifetime.java)** - `untilHidden()` or `forMillis(n)`; a
  deadline resolved against the clock at show time, and an expired entry is taken down by the
  follow pass, so a timed cue never needs a matching hide.
- **[`OverheadIndicatorAsset`](OverheadIndicatorAsset.java)** - the LOOK for one state, Pattern A,
  at `Server/ZiggfreedCommon/OverheadIndicators/<State_Id>.json` (the state id is the filename,
  `Is_Like_This`, matched without regard to case; a later pack's same-id file replaces it; no owner
  file). Four nested-or-leaf knobs: `Icon` (zc-core's `IconSpec`: an `ItemId` floats as that item
  through `ItemPropEntityService`, a Common-rooted `TexturePath` is drawn on the shipped flat
  CARD), `Scale` (0.5), `Offset {X,Y,Z}` above the top of the host's head (Y 0.45), `Spin` (the
  client's own dropped-item idle motion, true). Registered by `FrameworkAssetRegistrar`, folded by
  [`OverheadIndicatorConfig`](OverheadIndicatorConfig.java).
- **[`OverheadVisibilityFilter`](OverheadVisibilityFilter.java)** - the per-viewer half, inside
  the engine's own find-visible group after `CollectVisible` (the shape the engine's hidden-players
  filter takes): every tick, for each viewer, each marker the viewer is not in the audience of is
  removed from that viewer's `visible` set before anything is sent, so it is never sent at all, and
  the engine's own send-phase diff emits the despawn when it stops being for them. It walks the
  world's few markers, never a viewer's whole set; a world with no marker costs a null check.
- **[`OverheadFollowSystem`](OverheadFollowSystem.java)** - the throttled half: a query-less
  `TickingSystem` (once per world per tick) that on the world's cadence runs ONE
  `OverheadIndicators.pass` with the tick's command buffer (through `Store.forEachChunk`, the way
  zc-cast's `AbstractWorldFrameSystem` gets one): expiries, take-downs of markers no live entry
  names or whose host is gone, missing markers, and the move after a host that walked more than
  `FOLLOW_EPSILON`. Nothing here runs per tick beyond a clock read.
- **[`HostIndicators`](HostIndicators.java)** (package-private, pure) - one host's shared entry,
  per-viewer entries, deadlines and marker refs; **[`OverheadRegistry`](OverheadRegistry.java)**
  - one world's hosts and markers, evicted through `WorldEvictors` when the world unloads;
  **[`OverheadAnchor`](OverheadAnchor.java)** - the placement arithmetic (the anchor is the taller
  of the host's eye height and its bounding box, a human height for a host with neither);
  **[`OverheadLooks`](OverheadLooks.java)** - the two holder builders.
- **Registration is the library's** (`EntityBootstrap.registerOverheadIndicators`: the two
  systems and the eviction). A consumer only calls. The ECS system registry is class-keyed and
  several consumers may show indicators on one server, so a second filter would hide everybody's.

## The marker entity

One transient entity per (host, state), created lazily, shared by every viewer of that state:
`NetworkId` + `TransformComponent` (replication needs nothing more), `Intangible` (out of the melee
and projectile indexes), no `BoundingBox`, no `Interactable`, `NonSerialized` (never saved, so a
restart cannot orphan one), an item look through `ItemPropEntityService.Options` (intangible +
the dropped-item animation choice), a card look through a live `ModelComponent` whose `Model` is
the shipped `Server/Models/Zc_Overhead_Card.json` scaled model with the texture swapped in per
look (the engine writes a model's texture straight into the packet; the bare mesh path is the
fallback when the asset store has no card). Despawned when its audience empties, when its host is
gone, at its deadline, at world unload and at plugin shutdown, always by the follow pass through
its command buffer; a marker removed by anything else is forgotten after a one-second grace and
rebuilt.

## What a play session has to confirm

No client ran during this package's first cycle. Each of these is engine-verified in the shared
source but unseen on a screen: the per-viewer despawn (a marker leaving one viewer's set is sent
as removed to that viewer alone); the card mesh's texture mapping (two crossed 64 by 64 quads at
half stretch, UV locked to the face, so a 64 by 64 PNG should fill it; whether `flat` shading
reads well at night); the dropped-item animation flag (`setOverrideDroppedItemAnimation(false)`
is what lets the client's idle turn and bob play); the anchor height per NPC model (eye height
versus bounding box; a look's `Offset.Y` is the per-state fix); whether a `Model` packet built
over a pack-shipped `ModelAsset` id renders (the bare-path fallback is the second thing to try);
and the 250 ms follow reading smoothly on a walking host (`setPosition` interpolates on the
client; raise the cadence if it stutters).

## Tests

`HostIndicatorsTest` (audience layering, lifetime and expiry, case-insensitive state keys, the
forget), `OverheadAnchorTest` (the anchor rule, the target arithmetic, the epsilon),
`OverheadIndicatorAssetCodecTest` (every leaf, every default, the look-less file). The entity
spawn, the filter and the follow are in-game smoke, like the rest of this module's engine-touching
half.
