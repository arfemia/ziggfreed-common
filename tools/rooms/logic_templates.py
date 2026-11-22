"""Room logic templates: native TriggerVolume entities in vanilla's prefab shape, stamped by build_room.py.

Each template is a function (params, ctx) returning a Logic: the prefab entity documents
({"Components": {"Transform": ..., "TriggerVolume": ...}}, the shape vanilla's own prefabs carry,
Prefabs/Dungeon/Deepworks/Arena_Containment.prefab.json) and the blocks it stands on (a cell to a palette slot), which
the builder places like any prop. Only native Types are used (TriggerVolumesPlugin.java:538-580), plus CommonLib's own
registered into the same codecs: ZigRequires and ZigHasEffect (conditions), ZigCheckpoint, ZigRunMilestone,
ZigRewardChest and ZigFeedback (effects).

Rules every template keeps (procgen-dungeon.md section 3), and test_rooms.py checks:
- A key one volume uses to find another (a MatchKey, and the tag key it looks for) ends `@room`, so the room-logic
  placer can rewrite it to the room's own token; a volume's own state (State, Lit, Busy) is read only by itself.
- No position is world-absolute: block writes use `Origin: VolumeOrigin` offsets, a teleport `RelativeToVolume`.
- A tag fires only on a change, so a state machine walks distinct values, and a reset writes a different value
  before the one it returns to.
- Text players read is a lang key the caller passes in (HintKey, TitleKey); a template never spells it. A puzzle
  template's optional HintKey stamps a RoomHint: the room tells each player that line once, as they come in, so the
  puzzle is taught where it is played.

Coordinates in and out are anchor-relative block cells; a volume's Transform sits on the corner cell of its box, so
its Box shape runs from (0, 0, 0) to the box size and every offset below is a whole number of blocks.

How the engine runs these (TriggerVolumeTickingSystem.java:485-580, 929-957): an event's conditions and effects pair up
by (Event, Entry); every condition of the pair must pass, else the pair's RejectionEffects run. A TICK effect fires for
each tracked entity, at most once per its Interval. SIGNAL_RECEIVED and TAG_ADDED carry the signal's or the changed
tag's key and value, read with a TagCondition whose Source is Event.
"""
import copy
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import prefabio as pio  # noqa: E402

ROOM = "@room"
RUN = "@run"
CENTER_VOLUME = "Volume"
EMPTY = pio.EMPTY_BLOCK
VARIANT_KEY = "ZigVariant"
WHERE_KEY = "ZigWhere"

# How far a room's signals and tag writes reach, in blocks: a room fits well inside it, and the room-scoped keys keep
# a neighbour from answering.
REACH = 64.0
# Seconds between a tag write and the signal that asks another volume to read it, so the read sees the write.
SETTLE = 0.1
# A room hint's per-player cooldown, longer than any run: a volume's Cooldown is kept per entity and survives the
# entity leaving (VolumeEntry.isOnCooldown, clearEntityRuntimeState keeps it), and a run's room logic is placed once
# and removed at its end, so each player hears the line once per run.
HINT_ONCE = 86400.0


class BlockRef(str):
    """A block id as a volume names it, carrying the block state it means (blank for the block's default): a lit
    brazier is the brazier block in its On state. The prefab writes the state's own block, *<Id>_State_Definitions_
    <State>, as vanilla's prefabs do; an effect writes the id and the state apart (ReplaceBlockTypeEffect.java:
    234-265 resolves a state through the block's own definitions)."""

    def __new__(cls, block, state=""):
        ref = str.__new__(cls, block)
        ref.state = state or ""
        return ref


def state_of(block):
    return getattr(block, "state", "")


def prefab_name(block, state=""):
    """The block a prefab lists for `block` in `state`."""
    return "*%s_State_Definitions_%s" % (block, state) if state else block


class Logic:
    """What a template returns: its volume entities and the blocks it stands on (cell -> palette slot)."""

    def __init__(self, entities=(), blocks=None):
        self.entities = list(entities)
        self.blocks = dict(blocks or {})


# ---------------------------------------------------------------------------------------------------------------
# Shapes every template shares


def vec(x, y, z):
    return {"X": float(x), "Y": float(y), "Z": float(z)}


def cell_of(d):
    return pio.xyz_tuple(d)


def cells_of(lst):
    return [pio.xyz_tuple(d) for d in lst]


def region(d):
    """A {Min, Max} dict as (lo, hi) cells, each axis ordered."""
    a, b = pio.xyz_tuple(d["Min"]), pio.xyz_tuple(d["Max"])
    return (min(a[0], b[0]), min(a[1], b[1]), min(a[2], b[2])), (max(a[0], b[0]), max(a[1], b[1]), max(a[2], b[2]))


def box_size(lo, hi):
    return (hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1)


def box_cells(lo, hi):
    """Every cell of the box lo..hi."""
    return [(x, y, z) for y in range(lo[1], hi[1] + 1) for z in range(lo[2], hi[2] + 1)
            for x in range(lo[0], hi[0] + 1)]


def volume_entity(name, corner, size, effects, conditions=(), tags=None, targets=("Player",), rejections=(),
                  **settings):
    """A TriggerVolume entity whose Box covers `size` cells from the `corner` cell. `settings` are the component's
    own optional keys (CancelDelayedOnExit, Cooldown, ...)."""
    tv = {
        "Shape": {"Type": "Box", "Min": vec(0, 0, 0), "Max": vec(*size)},
        "Effects": list(effects),
        "Enabled": True,
        "TargetTypes": list(targets),
        "KeepLoaded": False,
        "Name": name,
    }
    if conditions:
        tv["Conditions"] = list(conditions)
    if rejections:
        tv["RejectionEffects"] = list(rejections)
    if tags:
        tv["Tags"] = dict(tags)
    tv.update(settings)
    return {"Components": {
        "Transform": {"Position": vec(*corner), "Rotation": {"Pitch": 0.0, "Yaw": 0.0, "Roll": 0.0}},
        "TriggerVolume": tv,
    }}


def cell_volume(name, cell, effects, conditions=(), tags=None, **kw):
    """A volume over one block cell (a lever, a brazier, a rune): block events at that cell reach it."""
    return volume_entity(name, cell, (1, 1, 1), effects, conditions, tags, **kw)


def effect(type_, event, entry=0, delay=0.0, interval=0.0, **fields):
    e = {"Type": type_, "Event": event, "Interval": float(interval), "Delay": float(delay)}
    if entry:
        e["Entry"] = entry
    e.update(fields)
    return e


def condition(type_, event, entry=0, **fields):
    c = {"Type": type_, "Event": event}
    if entry:
        c["Entry"] = entry
    c.update(fields)
    return c


def _tag_condition(event, source, key, value, entry, comparison="Exactly", inverted=False, match_key="",
                   radius=50.0):
    return condition("TagCondition", event, entry, Source=source, TagKey=key, Comparison=comparison,
                     CompareAgainst="Value", TagValue=value, Inverted=inverted, MatchKey=match_key, MatchValue="",
                     Radius=radius, Center=CENTER_VOLUME)


def tag_is(event, key, value, entry=0, inverted=False):
    """The volume's own tag `key` holds exactly `value` (or, inverted, does not)."""
    return _tag_condition(event, "Self", key, value, entry, inverted=inverted)


def event_tag_is(event, key, value="", entry=0, comparison="Exactly"):
    """The event carries `key` (a signal's key, or the tag a TAG_ADDED changed), with `value` when one is given."""
    return _tag_condition(event, "Event", key, value, entry, comparison=comparison)


def all_tagged(event, match_key, key, value, entry=0, radius=REACH):
    """Every volume carrying `match_key` within `radius` has its tag `key` at exactly `value` (and there is one)."""
    return _tag_condition(event, "Radius", key, value, entry, match_key=match_key, radius=radius)


def set_own_tag(event, key, value, entry=0, delay=0.0):
    """Set a tag on this volume only (a blank MatchKey targets the triggering volume)."""
    return effect("ModifyTags", event, entry, delay, Operation="Set", TagKey=key, TagValue=value, MatchKey="",
                  MatchValue="", Radius=50.0, Center=CENTER_VOLUME)


def set_tags(event, match_key, key, value, entry=0, delay=0.0):
    """Set a tag on every volume carrying `match_key` within the room's reach."""
    return effect("ModifyTags", event, entry, delay, Operation="Set", TagKey=key, TagValue=value, MatchKey=match_key,
                  MatchValue="", Radius=REACH, Center=CENTER_VOLUME)


def send_signal(event, match_key, keys, values=(), entry=0, delay=0.0, interval=0.0, match_value=""):
    """Raise SIGNAL_RECEIVED on every volume carrying `match_key` (and `match_value`, when given) within reach; the
    signal carries `keys` and their `values` for the receiver's Event conditions."""
    fields = {"MatchKey": match_key, "MatchValue": match_value, "Radius": REACH, "Center": CENTER_VOLUME,
              "SignalKeys": list(keys)}
    if values:
        fields["SignalValues"] = [str(v) for v in values]
    return effect("SendSignal", event, entry, delay, interval, **fields)


def offset_of(origin, cell):
    return (cell[0] - origin[0], cell[1] - origin[1], cell[2] - origin[2])


def swap_cells(event, origin, lo, hi, from_blocks, to_block, entry=0, delay=0.0):
    """Replace `from_blocks` with `to_block` over the cells lo..hi, offset from the volume's corner cell `origin`.

    Bounds Aabb spans floor(origin + Offset) to floor(origin + Offset + size) (ReplaceBlockTypeEffect.java:458-471),
    so a size of (cells - 1) + 0.5 covers exactly the cells asked for. A BlockRef's state rides along: a blank
    FromBlockState matches the block's default state only."""
    size = [hi[i] - lo[i] + 0.5 for i in range(3)]
    from_state = state_of(from_blocks[0]) if len(from_blocks) == 1 else ""
    return effect("ReplaceBlockType", event, entry, delay, FromBlockTypes=[str(b) for b in from_blocks],
                  FromBlockState=from_state, ToBlockType=str(to_block), ToBlockState=state_of(to_block),
                  Bounds="Aabb", X=size[0], Y=size[1], Z=size[2], Offset=vec(*offset_of(origin, lo)),
                  Origin="VolumeOrigin", Rotation="None", Pitch="None", Roll="None")


def swap_cell(event, origin, cell, from_block, to_block, entry=0, delay=0.0):
    return swap_cells(event, origin, cell, cell, [from_block], to_block, entry, delay)


def place_block(event, origin, cell, block, entry=0, delay=0.0):
    """Put `block` at `cell` only where the world holds air there (the block's centre, offset from the volume's corner
    cell; PlaceBlockEffect.java:106-147 floors it), so a write never lands on a block the room already has."""
    off = offset_of(origin, cell)
    return effect("PlaceBlock", event, entry, delay, BlockType=str(block), BlockState=state_of(block),
                  Position=vec(off[0] + 0.5, off[1] + 0.5, off[2] + 0.5), Origin="VolumeOrigin",
                  ReplaceMode="OnlyAir", Rotation="None", Pitch="None", Roll="None")


def milestone(event, milestone_id, entry=0, delay=0.0, placement="", player_only=False):
    """CommonLib's ZigRunMilestone: tells the run a beat happened (once per room per run, or once per run under
    `placement`, which a key ending @room keeps to its room). With `player_only` it credits only the player who set
    the volume off (PlayerOnly), not every member in the run's area."""
    fields = {"Id": milestone_id}
    if placement:
        fields["Placement"] = placement
    if player_only:
        fields["PlayerOnly"] = True
    return effect("ZigRunMilestone", event, entry, delay, **fields)


def chest_effect(event, origin, params, entry=0, delay=0.0):
    """CommonLib's ZigRewardChest: the reward chest file params["Chest"] rises once per run for every member in the
    run's area, its foot on the floor middle of the cell params["At"] (offset from the volume's corner cell `origin`).
    OpenerLootable and OpenerTitleKey give whoever set the volume off a reward of their own in it; Placement names what
    it rises once per run under."""
    off = offset_of(origin, cell_of(params["At"]))
    fields = {"Chest": params["Chest"], "Offset": vec(off[0] + 0.5, off[1], off[2] + 0.5)}
    for key in ("OpenerLootable", "OpenerTitleKey", "Placement"):
        if params.get(key):
            fields[key] = params[key]
    return effect("ZigRewardChest", event, entry, delay, **fields)


def message(event, key, entry=0):
    """A chat line to the player who set the volume off: `key` is a lang key the caller passes in."""
    return effect("SendMessage", event, entry, Message=key, Recipient="TriggeringPlayer")


