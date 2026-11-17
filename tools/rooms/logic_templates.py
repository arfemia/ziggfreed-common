"""Room logic templates: native TriggerVolume entities in vanilla's prefab shape, stamped by build_room.py.

Each template is a function (params, ctx) returning a Logic: the prefab entity documents
({"Components": {"Transform": ..., "TriggerVolume": ...}}, the shape vanilla's own prefabs carry,
Prefabs/Dungeon/Deepworks/Arena_Containment.prefab.json) and the blocks it stands on (a cell to a palette slot), which
the builder places like any prop. Only native Types are used (TriggerVolumesPlugin.java:538-580), plus three of
CommonLib's own registered into the same codecs: ZigRequires (a condition), ZigCheckpoint and ZigRunMilestone (effects).

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


def milestone(event, milestone_id, entry=0, delay=0.0):
    """CommonLib's ZigRunMilestone: tells the run a beat happened (once per room per run)."""
    return effect("ZigRunMilestone", event, entry, delay, Id=milestone_id)


def message(event, key, entry=0):
    """A chat line to the player who set the volume off: `key` is a lang key the caller passes in."""
    return effect("SendMessage", event, entry, Message=key, Recipient="TriggeringPlayer")


def cue(event, params, prefix, origin, cell, entry=0, delay=0.0):
    """The optional sound and particles a caller names as <prefix>Sound and <prefix>Vfx, at `cell`."""
    out = []
    off = offset_of(origin, cell)
    centre = vec(off[0] + 0.5, off[1] + 0.5, off[2] + 0.5)
    if params.get(prefix + "Sound"):
        out.append(effect("PlaySound", event, entry, delay, SoundEvent=params[prefix + "Sound"],
                          Location="VolumeCenter", Offset=centre))
    if params.get(prefix + "Vfx"):
        out.append(effect("PlayVfx", event, entry, delay, ParticleSystem=params[prefix + "Vfx"], Anchor="Volume",
                          Offset=centre, Scale=1.0, Rotation=vec(0, 0, 0), Duration=0.0))
    return out


def scoped(key, what):
    """Refuse a key that would reach past its room."""
    if not (key.endswith(ROOM) or key.endswith(RUN)):
        raise ValueError("%s %r must end %s (or %s) so it stays inside its room" % (what, key, ROOM, RUN))
    return key


def variant_tag(group, n):
    """The tag the room-logic placer keeps only for the variant the run picks: `<Group>:<n>`, n from 1."""
    return {VARIANT_KEY: "%s:%d" % (group, n)}


def solved(params, ctx, event, origin, entry=0, delay=0.0):
    """What a finished puzzle does, from its params: Opens (indices into the room's doors, each authored Closed with
    Bars) swap their bars out; Signal (a key ending @room) is told `Solved`; Milestone (default Puzzle_Solved, "" for
    none) counts the beat for the run."""
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


def all_at_once(params, ctx, label, cell, key, value, signal_key, member_key, tag, want, also=()):
    """The controller every all-of-them-at-once puzzle shares (braziers, plates, levers): a volume at `cell`, carrying
    `key`, that on a signal carrying `signal_key`, while it is Ready and every volume carrying `member_key` has `tag` at
    `want`, writes Solved, runs `also`, then what solved() makes of the params."""
    effects = [set_own_tag("SIGNAL_RECEIVED", "State", "Solved")] + list(also)
    effects += solved(params, ctx, "SIGNAL_RECEIVED", cell)
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


def pulse_cell(name, lo, hi, key, value, params, harm_effects, warn, burst):
    """One hazard cell. A Fire signal arms it unless it is already busy (several players' controllers can ask at once,
    as in vanilla's Quadrant cells): it warns for `warn` seconds, is hot for `burst` seconds, then rests. Busy and Hot
    walk Yes and No, a change on every write."""
    end = warn + burst
    effects = [set_own_tag("SIGNAL_RECEIVED", "Busy", "Yes", entry=1)]
    effects += cue("SIGNAL_RECEIVED", params, "Warn", lo, lo, entry=1)
    effects += [set_own_tag("SIGNAL_RECEIVED", "Hot", "Yes", entry=1, delay=warn)]
    effects += cue("SIGNAL_RECEIVED", params, "Burst", lo, lo, entry=1, delay=warn)
    effects += [set_own_tag("SIGNAL_RECEIVED", "Hot", "No", entry=1, delay=end),
                set_own_tag("SIGNAL_RECEIVED", "Busy", "No", entry=1, delay=end + SETTLE)]
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