def cue(event, params, prefix, origin, cell, entry=0, delay=0.0):
    """The optional sound and particles a caller names as <prefix>Sound and <prefix>Vfx (drawn <prefix>VfxScale times
    its own size; default 1), at `cell`."""
    out = []
    off = offset_of(origin, cell)
    centre = vec(off[0] + 0.5, off[1] + 0.5, off[2] + 0.5)
    if params.get(prefix + "Sound"):
        out.append(effect("PlaySound", event, entry, delay, SoundEvent=params[prefix + "Sound"],
                          Location="VolumeCenter", Offset=centre))
    if params.get(prefix + "Vfx"):
        out.append(effect("PlayVfx", event, entry, delay, ParticleSystem=params[prefix + "Vfx"], Anchor="Volume",
                          Offset=centre, Scale=float(params.get(prefix + "VfxScale", 1.0)), Rotation=vec(0, 0, 0),
                          Duration=0.0))
    return out


def scoped(key, what):
    """Refuse a key that would reach past its room."""
    if not (key.endswith(ROOM) or key.endswith(RUN)):
        raise ValueError("%s %r must end %s (or %s) so it stays inside its room" % (what, key, ROOM, RUN))
    return key


def variant_tag(group, n):
    """The tag the room-logic placer keeps only for the variant the run picks: `<Group>:<n>`, n from 1."""
    return {VARIANT_KEY: "%s:%d" % (group, n)}


def where_tag(requires):
    """The tag the room-logic placer keeps only on a server where the world-level Requires block `requires` holds
    (CommonLib's RoomWhere), written as compact JSON, since a volume's tags are strings."""
    if not isinstance(requires, dict) or not requires:
        raise ValueError("a Where is a Requires block: {\"Factors\": [...]} and the like")
    return {WHERE_KEY: json.dumps(requires, separators=(",", ":"), sort_keys=True)}


def solved(params, ctx, event, origin, entry=0, delay=0.0, kind=""):
    """What a finished puzzle does, from its params: Opens (indices into the room's doors, each authored Closed with
    Bars) swap their bars out; Signal (a key ending @room) is told `Solved`; Milestone (default Puzzle_Solved, "" for
    none) counts the beat for the run; KindMilestone (default the template's own `kind`, such as Puzzle_Rune_Order; ""
    for none) counts a second beat naming which kind of puzzle it was."""
    out = []
    opens = params.get("Opens", [])
    bars = ctx["slot"](params.get("Bars", "Bars")) if opens else None
    for i in opens:
        d = ctx["doors"][i]
        out.append(swap_cells(event, origin, d["OpeningMin"], d["OpeningMax"], [bars], EMPTY, entry, delay))
    if params.get("Signal"):
        out.append(send_signal(event, scoped(params["Signal"], "Signal"), ["Solved"], entry=entry, delay=delay))
    ms = params.get("Milestone", "Puzzle_Solved")
    if ms:
        out.append(milestone(event, ms, entry, delay))
    km = params.get("KindMilestone", kind)
    if km:
        out.append(milestone(event, km, entry, delay))
    return out


def controller_cell(params, fallback):
    return cell_of(params["Controller"]) if params.get("Controller") else fallback


def hint_volumes(params, ctx, region_param="HintRegion"):
    """The room hint a template's HintKey asks for: a volume over params[region_param] {Min, Max}, else the room's
    Box (ctx["box"]), whose ENTER tells the triggering player the line, once per player per run (HINT_ONCE). [] when
    there is no HintKey."""
    key = params.get("HintKey")
    if not key:
        return []
    if params.get(region_param):
        lo, hi = region(params[region_param])
    elif ctx.get("box"):
        lo, hi = ctx["box"]
    else:
        raise ValueError("HintKey: give %s, the region the hint is said in (the room has no Box)" % region_param)
    return [volume_entity(ctx["name"] + "Hint", lo, box_size(lo, hi), [message("ENTER", key)], Cooldown=HINT_ONCE)]


def all_at_once(params, ctx, label, cell, key, value, signal_key, member_key, tag, want, also=(), kind=""):
    """The controller every all-of-them-at-once puzzle shares (braziers, plates, levers): a volume at `cell`, carrying
    `key`, that on a signal carrying `signal_key`, while it is Ready and every volume carrying `member_key` has `tag` at
    `want`, writes Solved, runs `also`, then what solved() makes of the params (its kind milestone `kind`)."""
    effects = [set_own_tag("SIGNAL_RECEIVED", "State", "Solved")] + list(also)
    effects += solved(params, ctx, "SIGNAL_RECEIVED", cell, kind=kind)
    conditions = [event_tag_is("SIGNAL_RECEIVED", signal_key),
                  tag_is("SIGNAL_RECEIVED", "State", "Ready"),
                  all_tagged("SIGNAL_RECEIVED", member_key, tag, want)]
    return cell_volume(ctx["name"] + label, cell, effects, conditions, {key: value, "State": "Ready"})


# ---------------------------------------------------------------------------------------------------------------
# The shared pulse (every hazard): a controller sends Fire on a rhythm, each cell warns, burns, then rests


FIRE = "Fire"


def harm(params, defaults):
    """The TICK effects a hot cell runs on whoever stands in it: Damage (Amount, as DamageMode, default PercentMax),
    an entity Effect for EffectSeconds, and a Push velocity in the room's frame (the engine turns it with the room)."""
    p = dict(defaults)
    p.update({k: v for k, v in params.items() if k in ("Damage", "DamageMode", "Effect", "EffectSeconds", "Push",
                                                         "HarmInterval")})
    every = float(p.get("HarmInterval", 0.5))
    out = []
    if p.get("Damage"):
        out.append(effect("DamageEntity", "TICK", interval=every, Mode=p.get("DamageMode", "PercentMax"),
                          Amount=float(p["Damage"])))
    if p.get("Effect"):
        out.append(effect("EntityEffect", "TICK", interval=every, Effect=p["Effect"], Mode="Apply",
                          Duration=float(p.get("EffectSeconds", 3.0))))
    if p.get("Push"):
        push = p["Push"]
        out.append(effect("SetVelocity", "TICK", Velocity=vec(push["X"], push["Y"], push["Z"]), Additive=False,
                          RelativeMode="Absolute"))
    return out


def pulse_sequence(event, params, origin, at, warn, burst, entry=1):
    """What a hazard cell does once it fires: Busy Yes and the Warn cue now, Hot Yes and the Burst cue `warn` seconds
    on, Hot No `burst` seconds after that, and Busy No a moment later. Busy and Hot walk Yes and No, a change on every
    write; a TICK conditioned on Hot Yes is what hurts. The cues sit at `at`, offset from the volume's corner `origin`."""
    end = warn + burst
    effects = [set_own_tag(event, "Busy", "Yes", entry=entry)]
    effects += cue(event, params, "Warn", origin, at, entry=entry)
    effects += [set_own_tag(event, "Hot", "Yes", entry=entry, delay=warn)]
    effects += cue(event, params, "Burst", origin, at, entry=entry, delay=warn)
    effects += [set_own_tag(event, "Hot", "No", entry=entry, delay=end),
                set_own_tag(event, "Busy", "No", entry=entry, delay=end + SETTLE)]
    return effects


def pulse_cell(name, lo, hi, key, value, params, harm_effects, warn, burst):
    """One hazard cell. A Fire signal arms it unless it is already busy (several players' controllers can ask at once,
    as in vanilla's Quadrant cells): it warns for `warn` seconds, is hot for `burst` seconds, then rests
    (pulse_sequence)."""
    effects = pulse_sequence("SIGNAL_RECEIVED", params, lo, lo, warn, burst)
    effects += harm_effects
    conditions = [
        event_tag_is("SIGNAL_RECEIVED", FIRE, entry=1),
        tag_is("SIGNAL_RECEIVED", "Busy", "Yes", entry=1, inverted=True),
        tag_is("TICK", "Hot", "Yes"),
    ]
    tags = {key: value, "Busy": "No", "Hot": "No"}
    return volume_entity(name, lo, box_size(lo, hi), effects, conditions, tags, targets=params.get(
        "Targets", ["Player", "Npc"]))


def pulse_controller(name, lo, hi, period, phases):
    """The rhythm: while a player stands in lo..hi, every `period` seconds it sends Fire to each phase's cells
    (match key, match value, delay into the period), as vanilla's Quadrant_Controller does; leaving cancels what it
    has queued."""
    effects = [send_signal("TICK", key, [FIRE], match_value=value, delay=delay, interval=period)
               for key, value, delay in phases]
    return volume_entity(name, lo, box_size(lo, hi), effects, CancelDelayedOnExit=True)


def pulse_hazard(params, ctx, cells_key, label, default_key, phase_of, warn, burst, harm_defaults):
    """A hazard on the shared pulse: params[cells_key] lists the cells' regions {Min, Max}; phase_of(i, n, period)
    gives cell i's delay into the period; Region {Min, Max} is where the rhythm runs (a player inside keeps it going)."""
    key = scoped(params.get("Key", default_key), "Key")
    cells = [region(c) for c in params[cells_key]]
    period = float(params.get("Period", 6.0))
    warn = float(params.get("Warn", warn))
    burst = float(params.get("Burst", burst))
    if warn + burst + SETTLE >= period:
        raise ValueError("%s: Warn + Burst must be shorter than Period, so a cell rests before it fires again" % label)
    effects = harm(params, harm_defaults)
    out = Logic()
    phases = []
    for i, (lo, hi) in enumerate(cells):
        out.entities.append(pulse_cell("%s%s_%d" % (ctx["name"], label, i + 1), lo, hi, key, str(i + 1), params,
                                       effects, warn, burst))
        phases.append((key, str(i + 1), round(phase_of(i, len(cells), period), 3)))
    rlo, rhi = region(params["Region"])
    out.entities.append(pulse_controller(ctx["name"] + label + "_Rhythm", rlo, rhi, period, phases))
    return out


# ---------------------------------------------------------------------------------------------------------------
# Templates: combat and doors


def sealed_combat(params, ctx):
    """The sealed combat room: entering seals the listed doorways with bars and wakes the room's manual spawn markers;
    once no living NPC is left inside, the bars drop, the room says so with a signal and the run counts Room_Cleared.

    params: Interior {Min, Max} (the cells the fight happens in), Doors (indices into the spec's Doors; default all),
    Markers (spawn marker ids TriggerNpcMarkers wakes; default every manual marker inside), ArmDelay (seconds before the
    clear check starts, so a slow spawn cannot open the room at once; default 1.5), Interval (seconds between clear
    checks; default 0.5), ClearedSignal (the MatchKey told when the room is cleared; default Cleared@room), Milestone
    (default Room_Cleared, "" for none).
    ctx: doors (the spec's doors with their opening cells), slot (palette lookup), name prefix."""
    lo, hi = region(params["Interior"])
    doors = [ctx["doors"][i] for i in params.get("Doors", range(len(ctx["doors"])))]
    bars = ctx["slot"](params.get("Bars", "Bars"))
    arm = float(params.get("ArmDelay", 1.5))
    interval = float(params.get("Interval", 0.5))
    cleared = scoped(params.get("ClearedSignal", "Cleared" + ROOM), "ClearedSignal")
    effects = []
    for d in doors:
        effects.append(swap_cells("ENTER", lo, d["OpeningMin"], d["OpeningMax"], [EMPTY], bars, entry=1))
    effects.append(set_own_tag("ENTER", "State", "Arming", entry=1))
    markers = params.get("Markers") or [None]
    for m in markers:
        fields = {"Range": 0.0, "MatchTag": "", "Radius": 0.0, "Center": CENTER_VOLUME}
        if m:
            fields["MarkerType"] = m
        effects.append(effect("TriggerNpcMarkers", "ENTER", 1, **fields))
    effects.append(set_own_tag("ENTER", "State", "Sealed", entry=1, delay=arm))
    for d in doors:
        effects.append(swap_cells("TICK", lo, d["OpeningMin"], d["OpeningMax"], [bars], EMPTY, entry=2))
    effects.append(set_own_tag("TICK", "State", "Cleared", entry=2))
    effects.append(send_signal("TICK", cleared, ["Room_Cleared"], entry=2))
    ms = params.get("Milestone", "Room_Cleared")
    if ms:
        effects.append(milestone("TICK", ms, entry=2))
    for e in effects:
        if e["Event"] == "TICK":
            e["Interval"] = interval
    conditions = [
        tag_is("ENTER", "State", "Ready", entry=1),
        tag_is("TICK", "State", "Sealed", entry=2),
        condition("EntityCountCondition", "TICK", 2, Comparison="Exactly", Count=0),
    ]
    tags = {"State": "Ready", "Combat" + ROOM: "Arena"}
    return Logic([volume_entity(ctx["name"] + "Combat", lo, box_size(lo, hi), effects, conditions, tags)])


def lever_door(params, ctx):
    """The lever door: a doorway the spec closes with bars opens when a player uses the lever beside it, once.

    params: Lever {X, Y, Z} (the lever block's cell; the spec's Props place the block), Door (index into the spec's
    Doors; that door must be authored Closed), Key (the tag key the door listens on; default Door@room).
    The lever's volume covers the lever cell and the cell above it, the door's volume the bars."""
    lever = cell_of(params["Lever"])
    door = ctx["doors"][params["Door"]]
    key = scoped(params.get("Key", "Door" + ROOM), "Key")
    bars = ctx["slot"](params.get("Bars", "Bars"))
    lever_volume = volume_entity(
        ctx["name"] + "Lever", lever, (1, 2, 1),
        [send_signal("BLOCK_USED", key, ["Open"]),
         set_own_tag("BLOCK_USED", "State", "Pulled")],
        [tag_is("BLOCK_USED", "State", "Ready")],
        {"State": "Ready"})
    lo, hi = door["OpeningMin"], door["OpeningMax"]
    door_volume = volume_entity(
        ctx["name"] + "Door", lo, box_size(lo, hi),
        [swap_cells("SIGNAL_RECEIVED", lo, lo, hi, [bars], EMPTY)],
        (), {key: "Door"})
    return Logic([lever_volume, door_volume])


def teleport_to(event, lo, cell, yaw, entry=0):
    """Put whoever set the volume off on `cell`'s floor, facing `yaw`, relative to the volume's corner `lo`."""
    pos = vec(cell[0] - lo[0] + 0.5, cell[1] - lo[1], cell[2] - lo[2] + 0.5)
    return effect("Teleport", event, entry, Position=pos, World="", ResetVelocity=True, RelativeToEntity=False,
                  RelativeToVolume=True, UseRotation=True, Rotation=vec(yaw, 0.0, 0.0))


def fall_rescue(params, ctx):
    """The fall rescue: a player who drops into the region is put back at the return cell, facing the way given, and
    optionally loses a share of their health and is told why. Optionally, creatures that fall in too: one wearing
    CreatureMark is set back on CreatureReturn (a boss that must never leave its arena), any other dies.

    params: Region {Min, Max} (cells), Return {X, Y, Z} (the cell the player's feet land in), Yaw (degrees; 0 faces
    North, the engine's convention; default 0), Damage (a percent of the player's maximum health the fall costs;
    default none), HintKey (a lang key the fallen player is told; default none), CreatureReturn {X, Y, Z} and
    CreatureMark (an effect id; both or neither), CreatureYaw (default Yaw).
    The teleport's Rotation carries the yaw in X: TeleportEffect.toRotation builds Rotation3f(pitch = Y, yaw = X,
    roll = Z) (TeleportEffect.java:133-138, Rotation3f.java:103), as vanilla's Deepworks fall teleports author it; the
    engine's Teleport moves any entity with a transform, so the creature branch is the same effect."""
    lo, hi = region(params["Region"])
    yaw = float(params.get("Yaw", 0.0))
    effects = []
    if params.get("Damage"):
        effects.append(effect("DamageEntity", "ENTER", Mode="PercentMax", Amount=float(params["Damage"])))
    effects.append(teleport_to("ENTER", lo, cell_of(params["Return"]), yaw))
    if params.get("HintKey"):
        effects.append(message("ENTER", params["HintKey"]))
    out = Logic([volume_entity(ctx["name"] + "Rescue", lo, box_size(lo, hi), effects)])
    if bool(params.get("CreatureReturn")) != bool(params.get("CreatureMark")):
        raise ValueError("FallRescue: give CreatureReturn and CreatureMark together, or neither")
    if params.get("CreatureReturn"):
        mark = params["CreatureMark"]
        back = teleport_to("ENTER", lo, cell_of(params["CreatureReturn"]), float(params.get("CreatureYaw", yaw)),
                           entry=1)
        kill = effect("DamageEntity", "ENTER", 2, Mode="PercentMax", Amount=100.0)
        conditions = [condition("ZigHasEffect", "ENTER", 1, Effect=mark),
                      condition("ZigHasEffect", "ENTER", 2, Effect=mark, Wears=False)]
        out.entities.append(volume_entity(ctx["name"] + "Rescue_Creature", lo, box_size(lo, hi), [back, kill],
                                          conditions, targets=("Npc",)))
    return out


# ---------------------------------------------------------------------------------------------------------------
# Templates: puzzles


def brazier_relay(params, ctx):
    """The brazier relay: light every brazier before the first burns out. Using an unlit brazier lights it for Burn
    seconds; the moment all are lit together the puzzle is solved and they stay lit.

    params: Braziers [{X, Y, Z}] (usable brazier cells; the template places them), Burn (seconds; default 15),
    Unlit and Lit (palette slots; default Brazier and Brazier_Lit), Key (the relay's key; default Relay@room),
    BrazierKey (default Brazier@room), Controller {X, Y, Z} (default the cell over the first brazier), and the
    solved params (Opens, Signal, Milestone), LightSound and LightVfx, HintKey and HintRegion (a room hint).
    A brazier walks Fuel Burning and Cold: lighting writes Burning, Burn seconds on writes Cold, and Cold puts it out
    unless the relay is solved."""
    braziers = cells_of(params["Braziers"])
    burn = float(params.get("Burn", 15.0))
    unlit_slot, lit_slot = params.get("Unlit", "Brazier"), params.get("Lit", "Brazier_Lit")
    unlit, lit = ctx["slot"](unlit_slot), ctx["slot"](lit_slot)
    key = scoped(params.get("Key", "Relay" + ROOM), "Key")
    brazier_key = scoped(params.get("BrazierKey", "Brazier" + ROOM), "BrazierKey")
    out = Logic()
    for i, c in enumerate(braziers):
        out.blocks[c] = unlit_slot
        effects = [
            set_own_tag("BLOCK_USED", "Lit", "Yes"),
            set_own_tag("BLOCK_USED", "Fuel", "Burning"),
            swap_cell("BLOCK_USED", c, c, unlit, lit),
            send_signal("BLOCK_USED", key, ["Lit"], delay=SETTLE),
            set_own_tag("BLOCK_USED", "Fuel", "Cold", delay=burn),
        ] + cue("BLOCK_USED", params, "Light", c, c) + [
            set_own_tag("TAG_ADDED", "Lit", "No", entry=1),
            swap_cell("TAG_ADDED", c, c, lit, unlit, entry=1),
        ]
        conditions = [
            tag_is("BLOCK_USED", "Lit", "No"),
            event_tag_is("TAG_ADDED", "Fuel", "Cold", entry=1),
            tag_is("TAG_ADDED", "Solved", "Yes", entry=1, inverted=True),
        ]
        tags = {brazier_key: "Brazier", "Lit": "No", "Fuel": "Cold", "Solved": "No"}
        out.entities.append(cell_volume("%sBrazier_%d" % (ctx["name"], i + 1), c, effects, conditions, tags))
    ctl = controller_cell(params, (braziers[0][0], braziers[0][1] + 1, braziers[0][2]))
    out.entities.append(all_at_once(params, ctx, "Relay", ctl, key, "Relay", "Lit", brazier_key, "Lit", "Yes",
                                    also=[set_tags("SIGNAL_RECEIVED", brazier_key, "Solved", "Yes")],
                                    kind="Puzzle_Brazier_Relay"))
    out.entities.extend(hint_volumes(params, ctx))
    return out


def carried_core_source(params, ctx):
    """The carried core's source, in a room off the path: a carryable core block a player picks up and carries to its
    socket. Taking it starts a timer; Restock seconds later a new core stands there (only where the cell is air), so a
    core dropped in a pit or lost on a death is never the end of the run.

    params: At {X, Y, Z} (the core's cell; the template places it), Core (palette slot; default Core), Restock
    (seconds; default 20), HintKey and HintRegion (a room hint). The core block must be one a player can carry
    (vanilla's carryable blocks)."""
    c = cell_of(params["At"])
    core_slot = params.get("Core", "Core")
    core = ctx["slot"](core_slot)
    restock = float(params.get("Restock", 20.0))
    effects = [
        set_own_tag("BLOCK_BROKEN", "Stock", "Taken"),
        set_own_tag("BLOCK_BROKEN", "Stock", "Restock", delay=restock),
        place_block("TAG_ADDED", c, c, core, entry=1),
        set_own_tag("TAG_ADDED", "Stock", "Full", entry=1, delay=SETTLE),
    ]
    conditions = [
        tag_is("BLOCK_BROKEN", "Stock", "Full"),
        event_tag_is("TAG_ADDED", "Stock", "Restock", entry=1),
    ]
    return Logic([cell_volume(ctx["name"] + "Core_Source", c, effects, conditions, {"Stock": "Full"})]
                 + hint_volumes(params, ctx), {c: core_slot})


def carried_core_socket(params, ctx):
    """The carried core's socket: a player who steps into Region carrying the core gives it up (ItemCondition CARRIED
    with Consume), the core appears on its pedestal and the puzzle is solved.

    params: Region {Min, Max} (where a carrier must stand), Socket {X, Y, Z} (the cell the core appears in, air
    until then), Core (palette slot; default Core), Item (the carried item's id; default the Core slot's block),
    HintKey (a lang key said to a player who arrives without it while the socket is still empty; optional: unlike
    the other puzzles' HintKey, this one is said at the socket, each time, rather than once in the room), and the
    solved params. Entry 0 fills the socket; entry 1, only while it is Empty and the player carries no core, says the
    hint, so nobody is told to fetch a core the socket already holds."""
    lo, hi = region(params["Region"])
    socket = cell_of(params["Socket"])
    core = ctx["slot"](params.get("Core", "Core"))
    item = params.get("Item", str(core))

    def carried(entry, comparison, consume):
        return condition("ItemCondition", "ENTER", entry, Item=item, Location="Carried", Quantity=1, Consume=consume,
                         Comparison=comparison, EmptyInventory=False, MetadataKey="", MetadataValue="")

    effects = [set_own_tag("ENTER", "State", "Filled"), place_block("ENTER", lo, socket, core)]
    effects += cue("ENTER", params, "Fill", lo, socket) + solved(params, ctx, "ENTER", lo, kind="Puzzle_Carried_Core")
    conditions = [tag_is("ENTER", "State", "Empty"), carried(0, "AtLeast", True)]
    if params.get("HintKey"):
        effects.append(message("ENTER", params["HintKey"], entry=1))
        conditions += [tag_is("ENTER", "State", "Empty", entry=1), carried(1, "LessThan", False)]
    return Logic([volume_entity(ctx["name"] + "Core_Socket", lo, box_size(lo, hi), effects, conditions,
                                {"State": "Empty"})])