def fall_rescue(params, ctx):
    """The fall rescue: a player who drops into the region is put back at the return cell, facing the way given.

    params: Region {Min, Max} (cells), Return {X, Y, Z} (the cell the player's feet land in), Yaw (degrees; 0 faces
    North, the engine's convention; default 0).
    The teleport's Rotation carries the yaw in X: TeleportEffect.toRotation builds Rotation3f(pitch = Y, yaw = X,
    roll = Z) (TeleportEffect.java:133-138, Rotation3f.java:103), as vanilla's Deepworks fall teleports author it."""
    lo, hi = region(params["Region"])
    ret = cell_of(params["Return"])
    yaw = float(params.get("Yaw", 0.0))
    pos = vec(ret[0] - lo[0] + 0.5, ret[1] - lo[1], ret[2] - lo[2] + 0.5)
    tp = effect("Teleport", "ENTER", Position=pos, World="", ResetVelocity=True, RelativeToEntity=False,
                RelativeToVolume=True, UseRotation=True, Rotation=vec(yaw, 0.0, 0.0))
    return Logic([volume_entity(ctx["name"] + "Rescue", lo, box_size(lo, hi), [tp])])


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
                                    also=[set_tags("SIGNAL_RECEIVED", brazier_key, "Solved", "Yes")]))
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
    effects += cue("ENTER", params, "Fill", lo, socket) + solved(params, ctx, "ENTER", lo)
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
    out.entities.append(all_at_once(params, ctx, "Plates", ctl, key, "Lock", "Pressed", plate_key, "Pressed", "Yes"))
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
        effects = [set_own_tag("ENTER", "State", "Solved")] + solved(params, ctx, "ENTER", flo)
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
                effects += solved(params, ctx, "SIGNAL_RECEIVED", ctl, entry)
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
                                    "Yes"))
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
    the requirement uses it, counting Secret_Found for the run (the MMO pays the skill's XP from it). A player short of
    it is told what opens it.

    params: Lock {Min, Max}, or a list of them (the lock's cells, fitted round a doorway's shape; the template
    places them; a usable block); an entry {Door: n} fills that door's whole opening instead, as the builder measured
    it (a vanilla doorway is rarely a box), so the lock is the room's own way in; Lock_Slot (palette slot; default
    Lock, a block whose Use does nothing, such as CommonLib's Zc_Lock_* blocks), Opened (the slot the cells become; default
    air), Factor (default hytale:stat), Param (the stat a skill's level reads as, upper case after its prefix, such as
    MMO_Level_MINING), Min (the level), or Requires (a whole Requires block instead), HintKey (the lang key naming what
    opens it, required), Level (the level the hint names; default Min), Milestone (default Secret_Found), Signal,
    OpenSound, OpenVfx.
    The lock's volume carries the level as its Level tag, and the engine binds a volume's tags as named params of the
    line it sends, so HintKey's value names it as {Level} (no digit in the lang file): the level is written once, here.
    Opening swaps each Lock piece on its own, so a block of the lock's kind the room already has beside the lock
    stays put."""
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
    if params.get("Requires"):
        requires = params["Requires"]
    else:
        requires = {"Factors": [{"Factor": params.get("Factor", "hytale:stat"), "Param": params["Param"],
                                 "Min": params["Min"]}]}
    if not params.get("HintKey"):
        raise ValueError("SkillLock: HintKey (the lang key naming what opens it) is required")
    effects = [set_own_tag("BLOCK_USED", "State", "Open")]
    effects += [swap_cells("BLOCK_USED", lo, plo, phi, [lock], opened) for plo, phi in pieces]
    effects += cue("BLOCK_USED", params, "Open", lo, lo)
    if params.get("Signal"):
        effects.append(send_signal("BLOCK_USED", scoped(params["Signal"], "Signal"), ["Opened"]))
    ms = params.get("Milestone", "Secret_Found")
    if ms:
        effects.append(milestone("BLOCK_USED", ms))
    conditions = [tag_is("BLOCK_USED", "State", "Locked"),
                  condition("ZigRequires", "BLOCK_USED", Requires=requires)]
    tags = {"State": "Locked"}
    level = params.get("Level", params.get("Min"))
    if level is not None:
        tags["Level"] = "%d" % int(level)
    out = Logic(blocks={c: slot for c in cells})
    out.entities.append(volume_entity(ctx["name"] + "Skill_Lock", lo, box_size(lo, hi), effects, conditions,
                                      tags, rejections=[message("BLOCK_USED", params["HintKey"])]))
    return out


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


TEMPLATES = {
    "RoomHint": room_hint,
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