def pressure_plates(params, ctx):
    """Pressure plates: every plate pressed at once opens the way. A plate is pressed by whatever its Targets are
    (players, a lured NPC, a dropped item) standing on it, or by a carried Weight block set down on it; it lifts when
    the last of them leaves, unless Latch keeps it down.

    params: Plates [{X, Y, Z}] (plate cells; the template places them), Targets (["Player"], ["Npc"], ["ItemDrop"],
    or several; default ["Player"]; [] when only a Weight presses), Weight (a block id whose placing on a plate presses
    it), Latch (default false), Plate and Plate_Down (palette slots; Plate_Down optional), Key (default
    PlateLock@room), PlateKey (default Plate@room), Controller {X, Y, Z}, the solved params, HintKey and HintRegion
    (a room hint).
    A plate's volume covers its cell and the two above, where a body or a set-down block stands."""
    plates = cells_of(params["Plates"])
    targets = list(params.get("Targets", ["Player"]))
    weight = params.get("Weight")
    latch = bool(params.get("Latch", False))
    up_slot = params.get("Plate", "Plate")
    up = ctx["slot"](up_slot)
    down = ctx["slot"](params["Plate_Down"]) if params.get("Plate_Down") else None
    key = scoped(params.get("Key", "PlateLock" + ROOM), "Key")
    plate_key = scoped(params.get("PlateKey", "Plate" + ROOM), "PlateKey")
    if not targets and not weight:
        raise ValueError("PressurePlates: give Targets, a Weight, or both")
    out = Logic()

    def press(event, c):
        e = [set_own_tag(event, "Pressed", "Yes"), send_signal(event, key, ["Pressed"], delay=SETTLE)]
        if down:
            e.append(swap_cell(event, c, c, up, down))
        return e

    def lift(event, c):
        e = [set_own_tag(event, "Pressed", "No")]
        if down:
            e.append(swap_cell(event, c, c, down, up))
        return e

    def empty(event):
        out_c = []
        if "Player" in targets:
            out_c.append(condition("PlayerCountCondition", event, Comparison="Exactly", Count=0))
        if "Npc" in targets:
            out_c.append(condition("EntityCountCondition", event, Comparison="Exactly", Count=0))
        return out_c

    for i, c in enumerate(plates):
        out.blocks[c] = up_slot
        effects, conditions = [], []
        if targets:
            effects += press("ENTER", c)
            if not latch:
                effects += lift("EXIT", c)
                conditions += empty("EXIT")
        if weight:
            effects += press("BLOCK_PLACED", c)
            conditions.append(condition("BlockTypeCondition", "BLOCK_PLACED", BlockType=[weight],
                                        PositionSource="Event"))
            if not latch:
                effects += lift("BLOCK_BROKEN", c)
                conditions.append(condition("BlockTypeCondition", "BLOCK_BROKEN", BlockType=[weight],
                                            PositionSource="Event"))
        out.entities.append(volume_entity("%sPlate_%d" % (ctx["name"], i + 1), c, (1, 3, 1), effects, conditions,
                                          {plate_key: "Plate", "Pressed": "No"}, targets=targets or ["Player"]))
    ctl = controller_cell(params, (plates[0][0], plates[0][1] + 3, plates[0][2]))
    out.entities.append(all_at_once(params, ctx, "Plates", ctl, key, "Lock", "Pressed", plate_key, "Pressed", "Yes",
                                    kind="Puzzle_Pressure_Plates"))
    out.entities.extend(hint_volumes(params, ctx))
    return out


def grid_cell(tiles_lo, tiles_hi, col, row):
    """A memory floor's tile: column `col` east from the west edge, row `row` north from the south (entry) edge."""
    return (tiles_lo[0] + col, tiles_lo[1], tiles_hi[2] - row)


def check_variants(variants, label):
    if not variants:
        raise ValueError("%s: give at least one variant" % label)


def memory_floor(params, ctx):
    """The memory floor: a grid of tiles where only the variant's path holds; a wrong tile drops away under whoever
    steps on it and closes again Drop seconds later. Crossing to Finish solves it. The path is a ZigVariant group, so
    the run's seed picks one variant, and the same group's MemoryClue in an earlier room paints that path.

    params: Tiles {Min, Max} (one layer of tile cells; the template places them), Variants [[[col, row], ...], ...]
    (each variant's safe tiles, col east from Tiles.Min.X, row north from Tiles.Max.Z), Group (default Memory), Drop
    (seconds; default 3), Tile (palette slot; default Tile), Finish {Min, Max} (optional: entering it solves), the
    solved params, HintKey and HintRegion (a room hint). A pit under the floor wants a FallRescue."""
    lo, hi = region(params["Tiles"])
    if lo[1] != hi[1]:
        raise ValueError("MemoryFloor: Tiles must be one layer")
    variants = params["Variants"]
    check_variants(variants, "MemoryFloor")
    group = params.get("Group", "Memory")
    drop = float(params.get("Drop", 3.0))
    tile_slot = params.get("Tile", "Tile")
    tile = ctx["slot"](tile_slot)
    out = Logic()
    cols, rows = hi[0] - lo[0] + 1, hi[2] - lo[2] + 1
    for col in range(cols):
        for row in range(rows):
            out.blocks[grid_cell(lo, hi, col, row)] = tile_slot
    for n, path in enumerate(variants, start=1):
        safe = {(int(c), int(r)) for c, r in path}
        for col, row in sorted(safe):
            if not (0 <= col < cols and 0 <= row < rows):
                raise ValueError("MemoryFloor: variant %d names tile %d,%d outside the grid" % (n, col, row))
        for row in range(rows):
            for col in range(cols):
                if (col, row) in safe:
                    continue
                t = grid_cell(lo, hi, col, row)
                foot = (t[0], t[1] + 1, t[2])
                effects = [swap_cell("ENTER", foot, t, tile, EMPTY, delay=0.25),
                           swap_cell("ENTER", foot, t, EMPTY, tile, delay=0.25 + drop)]
                out.entities.append(volume_entity("%sMemory_%d_%d_%d" % (ctx["name"], n, col, row), foot, (1, 2, 1),
                                                  effects, (), variant_tag(group, n)))
    if params.get("Finish"):
        flo, fhi = region(params["Finish"])
        effects = [set_own_tag("ENTER", "State", "Solved")] + solved(params, ctx, "ENTER", flo,
                                                                     kind="Puzzle_Memory_Floor")
        out.entities.append(volume_entity(ctx["name"] + "Memory_Finish", flo, box_size(flo, fhi), effects,
                                          [tag_is("ENTER", "State", "Ready")], {"State": "Ready"}))
    out.entities.extend(hint_volumes(params, ctx))
    return out


def canvas_cell(canvas, col, row, upright):
    """Where a clue paints tile (col, row): on the floor plane (rows north) or, Upright, on a wall (rows up)."""
    if upright:
        return (canvas[0] + col, canvas[1] + row, canvas[2])
    return (canvas[0] + col, canvas[1], canvas[2] - row)


def memory_clue(params, ctx):
    """The memory floor's clue: in an earlier room, the variant's path painted as Mark blocks the moment the room's
    logic is placed (VOLUME_CREATE), one volume per variant in the same ZigVariant group as its MemoryFloor, so only
    the picked one paints.

    params: Canvas {X, Y, Z} (the air cell tile 0,0 paints to), Variants and Group (the MemoryFloor's), Upright
    (paint on a wall facing south, rows going up; default false: on the floor plane, rows going north), Mark (palette
    slot; default Mark), HintKey and HintRegion (a room hint). Every painted cell must be air: the paint never
    replaces a block."""
    canvas = cell_of(params["Canvas"])
    variants = params["Variants"]
    check_variants(variants, "MemoryClue")
    group = params.get("Group", "Memory")
    upright = bool(params.get("Upright", False))
    mark = ctx["slot"](params.get("Mark", "Mark"))
    out = Logic()
    for n, path in enumerate(variants, start=1):
        effects = [place_block("VOLUME_CREATE", canvas, canvas_cell(canvas, int(c), int(r), upright), mark)
                   for c, r in sorted((int(c), int(r)) for c, r in path)]
        out.entities.append(cell_volume("%sMemory_Clue_%d" % (ctx["name"], n), canvas, effects,
                                        tags=variant_tag(group, n)))
    out.entities.extend(hint_volumes(params, ctx))
    return out


def rune_order(params, ctx):
    """The rune order: use the runes in the variant's order. A right rune lights; a wrong one puts every lit rune out
    and the order starts again. The order is a ZigVariant group, painted in an earlier room by its RuneClue.

    params: Runes [{X, Y, Z}] (usable rune cells; the template places them), Glyphs [slot, ...] (each rune's palette
    slot, in Runes order; the clue paints the same blocks), Lit (the palette slot a lit rune becomes, or one per rune
    in Runes order, such as each glyph's own On state; default Rune_Lit),
    Variants [[rune index, ...], ...] (each an order over the runes, indices from 0), Group (default Runes), Key
    (default RuneLock@room), Controller {X, Y, Z}, PressSound, the solved params, HintKey and HintRegion (a room hint).
    The controller walks Step 0, 1, ... and Done; a wrong rune writes Reset before 0, at any step, the first
    included: a rune block's own Use lights it whatever the order, so a wrong first press must be put out too."""
    runes = cells_of(params["Runes"])
    glyphs = list(params["Glyphs"])
    if len(glyphs) != len(runes):
        raise ValueError("RuneOrder: give one Glyph per rune")
    variants = params["Variants"]
    check_variants(variants, "RuneOrder")
    group = params.get("Group", "Runes")
    key = scoped(params.get("Key", "RuneLock" + ROOM), "Key")
    lit_slots = params.get("Lit", "Rune_Lit")
    lit_slots = list(lit_slots) if isinstance(lit_slots, list) else [lit_slots] * len(runes)
    if len(lit_slots) != len(runes):
        raise ValueError("RuneOrder: give one Lit slot, or one per rune")
    lits = [ctx["slot"](s) for s in lit_slots]
    blocks = [ctx["slot"](g) for g in glyphs]
    out = Logic()
    for i, c in enumerate(runes):
        out.blocks[c] = glyphs[i]
        effects = [send_signal("BLOCK_USED", key, ["Rune"], [i])] + cue("BLOCK_USED", params, "Press", c, c)
        out.entities.append(cell_volume("%sRune_%d" % (ctx["name"], i + 1), c, effects))
    ctl = controller_cell(params, (runes[0][0], runes[0][1] + 2, runes[0][2]))
    reset_delay = float(params.get("ResetDelay", 1.0))
    for n, order in enumerate(variants, start=1):
        order = [int(i) for i in order]
        if sorted(set(order)) != sorted(order) or not all(0 <= i < len(runes) for i in order):
            raise ValueError("RuneOrder: variant %d must name each rune at most once" % n)
        effects, conditions = [], []
        last = len(order) - 1
        for k, rune in enumerate(order):
            entry = k + 1
            conditions += [event_tag_is("SIGNAL_RECEIVED", "Rune", str(rune), entry),
                           tag_is("SIGNAL_RECEIVED", "Step", str(k), entry)]
            effects.append(swap_cell("SIGNAL_RECEIVED", ctl, runes[rune], blocks[rune], lits[rune], entry))
            # Entries run in order on one signal, so the step moves on a moment later: the wrong-rune entries below
            # must still read the step this rune answered.
            if k < last:
                effects.append(set_own_tag("SIGNAL_RECEIVED", "Step", str(k + 1), entry, delay=SETTLE))
            else:
                effects.append(set_own_tag("SIGNAL_RECEIVED", "Step", "Done", entry, delay=SETTLE))
                effects += solved(params, ctx, "SIGNAL_RECEIVED", ctl, entry, kind="Puzzle_Rune_Order")
        for k in range(len(order)):
            entry = len(order) + 1 + k
            conditions += [event_tag_is("SIGNAL_RECEIVED", "Rune", str(order[k]), entry, comparison="NotEquals"),
                           tag_is("SIGNAL_RECEIVED", "Step", str(k), entry)]
            effects.append(set_own_tag("SIGNAL_RECEIVED", "Step", "Reset", entry))
            for i, c in enumerate(runes):
                effects.append(swap_cell("SIGNAL_RECEIVED", ctl, c, lits[i], blocks[i], entry, delay=reset_delay))
            effects.append(set_own_tag("SIGNAL_RECEIVED", "Step", "0", entry, delay=reset_delay))
        tags = {key: "Lock", "Step": "0"}
        tags.update(variant_tag(group, n))
        out.entities.append(cell_volume("%sRunes_%d" % (ctx["name"], n), ctl, effects, conditions, tags))
    out.entities.extend(hint_volumes(params, ctx))
    return out


def rune_clue(params, ctx):
    """The rune order's clue: in an earlier room, the variant's order painted as its runes' glyph blocks in a row,
    first to last along Step, when the room's logic is placed; one volume per variant in the RuneOrder's group.

    params: Canvas {X, Y, Z} (the air cell the first glyph paints to), Step {X, Y, Z} (from one glyph to the next;
    default one block east), Glyphs, Variants and Group (the RuneOrder's), HintKey and HintRegion (a room hint)."""
    canvas = cell_of(params["Canvas"])
    step = cell_of(params.get("Step", {"X": 1, "Y": 0, "Z": 0}))
    blocks = [ctx["slot"](g) for g in params["Glyphs"]]
    variants = params["Variants"]
    check_variants(variants, "RuneClue")
    group = params.get("Group", "Runes")
    out = Logic()
    for n, order in enumerate(variants, start=1):
        effects = []
        for k, rune in enumerate(int(i) for i in order):
            at = (canvas[0] + step[0] * k, canvas[1] + step[1] * k, canvas[2] + step[2] * k)
            effects.append(place_block("VOLUME_CREATE", canvas, at, blocks[rune]))
        out.entities.append(cell_volume("%sRune_Clue_%d" % (ctx["name"], n), canvas, effects,
                                        tags=variant_tag(group, n)))
    out.entities.extend(hint_volumes(params, ctx))
    return out


def lever_logic(params, ctx):
    """Lever logic, the Deepworks generator: each lever powers its pipe run (the pipe lights cell by cell); when every
    lever stands the way it is wanted, the generator runs and the way opens.

    params: Levers [{At {X, Y, Z}, Pipe [{X, Y, Z}, ...], Want (true for on, the default; false for a lever that must
    stay off)}] (the template places levers and pipes), Lever, Pipe and Pipe_Lit (palette slots; defaults those
    names), Flow (seconds per pipe cell; default 0.25), Key (default Generator@room), LeverKey (default Lever@room),
    Controller {X, Y, Z}, PullSound, the solved params, HintKey and HintRegion (a room hint).
    A lever walks On No and Yes, and keeps Correct (Yes when it stands as wanted) for the generator to read."""
    levers = params["Levers"]
    lever_slot, pipe_slot = params.get("Lever", "Lever"), params.get("Pipe", "Pipe")
    pipe, pipe_lit = ctx["slot"](pipe_slot), ctx["slot"](params.get("Pipe_Lit", "Pipe_Lit"))
    flow = float(params.get("Flow", 0.25))
    key = scoped(params.get("Key", "Generator" + ROOM), "Key")
    lever_key = scoped(params.get("LeverKey", "Lever" + ROOM), "LeverKey")
    out = Logic()
    for i, lv in enumerate(levers):
        c = cell_of(lv["At"])
        run = cells_of(lv.get("Pipe", []))
        want = bool(lv.get("Want", True))
        out.blocks[c] = lever_slot
        for p in run:
            out.blocks[p] = pipe_slot
        on_correct, off_correct = ("Yes", "No") if want else ("No", "Yes")
        # Entries run in order on one use, so entry 0's writes wait a moment: entry 1 must still read On as No.
        effects = [set_own_tag("BLOCK_USED", "On", "Yes", delay=SETTLE),
                   set_own_tag("BLOCK_USED", "Correct", on_correct, delay=SETTLE)]
        effects += [swap_cell("BLOCK_USED", c, p, pipe, pipe_lit, delay=flow * k) for k, p in enumerate(run)]
        effects += [send_signal("BLOCK_USED", key, ["Lever"], delay=2 * SETTLE)]
        effects += cue("BLOCK_USED", params, "Pull", c, c)
        effects += [set_own_tag("BLOCK_USED", "On", "No", 1), set_own_tag("BLOCK_USED", "Correct", off_correct, 1)]
        effects += [swap_cell("BLOCK_USED", c, p, pipe_lit, pipe, 1, delay=flow * k)
                    for k, p in enumerate(reversed(run))]
        effects += [send_signal("BLOCK_USED", key, ["Lever"], entry=1, delay=SETTLE)]
        effects += cue("BLOCK_USED", params, "Pull", c, c, entry=1)
        conditions = [tag_is("BLOCK_USED", "On", "No"), tag_is("BLOCK_USED", "On", "Yes", 1)]
        tags = {lever_key: "Lever", "On": "No", "Correct": off_correct}
        out.entities.append(cell_volume("%sLever_%d" % (ctx["name"], i + 1), c, effects, conditions, tags))
    first = cell_of(levers[0]["At"])
    ctl = controller_cell(params, (first[0], first[1] + 2, first[2]))
    out.entities.append(all_at_once(params, ctx, "Generator", ctl, key, "Generator", "Lever", lever_key, "Correct",
                                    "Yes", kind="Puzzle_Lever_Logic"))
    out.entities.extend(hint_volumes(params, ctx))
    return out


# ---------------------------------------------------------------------------------------------------------------
# Templates: hazards (each on the shared pulse)


FIRE_HARM = {"Damage": 6.0, "DamageMode": "PercentMax", "Effect": "Burn", "EffectSeconds": 3.0}


def flame_stair(params, ctx):
    """The flame stair: jets fire one after another up the stair, so a climber times the gaps.

    params: Jets [{Min, Max}] (each jet's cells, bottom to top), Region {Min, Max} (the stair, where the rhythm
    runs), Period (seconds a whole sweep takes; default 6), Stagger (seconds between jets; default Period / jets),
    Warn (default 1), Burst (default 1), Damage, DamageMode, Effect (default Burn), EffectSeconds, Push {X, Y, Z}
    (optional knockback, in the room's frame), Key (default Jet@room), WarnSound, WarnVfx, BurstSound, BurstVfx,
    Targets (default ["Player", "Npc"])."""
    n = len(params["Jets"])
    period = float(params.get("Period", 6.0))
    stagger = float(params.get("Stagger", period / n))
    return pulse_hazard(params, ctx, "Jets", "Jet", "Jet" + ROOM, lambda i, count, p: (i * stagger) % p,
                        1.0, 1.0, FIRE_HARM)


def fire_floor(params, ctx):
    """The rotating fire floor, the Deepworks quadrant controller: the floor's sections blast in turn around the room,
    each warning first, so a player keeps moving to the section that just burned.

    params: Quadrants [{Min, Max}] (the sections, in the order the fire turns), Region {Min, Max}, Period (seconds
    for a full turn; default 8), Warn (default 2), Burst (default 0.6), Damage, DamageMode, Effect, EffectSeconds,
    Push, Key (default Quadrant@room), WarnSound, WarnVfx, BurstSound, BurstVfx, Targets."""
    params = dict(params)
    params.setdefault("Period", 8.0)
    return pulse_hazard(params, ctx, "Quadrants", "Quadrant", "Quadrant" + ROOM,
                        lambda i, count, p: p * i / count, 2.0, 0.6, FIRE_HARM)


def geyser_hall(params, ctx):
    """The geyser hall: vents bubble, then throw whoever stands on them into the air and scald them; alternate vents
    erupt half a period apart.

    params: Geysers [{Min, Max}] (each vent's cells), Region {Min, Max}, Period (default 5), Warn (default 1.5),
    Burst (default 0.6), Launch (upward speed; default 12), Damage (default 4, PercentMax), Effect, EffectSeconds,
    Key (default Geyser@room), WarnSound, WarnVfx, BurstSound, BurstVfx, Targets."""
    params = dict(params)
    params.setdefault("Period", 5.0)
    params.setdefault("Push", {"X": 0, "Y": float(params.get("Launch", 12.0)), "Z": 0})
    return pulse_hazard(params, ctx, "Geysers", "Geyser", "Geyser" + ROOM,
                        lambda i, count, p: (p / 2.0) * (i % 2), 1.5, 0.6, {"Damage": 4.0, "DamageMode": "PercentMax"})


# ---------------------------------------------------------------------------------------------------------------
# Templates: kit pieces


def checkpoint(params, ctx):
    """The checkpoint statue: walking into Region moves this party's respawn to Respawn, heals it briefly and shows the
    banner, through CommonLib's ZigCheckpoint (the run retires the previous one and counts it once).

    params: Region {Min, Max}, Respawn {X, Y, Z} (the cell a respawned player's feet stand in), Yaw (degrees the
    respawn faces; default 0, North), HealEffect (an entity effect id; default the library's own), TitleKey and
    SubtitleKey (lang keys for the banner), Statue {X, Y, Z} with the Statue_Slot and Statue_Lit palette slots
    (default Statue and Statue_Lit; optional: the template places the statue, which wakes when first touched),
    WakeSound and WakeVfx."""
    lo, hi = region(params["Region"])
    ret = cell_of(params["Respawn"])
    # ZigCheckpoint's keys are flat: RespawnOffset (from the volume's corner), RespawnYaw, HealEffect,
    # BannerTitleKey and BannerSubtitleKey.
    cp = {"RespawnOffset": vec(ret[0] - lo[0] + 0.5, ret[1] - lo[1], ret[2] - lo[2] + 0.5),
          "RespawnYaw": float(params.get("Yaw", 0.0))}
    if params.get("HealEffect"):
        cp["HealEffect"] = params["HealEffect"]
    for param, key in (("TitleKey", "BannerTitleKey"), ("SubtitleKey", "BannerSubtitleKey")):
        if params.get(param):
            cp[key] = params[param]
    effects = [effect("ZigCheckpoint", "ENTER", **cp)]
    out = Logic()
    if params.get("Statue"):
        s = cell_of(params["Statue"])
        out.blocks[s] = params.get("Statue_Slot", "Statue")
        effects.append(swap_cell("ENTER", lo, s, ctx["slot"](params.get("Statue_Slot", "Statue")),
                                 ctx["slot"](params.get("Statue_Lit", "Statue_Lit"))))
    effects += cue("ENTER", params, "Wake", lo, ret)
    out.entities.append(volume_entity(ctx["name"] + "Checkpoint", lo, box_size(lo, hi), effects))
    return out


def skill_lock(params, ctx):
    """The skill lock: a blocked way (a cracked wall, a root wall, a sunken chest) that opens when a player who meets
    the requirement uses it, counting Secret_Found for the run, and optionally raising the cache behind it, a reward
    chest whose opener finds a reward of their own in it (CommonLib's ZigRewardChest). A player short of it is told
    what opens it.

    params: Lock {Min, Max}, or a list of them (the lock's cells, fitted round a doorway's shape; the template
    places them; a usable block); an entry {Door: n} fills that door's whole opening instead, as the builder measured
    it (a vanilla doorway is rarely a box), so the lock is the room's own way in; Lock_Slot (palette slot; default
    Lock, a block whose Use does nothing, such as CommonLib's Zc_Lock_* blocks), Opened (the slot the cells become; default
    air), Factor (default hytale:stat), Param (the stat a skill's level reads as, such as
    Level_Mining), Min (the level), or Requires (a whole Requires block instead), HintKey (the lang key naming what
    opens it, required), Level (the level the hint names; default Min), Milestone (default Secret_Found),
    KindMilestone (a second beat naming which kind of lock it was, such as Lock_Mining, credited only to the player
    who opened it, so a party member standing by never earns it; default none), Signal,
    OpenSound, OpenVfx, and the cache: Chest (a reward chest file id) with At {X, Y, Z} (the air cell over the floor
    it stands in, a lock cell only when the lock opens to air; a spec writes "$Chest" for its own top-level Chest),
    OpenerLootable and OpenerTitleKey (the opener's own reward in it). Placement (default the volume) is what the
    milestones and the cache count once per run under: two locks guarding one cache (both doorways of a room) share
    one ending @room, so whichever opens second pays and counts nothing.
    The lock's volume carries the level as its Level tag, and the engine binds a volume's tags as named params of the
    line it sends, so HintKey's value names it as {Level} (no digit in the lang file): the level is written once, here.
    Opening swaps each Lock piece on its own, so a block of the lock's kind the room already has beside the lock
    stays put.

    One branch per kind of server: Branches, a list of two or more, takes the place of Factor, Param, Min, Requires,
    HintKey and Level, each branch writing its own: Where (a world-level Requires block, read of the server and never
    of a player: {"Factors": [{"Factor": "hytale:mod_installed", "Param": "Group:Name", "Min": 1}]} where that mod
    runs, "Max": 0 in place of "Min": 1 where it does not), its Factor, Param and Min or its Requires, its HintKey and
    its Level. Each branch stamps a lock volume of its own over the same cells, opening them the same way, tagged
    ZigWhere with its Where (CommonLib's RoomWhere), so a generated run places only the volume whose Where holds on
    its server and the lock speaks only that branch's line. The whole lock reads AnyOf[AllOf[Where, Requires] per
    branch]: each volume asks its own Where beside its Requires, so a copy of the room the engine pastes whole, which
    places every volume, still opens only by its own server's branch (there every other branch's volume also says
    its own line when used). Write each Where so exactly one holds on any server."""
    pieces = []
    for piece in params["Lock"] if isinstance(params["Lock"], list) else [params["Lock"]]:
        if "Door" in piece:
            pieces.extend(column_runs(ctx["doors"][int(piece["Door"])]["Opening"]))
        else:
            pieces.append(region(piece))
    cells = sorted({c for piece in pieces for c in box_cells(*piece)})
    lo = tuple(min(c[i] for c in cells) for i in range(3))
    hi = tuple(max(c[i] for c in cells) for i in range(3))
    slot = params.get("Lock_Slot", "Lock")
    lock = ctx["slot"](slot)
    opened = ctx["slot"](params["Opened"]) if params.get("Opened") else EMPTY
    branches = lock_branches(params)
    effects = [set_own_tag("BLOCK_USED", "State", "Open")]
    effects += [swap_cells("BLOCK_USED", lo, plo, phi, [lock], opened) for plo, phi in pieces]
    effects += cue("BLOCK_USED", params, "Open", lo, lo)
    if params.get("Signal"):
        effects.append(send_signal("BLOCK_USED", scoped(params["Signal"], "Signal"), ["Opened"]))
    placement = params.get("Placement", "")
    ms = params.get("Milestone", "Secret_Found")
    if ms:
        effects.append(milestone("BLOCK_USED", ms, placement=placement))
    if params.get("KindMilestone"):
        effects.append(milestone("BLOCK_USED", params["KindMilestone"], placement=placement, player_only=True))
    if params.get("Chest"):
        if not params.get("At"):
            raise ValueError("SkillLock: a Chest needs At, the cell it stands in")
        if params.get("Opened") and cell_of(params["At"]) in cells:
            raise ValueError("SkillLock: the chest's At %s is a cell the opened lock fills" % (cell_of(params["At"]),))
        effects.append(chest_effect("BLOCK_USED", lo, params))
    elif params.get("OpenerLootable") or params.get("OpenerTitleKey"):
        raise ValueError("SkillLock: OpenerLootable and OpenerTitleKey are rewards in the cache, so they need a Chest")
    out = Logic(blocks={c: slot for c in cells})
    for n, branch in enumerate(branches, 1):
        conditions = [tag_is("BLOCK_USED", "State", "Locked")]
        tags = {"State": "Locked"}
        name = ctx["name"] + "Skill_Lock"
        if "Where" in branch:
            conditions.append(condition("ZigRequires", "BLOCK_USED", Requires=branch["Where"]))
            tags.update(where_tag(branch["Where"]))
            name += "_%d" % n
        conditions.append(condition("ZigRequires", "BLOCK_USED", Requires=lock_requires(branch)))
        level = branch.get("Level", branch.get("Min"))
        if level is not None:
            tags["Level"] = "%d" % int(level)
        out.entities.append(volume_entity(name, lo, box_size(lo, hi), copy.deepcopy(effects), conditions, tags,
                                          rejections=[message("BLOCK_USED", branch["HintKey"])]))
    return out


# The keys a skill lock's one branch writes: at the top level for a lock that asks the same on every server, in each
# entry of Branches otherwise.
LOCK_BRANCH_KEYS = ("Factor", "Param", "Min", "Requires", "HintKey", "Level")


def lock_branches(params):
    """A skill lock's branches: its Branches, each checked (a Where, a HintKey, a requirement), else the lock's own
    params as its one branch with no Where."""
    if "Branches" not in params:
        if not params.get("HintKey"):
            raise ValueError("SkillLock: HintKey (the lang key naming what opens it) is required")
        return [params]
    branches = params["Branches"]
    mixed = [k for k in LOCK_BRANCH_KEYS if k in params]
    if mixed:
        raise ValueError("SkillLock: with Branches, each branch writes its own %s, never the lock" % ", ".join(mixed))
    if not isinstance(branches, list) or len(branches) < 2:
        raise ValueError("SkillLock: Branches lists two or more, one per kind of server")
    for n, branch in enumerate(branches, 1):
        if not branch.get("Where"):
            raise ValueError("SkillLock: branch %d needs Where, the world-level Requires that picks its server" % n)
        if not branch.get("HintKey"):
            raise ValueError("SkillLock: branch %d needs HintKey, the line naming what opens it there" % n)
        lock_requires(branch)
    return branches


def lock_requires(form):
    """What a lock (or one of its branches) asks of the player who uses it: its Requires, else Factor (default
    hytale:stat), Param and Min."""
    if form.get("Requires"):
        return form["Requires"]
    if not form.get("Param") or form.get("Min") is None:
        raise ValueError("SkillLock: a lock asks Param and Min (with Factor), or a whole Requires")
    return {"Factors": [{"Factor": form.get("Factor", "hytale:stat"), "Param": form["Param"], "Min": form["Min"]}]}


def column_runs(cells):
    """`cells` as boxes (lo, hi), each one column's unbroken run of cells, so an odd shape swaps run by run."""
    by_column = {}
    for c in cells:
        by_column.setdefault((c[0], c[2]), []).append(c[1])
    out = []
    for (x, z), ys in sorted(by_column.items()):
        ys = sorted(ys)
        start = prev = ys[0]
        for y in ys[1:] + [None]:
            if y is None or y != prev + 1:
                out.append(((x, start, z), (x, prev, z)))
                start = y
            if y is not None:
                prev = y
    return out


def room_hint(params, ctx):
    """The room hint: a player coming into the room is told one line, once per run (a per-player Cooldown longer than
    any run, HINT_ONCE), so a room teaches what it asks where it asks it. Every puzzle template stamps one from its own
    HintKey; a room with no puzzle (a hazard, a checkpoint) names this template itself.

    params: HintKey (the lang key, required), Region {Min, Max} (where coming in counts; default the room's Box)."""
    if not params.get("HintKey"):
        raise ValueError("RoomHint: HintKey (the lang key of the line) is required")
    return Logic(hint_volumes(params, ctx, region_param="Region"))


def reward_chest(params, ctx):
    """The reward chest: CommonLib's ZigRewardChest raises the reward chest file Chest on the cell At, once per run,
    for every member standing in the run's area, each finding their own share of what it holds. Without Signal it
    rises as the first player walks into Region (default the room's Box), and that player may find a reward of their
    own in it; with Signal, as the volume carrying Tag hears that signal key (a fight's ArenaTag hearing Defeated, as
    ArenaDoors does, or a room key a puzzle's Signal names hearing Solved), which sets it off for nobody.

    params: Chest (a reward chest file id, required), At {X, Y, Z} (the air cell over the floor the chest stands in,
    required; a spec writes "$Chest" for its own top-level Chest), Region {Min, Max}, Signal (a signal key) with Tag
    (the tag its sender finds: a fight's ArenaTag, plain, or a room key ending @room), UnlessWearing (with Signal: an
    effect id; a creature inside Region, else the room's Box, seen wearing it keeps the chest down for the run, as a
    boss its fight marks as it gets away, whose vanishing still tells the arena Defeated), OpenerLootable and
    OpenerTitleKey (the walking-in player's own reward), Placement (what it rises once per run under; default the
    volume)."""
    if not params.get("Chest"):
        raise ValueError("RewardChest: Chest (the reward chest file that rises) is required")
    if not params.get("At"):
        raise ValueError("RewardChest: At (the cell the chest stands in) is required; a spec writes \"$Chest\"")
    at = cell_of(params["At"])
    signal = params.get("Signal")
    if params.get("Region"):
        lo, hi = region(params["Region"])
    elif ctx.get("box"):
        lo, hi = ctx["box"]
    else:
        lo = hi = None
    if not signal:
        if params.get("Tag") or params.get("UnlessWearing"):
            raise ValueError("RewardChest: Tag and UnlessWearing go with a Signal")
        if lo is None:
            raise ValueError("RewardChest: give Region, where walking in raises the chest (the room has no Box)")
        return Logic([volume_entity(ctx["name"] + "Reward_Chest", lo, box_size(lo, hi),
                                    [chest_effect("ENTER", lo, params)])])
    if not params.get("Tag"):
        raise ValueError("RewardChest: a Signal needs the Tag its sender finds")
    if params.get("OpenerLootable") or params.get("OpenerTitleKey"):
        raise ValueError("RewardChest: a signal sets the chest off for nobody, so it has no opener's reward")
    tags = {params["Tag"]: "Chest"}
    conditions = event_any("SIGNAL_RECEIVED", [signal], 1)
    wearing = params.get("UnlessWearing")
    if not wearing:
        return Logic([cell_volume(ctx["name"] + "Reward_Chest", at, [chest_effect("SIGNAL_RECEIVED", at, params, 1)],
                                  conditions, tags)])
    if lo is None:
        raise ValueError("RewardChest: give Region, where UnlessWearing is looked for (the room has no Box)")
    away = set_own_tag("TICK", "Away", "Yes", entry=2)
    away["Interval"] = 0.25
    effects = [chest_effect("SIGNAL_RECEIVED", lo, params, 1), away]
    conditions += [tag_is("SIGNAL_RECEIVED", "Away", "Yes", entry=1, inverted=True),
                   condition("ZigHasEffect", "TICK", 2, Effect=wearing)]
    tags["Away"] = "No"
    return Logic([volume_entity(ctx["name"] + "Reward_Chest", lo, box_size(lo, hi), effects, conditions, tags,
                                targets=("Npc",))])


def event_any(event, keys, entry):
    """The event carries one of `keys`: one condition per key would AND them, so each key takes its own entry."""
    return [event_tag_is(event, key, entry=entry + i) for i, key in enumerate(keys)]


def arena_doors(params, ctx):
    """An arena's doorways, closed while its fight is on: each listed doorway's air turns to bars as the fight begins
    (its encounter script signals Engaged to the volumes carrying its arena tag) and back to air as the boss falls
    (Defeated) or the fight resets (Reset). The volumes carry the arena tag plain, never @room: the fight's own signal
    reaches them, and an arena keeps its fights more than its signal's range from the next arena.

    params: Tag (the script's ArenaTag), Doors (indices into the spec's Doors; default all), Bars (the palette slot that
    closes a doorway; default Bars)."""
    tag = params["Tag"]
    if tag.endswith(ROOM) or tag.endswith(RUN):
        raise ValueError("an arena tag is the fight's own, plain: an encounter script's signal is never scoped")
    bars = ctx["slot"](params.get("Bars", "Bars"))
    out = Logic()
    for i in params.get("Doors", range(len(ctx["doors"]))):
        d = ctx["doors"][i]
        lo, hi = d["OpeningMin"], d["OpeningMax"]
        effects = [swap_cells("SIGNAL_RECEIVED", lo, lo, hi, [EMPTY], bars, entry=1),
                   set_own_tag("SIGNAL_RECEIVED", "State", "Sealed", entry=1)]
        conditions = event_any("SIGNAL_RECEIVED", ["Engaged"], 1)
        for entry, key in ((2, "Defeated"), (3, "Reset")):
            effects.append(swap_cells("SIGNAL_RECEIVED", lo, lo, hi, [bars], EMPTY, entry=entry))
            effects.append(set_own_tag("SIGNAL_RECEIVED", "State", "Open", entry=entry))
            conditions += event_any("SIGNAL_RECEIVED", [key], entry)
        out.entities.append(volume_entity("%sDoor_%d" % (ctx["name"], i), lo, box_size(lo, hi), effects, conditions,
                                          {tag: "Door", "State": "Open"}))
    return out


def pillar_boxes(pillars, reach):
    """Each pillar's watch box, (lo, hi) cells: the pillar `reach` blocks wider on every side and at least three cells
    tall, so a creature pressed against it is inside. Where two boxes would overlap (pillars closer than twice the
    reach), both stop at the gap between the pillars along the axis that parts them most, the lower one taking the
    smaller half, so one crash is ever caught by one pillar. A pair parted on neither horizontal axis (one stacked over
    the other) is refused."""
    boxes = []
    for p in pillars:
        plo, phi = region(p)
        boxes.append([[plo[0] - reach, plo[1], plo[2] - reach], [phi[0] + reach, max(phi[1], plo[1] + 2), phi[2] + reach]])
    cells = [region(p) for p in pillars]
    for i in range(len(pillars)):
        for j in range(i + 1, len(pillars)):
            a, b = boxes[i], boxes[j]
            if any(a[1][k] < b[0][k] or b[1][k] < a[0][k] for k in range(3)):
                continue
            (alo, ahi), (blo, bhi) = cells[i], cells[j]
            best = None
            for k in (0, 2):
                if ahi[k] < blo[k]:
                    gap, low, high = blo[k] - ahi[k] - 1, i, j
                elif bhi[k] < alo[k]:
                    gap, low, high = alo[k] - bhi[k] - 1, j, i
                else:
                    continue
                if best is None or gap > best[0]:
                    best = (gap, k, low, high)
            if best is None:
                raise ValueError("pillars %d and %d overlap on both horizontal axes" % (i, j))
            gap, k, low, high = best
            low_face, high_face = cells[low][1][k], cells[high][0][k]
            boxes[low][1][k] = min(boxes[low][1][k], low_face + gap // 2)
            boxes[high][0][k] = max(boxes[high][0][k], high_face - (gap - gap // 2))
    return [(tuple(lo), tuple(hi)) for lo, hi in boxes]


def crash_pillars(params, ctx):
    """Pillars a charging creature crashes into (the Gobliterator's barrel breakdown, R683): each pillar stands in a
    volume wider than itself that watches the creatures in it, and one wearing the charging mark (Effect, which the
    creature's charge puts on it) runs the crash interaction (Crash: a root interaction run on that creature, such as a
    stun and a weak-spot window). The first crash cracks the pillar (its blocks turn to Cracked), a crash once it has
    settled shatters it (a Stump on its base row, air above), and a shattered pillar stops nothing. The fight's Reset
    (its encounter script signals the arena tag) stands every pillar up again.

    A volume sees a creature by the middle of its footprint, so Reach must cover the creature's half-width past the
    pillar's face: a 1.2-wide half-width needs Reach 2. Two pillars closer than twice the Reach share the gap between
    them (pillar_boxes), so one crash cracks one pillar.

    params: Pillars [{Min, Max}], Effect (the charging mark), Crash (a root interaction id), Tag (the script's ArenaTag),
    Pillar (the palette slot the pillars are built of; default Pillar), Cracked and Stump (block ids), Reach (blocks
    either side of a pillar the volume watches; default 1), Settle (seconds a cracked pillar takes before it can
    shatter; default 3), Interval (seconds between looks; default 0.2), the optional CrackSound, CrackVfx,
    ShatterSound and ShatterVfx, and the optional ShatterMoment and LastMoment (FeedbackMoment ids shown, through
    ZigFeedback, to the players within MomentRadius blocks, default 32, of the crash: one as a pillar shatters while
    others stand, the other as the last one does)."""
    tag = params["Tag"]
    if tag.endswith(ROOM) or tag.endswith(RUN):
        raise ValueError("an arena tag is the fight's own, plain: an encounter script's signal is never scoped")
    pillar = ctx["slot"](params.get("Pillar", "Pillar"))
    cracked, stump = params["Cracked"], params["Stump"]
    reach = int(params.get("Reach", 1))
    settle = float(params.get("Settle", 3.0))
    interval = float(params.get("Interval", 0.2))
    radius = float(params.get("MomentRadius", 32.0))
    member = "CrashPillar" + ROOM
    pillars = params["Pillars"]
    # Entries run in order each tick: the two notices (2, 3) read the pillars before the shatter (4) marks this one.
    crack_e, last_e, shatter_note_e, shatter_e, reset_e = 1, 2, 3, 4, 5
    out = Logic()
    for i, (p, (lo, hi)) in enumerate(zip(pillars, pillar_boxes(pillars, reach))):
        plo, phi = region(p)
        base_hi = (phi[0], plo[1], phi[2])
        mid = ((plo[0] + phi[0]) // 2, plo[1], (plo[2] + phi[2]) // 2)
        crash = effect("RunRootInteraction", "TICK", 0, RootInteraction=params["Crash"], InteractionType="Secondary",
                       EquipSlot=0)
        effects = [dict(crash, Entry=crack_e),
                   swap_cells("TICK", lo, plo, phi, [pillar], cracked, entry=crack_e),
                   set_own_tag("TICK", "State", "Cracking", entry=crack_e),
                   set_own_tag("TICK", "State", "Cracked", entry=crack_e, delay=settle)]
        effects += cue("TICK", params, "Crack", lo, mid, entry=crack_e)
        conditions = []
        notices = []
        if params.get("LastMoment"):
            notices.append((last_e, params["LastMoment"], False))
        if params.get("ShatterMoment") and len(pillars) > 1:
            notices.append((shatter_note_e, params["ShatterMoment"], True))
        for entry, moment, others_stand in notices:
            effects.append(effect("ZigFeedback", "TICK", entry, Moment=moment, Radius=radius))
            if len(pillars) > 1:
                # Every other pillar already shattered, or (Inverted) not yet: this one still reads Cracked here.
                conditions.append(condition("TagCondition", "TICK", entry, Source="Radius", TagKey="State",
                                            Comparison="Exactly", CompareAgainst="Value", TagValue="Shattered",
                                            Inverted=others_stand, MatchKey=member, MatchValue="", Radius=REACH,
                                            Center=CENTER_VOLUME, MinimumCount=len(pillars) - 1))
        effects += [dict(crash, Entry=shatter_e),
                    swap_cells("TICK", lo, plo, base_hi, [pillar, cracked], stump, entry=shatter_e),
                    set_own_tag("TICK", "State", "Shattered", entry=shatter_e)]
        if phi[1] > plo[1]:
            effects.append(swap_cells("TICK", lo, (plo[0], plo[1] + 1, plo[2]), phi, [pillar, cracked], EMPTY,
                                      entry=shatter_e))
        effects += cue("TICK", params, "Shatter", lo, mid, entry=shatter_e)
        for e in effects:
            if e["Event"] == "TICK":
                e["Interval"] = interval
        # A crack's delayed Cracked can land after a reset: a second Intact past Settle wins over it.
        effects += [swap_cells("SIGNAL_RECEIVED", lo, plo, phi, [cracked, stump, EMPTY], pillar, entry=reset_e),
                    set_own_tag("SIGNAL_RECEIVED", "State", "Intact", entry=reset_e),
                    set_own_tag("SIGNAL_RECEIVED", "State", "Intact", entry=reset_e, delay=settle + 0.25)]
        entries = [(crack_e, "Intact"), (shatter_e, "Cracked")] + [(entry, "Cracked") for entry, _, _ in notices]
        for entry, state in entries:
            conditions.append(condition("ZigHasEffect", "TICK", entry, Effect=params["Effect"]))
            conditions.append(tag_is("TICK", "State", state, entry=entry))
        conditions += event_any("SIGNAL_RECEIVED", ["Reset"], reset_e)
        out.entities.append(volume_entity("%sPillar_%d" % (ctx["name"], i), lo, box_size(lo, hi), effects, conditions,
                                          {tag: "Pillar", member: "Pillar", "State": "Intact"}, targets=("Npc",)))
    return out


def arena_wake(params, ctx):
    """Marks every player inside an arena (the room's Box, Inset blocks in from each side wall, so a player standing in a
    doorway is not yet inside): while they stand in it they wear Mark, a short effect renewed every Interval seconds,
    so it lapses Seconds after they leave. An encounter script built on Zc_Encounter_Base with WakeInArena true wakes
    its boss only for a player wearing it, never for one just outside the door.

    params: Mark (an effect id; default Zig_In_Arena), Inset (default 2), Seconds (default 2), Interval (default 1)."""
    if not ctx.get("box"):
        raise ValueError("ArenaWake covers the room's Box, and the room has none")
    inset = int(params.get("Inset", 2))
    (x0, y0, z0), (x1, y1, z1) = ctx["box"]
    lo, hi = (x0 + inset, y0, z0 + inset), (x1 - inset, y1, z1 - inset)
    if lo[0] > hi[0] or lo[2] > hi[2]:
        raise ValueError("ArenaWake's Inset %d leaves nothing of the room's Box" % inset)
    mark = effect("EntityEffect", "TICK", interval=float(params.get("Interval", 1.0)),
                  Effect=params.get("Mark", "Zig_In_Arena"), Mode="Apply", Duration=float(params.get("Seconds", 2.0)))
    out = Logic()
    out.entities.append(volume_entity(ctx["name"] + "Wake", lo, box_size(lo, hi), [mark]))
    return out


def arena_tag_of(params):
    """The fight's ArenaTag a template's volumes carry and hear, plain: an encounter script's signal is never scoped."""
    tag = params["Tag"]
    if tag.endswith(ROOM) or tag.endswith(RUN):
        raise ValueError("an arena tag is the fight's own, plain: an encounter script's signal is never scoped")
    return tag


# What a fight's script tells every volume carrying its arena tag when the floor must stand whole again: the phase that
# ends the shifting (Settle), the boss's fall (Defeated) and an arena reset (Reset).
SETTLE_KEYS = ("Settle", "Defeated", "Reset")


def centre_cell(lo, hi):
    return ((lo[0] + hi[0]) // 2, lo[1], (lo[2] + hi[2]) // 2)


def check_order(order, count, label, n):
    order = [int(i) for i in order]
    if len(set(order)) != len(order) or not all(0 <= i < count for i in order):
        raise ValueError("%s: order %d must name each index at most once, each under %d" % (label, n, count))
    return order


def shifting_floor(params, ctx):
    """A floor that shifts under a boss fight (the Heart of the Deep's): on each Shift the fight signals, the next
    section in the run's order cracks (its stone turns to Crack), Warn seconds later drops away (only Crack turns to
    air), and Down seconds after that rises again (only air turns back to its stone), so a fall still pending when the
    floor settles finds nothing to drop. On every RuneEvery-th Shift from the first, the next rune pad in the run's rune
    order glows (its pad blocks, not its plate, turn to GlowBlock) for Glow seconds; a creature wearing Ward that comes
    onto a glowing pad has Breakdown run on it and the pad goes dark. Settle, Defeated and Reset (the fight's own
    signals) put every section and pad back and start both orders over.

    The orders are a ZigVariant group (Group): the run's seed picks one variant, so a day's seed shows everyone the
    same floor. Each variant is a clock volume that walks Step 0, 1, ... on each Shift and tells one section (or one pad)
    by its index; sections and pads carry their index under SectionKey and RuneKey (both @room).

    params: Tag (the script's ArenaTag), Sections [{Min, Max, Slot}] (the sections that can fall, one layer each, the
    template places each in its own palette Slot, so a risen section comes back as it fell), Orders [[section index,
    ...], ...] (one per variant), Runes [{Min, Max, Plate {X, Y, Z}}] (rune pads, one layer; the template places the
    Pad slot on every cell and the Plate slot on the plate), RuneOrders [[rune index, ...], ...] (one per variant, as
    many as Orders), RuneEvery (default 3), Crack and GlowBlock (block ids), Pad and Plate (palette slots; defaults
    those names), Ward (the effect a creature wears for a pad to break it down) and Breakdown (a root interaction id),
    Warn (default 3), Down (default 24), Glow (default 15), RuneHeight (cells a pad's volume watches above it; default
    4), Interval (seconds between a pad's looks; default 0.25), Group (default Floor), SectionKey (default
    FloorSection@room), RuneKey (default FloorRune@room), Controller {X, Y, Z} (the clocks' cell; default the cell two
    over the first section's corner), and the optional CrackSound, CrackVfx, FallSound, FallVfx, RiseSound, RiseVfx,
    GlowSound, GlowVfx, BreakSound, BreakVfx (each a cue at the section's or pad's middle; a <cue>VfxScale sizes its
    particles), and TimerVfx with TimerSeconds (default 8): a countdown particle shown that long before a pad goes
    dark, such as the game's eight-second pie timer."""
    tag = arena_tag_of(params)
    sections = [(region(s), s.get("Slot", "Floor")) for s in params["Sections"]]
    orders = params["Orders"]
    check_variants(orders, "ShiftingFloor")
    runes = [(region(r), cell_of(r["Plate"])) for r in params.get("Runes", [])]
    rune_orders = params.get("RuneOrders", [])
    if runes and len(rune_orders) != len(orders):
        raise ValueError("ShiftingFloor: give one RuneOrder per Order, so every variant lights its runes")
    if runes and not (params.get("Ward") and params.get("Breakdown")):
        raise ValueError("ShiftingFloor: runes need a Ward (what the creature wears) and a Breakdown (what they run)")
    every = int(params.get("RuneEvery", 3))
    if every < 1:
        raise ValueError("ShiftingFloor: RuneEvery is at least 1")
    crack, glow_block = params["Crack"], params["GlowBlock"]
    pad_slot, plate_slot = params.get("Pad", "Pad"), params.get("Plate", "Plate")
    pad = ctx["slot"](pad_slot) if runes else None
    warn = float(params.get("Warn", 3.0))
    down = float(params.get("Down", 24.0))
    glow = float(params.get("Glow", 15.0))
    timer = float(params.get("TimerSeconds", 8.0))
    height = int(params.get("RuneHeight", 4))
    interval = float(params.get("Interval", 0.25))
    group = params.get("Group", "Floor")
    section_key = scoped(params.get("SectionKey", "FloorSection" + ROOM), "SectionKey")
    rune_key = scoped(params.get("RuneKey", "FloorRune" + ROOM), "RuneKey")
    sig = "SIGNAL_RECEIVED"
    out = Logic()

    def settle(lo, effects_for):
        """Entries 2, 3, 4: each of SETTLE_KEYS runs effects_for(entry)."""
        effects, conditions = [], []
        for i, key in enumerate(SETTLE_KEYS):
            effects += effects_for(2 + i)
            conditions += event_any(sig, [key], 2 + i)
        return effects, conditions

    for i, ((lo, hi), slot) in enumerate(sections):
        if lo[1] != hi[1]:
            raise ValueError("ShiftingFloor: section %d must be one layer" % i)
        stone = ctx["slot"](slot)
        mid = centre_cell(lo, hi)
        for c in box_cells(lo, hi):
            out.blocks[c] = slot
        rise = warn + down
        effects = [swap_cells(sig, lo, lo, hi, [stone], crack, entry=1),
                   set_own_tag(sig, "State", "Cracked", entry=1)]
        effects += cue(sig, params, "Crack", lo, mid, entry=1)
        effects += [swap_cells(sig, lo, lo, hi, [crack], EMPTY, entry=1, delay=warn),
                    set_own_tag(sig, "State", "Fallen", entry=1, delay=warn)]
        effects += cue(sig, params, "Fall", lo, mid, entry=1, delay=warn)
        effects += [swap_cells(sig, lo, lo, hi, [EMPTY], stone, entry=1, delay=rise),
                    set_own_tag(sig, "State", "Whole", entry=1, delay=rise)]
        effects += cue(sig, params, "Rise", lo, mid, entry=1, delay=rise)
        conditions = [event_tag_is(sig, "Fall", entry=1), tag_is(sig, "State", "Whole", entry=1)]
        # A crack's delayed writes can land after a settle: a second Whole past the last of them wins over them.
        more, more_conditions = settle(lo, lambda entry: [
            swap_cells(sig, lo, lo, hi, [crack, EMPTY], stone, entry=entry),
            set_own_tag(sig, "State", "Whole", entry=entry),
            set_own_tag(sig, "State", "Whole", entry=entry, delay=rise + 0.25)])
        out.entities.append(volume_entity("%sSection_%d" % (ctx["name"], i), lo, box_size(lo, hi), effects + more,
                                          conditions + more_conditions,
                                          {tag: "Section", section_key: str(i), "State": "Whole"}))

    for r, ((lo, hi), plate) in enumerate(runes):
        if lo[1] != hi[1]:
            raise ValueError("ShiftingFloor: rune pad %d must be one layer" % r)
        if plate not in box_cells(lo, hi):
            raise ValueError("ShiftingFloor: rune pad %d's Plate lies outside its pad" % r)
        for c in box_cells(lo, hi):
            out.blocks[c] = plate_slot if c == plate else pad_slot
        mid = centre_cell(lo, hi)
        top = (hi[0], lo[1] + height, hi[2])
        effects = [swap_cells(sig, lo, lo, hi, [pad], glow_block, entry=1),
                   set_own_tag(sig, "Glow", "Yes", entry=1)]
        effects += cue(sig, params, "Glow", lo, mid, entry=1)
        effects += cue(sig, params, "Timer", lo, mid, entry=1, delay=max(0.0, glow - timer))
        effects += [swap_cells(sig, lo, lo, hi, [glow_block], pad, entry=1, delay=glow),
                    set_own_tag(sig, "Glow", "No", entry=1, delay=glow)]
        brk = 5
        effects += [effect("RunRootInteraction", "TICK", brk, interval=interval, RootInteraction=params["Breakdown"],
                           InteractionType="Secondary", EquipSlot=0),
                    swap_cells("TICK", lo, lo, hi, [glow_block], pad, entry=brk),
                    set_own_tag("TICK", "Glow", "No", entry=brk)]
        effects += cue("TICK", params, "Break", lo, mid, entry=brk)
        for e in effects:
            if e["Event"] == "TICK":
                e["Interval"] = interval
        conditions = [event_tag_is(sig, "Glow", entry=1), tag_is(sig, "Glow", "No", entry=1),
                      condition("ZigHasEffect", "TICK", brk, Effect=params["Ward"]),
                      tag_is("TICK", "Glow", "Yes", entry=brk)]
        more, more_conditions = settle(lo, lambda entry: [
            swap_cells(sig, lo, lo, hi, [glow_block], pad, entry=entry),
            set_own_tag(sig, "Glow", "No", entry=entry),
            set_own_tag(sig, "Glow", "No", entry=entry, delay=glow + 0.25)])
        out.entities.append(volume_entity("%sRune_%d" % (ctx["name"], r), lo, box_size(lo, top), effects + more,
                                          conditions + more_conditions,
                                          {tag: "Rune", rune_key: str(r), "Glow": "No"}, targets=("Npc",)))

    first = sections[0][0][0] if sections else runes[0][0][0]
    ctl = controller_cell(params, (first[0], first[1] + 2, first[2]))

    def clock(label, n, steps):
        """A variant's clock: on Shift at Step k it runs steps[k] (a list of effects for that entry) and moves on to
        k + 1, wrapping to 0; a settle starts it over."""
        effects, conditions = [], []
        for k, run in enumerate(steps):
            entry = 10 + k
            conditions += [event_tag_is(sig, "Shift", entry=entry), tag_is(sig, "Step", str(k), entry=entry)]
            effects += [dict(e, Entry=entry) for e in run]
            # Entries run in order on one signal: the step moves on a moment later, so no later entry reads it.
            effects.append(set_own_tag(sig, "Step", str((k + 1) % len(steps)), entry=entry, delay=SETTLE))
        more, more_conditions = settle(ctl, lambda entry: [
            set_own_tag(sig, "Step", "0", entry=entry),
            set_own_tag(sig, "Step", "0", entry=entry, delay=SETTLE + 0.25)])
        tags = {tag: "Clock", "Step": "0"}
        tags.update(variant_tag(group, n))
        return cell_volume("%s%s_Clock_%d" % (ctx["name"], label, n), ctl, effects + more,
                           conditions + more_conditions, tags)

    for n, order in enumerate(orders, start=1):
        order = check_order(order, len(sections), "ShiftingFloor Orders", n)
        out.entities.append(clock("Floor", n, [[send_signal(sig, section_key, ["Fall"], match_value=str(i))]
                                               for i in order]))
        if runes:
            rune_order = check_order(rune_orders[n - 1], len(runes), "ShiftingFloor RuneOrders", n)
            steps = []
            for j in range(every * len(rune_order)):
                steps.append([send_signal(sig, rune_key, ["Glow"], match_value=str(rune_order[j // every]))]
                             if j % every == 0 else [])
            out.entities.append(clock("Runes", n, steps))
    return out


def arena_quadrants(params, ctx):
    """A boss arena's turning hazard (the Heart of the Deep's last phase): the fight signals Fire with a value, and the
    quarter whose number it names warns for Warn seconds (a haze and a rumble), then bursts for Burst seconds on whoever
    of its Targets stands in it, at most once each (HarmInterval longer than Burst), then rests. The fight paces the
    turn (one Fire per quarter, a few seconds apart); the quarters only answer, so a fight that stops signalling stops
    them. A quarter already warning or bursting ignores a second Fire.

    params: Tag (the script's ArenaTag), Quarters [{Min, Max}] (quarter i answers Fire with the value i + 1), Key (the
    signal key; default Fire), Warn (default 3), Burst (default 0.6), HarmInterval (default 1), Damage (a percent of
    maximum health; default 30), DamageMode (default PercentMax), Effect and EffectSeconds (optional), Push {X, Y, Z}
    (default a knock-up, Y 10), Targets (default ["Player"]), WarnSound, WarnVfx, WarnVfxScale, BurstSound, BurstVfx,
    BurstVfxScale (cues at the quarter's middle)."""
    tag = arena_tag_of(params)
    key = params.get("Key", FIRE)
    if key.endswith(ROOM) or key.endswith(RUN):
        raise ValueError("ArenaQuadrants: the Key is the fight's own signal key, plain")
    warn = float(params.get("Warn", 3.0))
    burst = float(params.get("Burst", 0.6))
    every = float(params.get("HarmInterval", 1.0))
    if every <= burst:
        raise ValueError("ArenaQuadrants: HarmInterval must be longer than Burst, so a burst hurts each player once")
    defaults = {"Damage": 30.0, "DamageMode": "PercentMax", "Push": {"X": 0, "Y": 10, "Z": 0}, "HarmInterval": every}
    harm_effects = harm(params, defaults)
    for e in harm_effects:
        e["Interval"] = every
    out = Logic()
    for i, q in enumerate(params["Quarters"]):
        lo, hi = region(q)
        mid = centre_cell(lo, hi)
        effects = pulse_sequence("SIGNAL_RECEIVED", params, lo, mid, warn, burst) + [dict(e) for e in harm_effects]
        conditions = [event_tag_is("SIGNAL_RECEIVED", key, str(i + 1), entry=1),
                      tag_is("SIGNAL_RECEIVED", "Busy", "Yes", entry=1, inverted=True),
                      tag_is("TICK", "Hot", "Yes")]
        out.entities.append(volume_entity("%sQuarter_%d" % (ctx["name"], i + 1), lo, box_size(lo, hi), effects,
                                          conditions, {tag: "Quarter", "Busy": "No", "Hot": "No"},
                                          targets=params.get("Targets", ["Player"])))
    return out


TEMPLATES = {
    "ArenaDoors": arena_doors,
    "ArenaQuadrants": arena_quadrants,
    "ArenaWake": arena_wake,
    "ShiftingFloor": shifting_floor,
    "CrashPillars": crash_pillars,
    "RoomHint": room_hint,
    "RewardChest": reward_chest,
    "SealedCombat": sealed_combat,
    "LeverDoor": lever_door,
    "FallRescue": fall_rescue,
    "BrazierRelay": brazier_relay,
    "CarriedCoreSource": carried_core_source,
    "CarriedCoreSocket": carried_core_socket,
    "PressurePlates": pressure_plates,
    "MemoryFloor": memory_floor,
    "MemoryClue": memory_clue,
    "RuneOrder": rune_order,
    "RuneClue": rune_clue,
    "LeverLogic": lever_logic,
    "FlameStair": flame_stair,
    "FireFloor": fire_floor,
    "GeyserHall": geyser_hall,
    "Checkpoint": checkpoint,
    "SkillLock": skill_lock,
}


# ---------------------------------------------------------------------------------------------------------------
# Reading volumes back (the builder's report and checks, the tests)


def volumes(entities):
    for ent in entities:
        tv = ent.get("Components", {}).get("TriggerVolume")
        if tv:
            yield ent, tv


def parts(tv):
    """Every effect, rejection effect and condition of a volume."""
    return list(tv.get("Effects") or []) + list(tv.get("RejectionEffects") or []) + list(tv.get("Conditions") or [])


def room_scoped_keys(entities):
    """Every MatchKey and tag key the entities author with @room (for the socket report and the tests)."""
    keys = set()
    for _, tv in volumes(entities):
        for k in (tv.get("Tags") or {}):
            if k.endswith(ROOM):
                keys.add(k)
        for e in parts(tv):
            mk = e.get("MatchKey")
            if mk and mk.endswith(ROOM):
                keys.add(mk)
    return sorted(keys)


def unscoped_keys(entities):
    """MatchKeys that would reach past their room: none of ours may."""
    found = []
    for _, tv in volumes(entities):
        for e in parts(tv):
            mk = e.get("MatchKey")
            if mk and not (mk.endswith(ROOM) or mk.endswith(RUN)):
                found.append((tv.get("Name"), e.get("Type"), mk))
            if e.get("Type") == "TagCondition" and e.get("Source") == "Radius" and not mk:
                found.append((tv.get("Name"), "TagCondition", "(a Radius source with no MatchKey reads every volume)"))
    return found


def world_absolute_uses(entities):
    """Effects that would write to a world-absolute position (none of ours may)."""
    found = []
    for _, tv in volumes(entities):
        for e in parts(tv):
            if "WorldAbsolute" in (e.get("Origin"), e.get("RelativeMode"), e.get("PositionSource")):
                found.append((tv.get("Name"), e.get("Type")))
            if e.get("Type") == "Teleport" and not e.get("RelativeToVolume") and not e.get("RelativeToEntity"):
                found.append((tv.get("Name"), "Teleport"))
    return found


def placed_cells(entities, anchor=(0, 0, 0)):
    """(volume name, cell) for every PlaceBlock target, anchor-relative: the cells a volume writes a block into."""
    out = []
    for ent, tv in volumes(entities):
        pos = ent["Components"]["Transform"]["Position"]
        origin = (pos["X"] - anchor[0], pos["Y"] - anchor[1], pos["Z"] - anchor[2])
        for e in parts(tv):
            if e.get("Type") == "PlaceBlock" and e.get("Origin", "VolumeOrigin") == "VolumeOrigin":
                p = e.get("Position") or vec(0, 0, 0)
                out.append((tv.get("Name"), tuple(int(math.floor(origin[i] + p[k]))
                                                  for i, k in enumerate(("X", "Y", "Z")))))
    return out
