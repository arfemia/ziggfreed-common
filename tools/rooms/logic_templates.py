"""Room logic templates: native TriggerVolume entities in vanilla's prefab shape, stamped by build_room.py.

Each template returns prefab entity documents ({"Components": {"Transform": ..., "TriggerVolume": ...}}), the shape
vanilla's own prefabs carry (Prefabs/Dungeon/Deepworks/Arena_Containment.prefab.json). Only native Types are used
(TriggerVolumesPlugin.java:538-580), so a room works on a server running nothing but the engine and zc:
TagCondition, EntityCountCondition, ReplaceBlockType, ModifyTags, TriggerNpcMarkers, SendSignal and Teleport.

Rules every template keeps (procgen-dungeon.md section 3):
- A key that must stay inside one room is written with the suffix `@room` (a volume's tag key and every MatchKey that
  looks for it), so the room-logic placer can rewrite it to the room's own token; nothing here is world-wide.
- No position is world-absolute: block swaps use `Origin: VolumeOrigin` offsets, a teleport `RelativeToVolume`.
- A tag only fires on a change, so a state machine walks distinct values (Ready, Arming, Sealed, Cleared).

Coordinates in and out are anchor-relative block cells; a volume's Transform sits on the corner cell of its box, so
its Box shape runs from (0, 0, 0) to the box size and every offset below is a whole number of blocks.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import prefabio as pio  # noqa: E402

ROOM = "@room"
CENTER_VOLUME = "Volume"


def vec(x, y, z):
    return {"X": float(x), "Y": float(y), "Z": float(z)}


def volume_entity(name, corner, size, effects, conditions=(), tags=None, targets=("Player",)):
    """A TriggerVolume entity whose Box covers `size` cells from the `corner` cell."""
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
    if tags:
        tv["Tags"] = dict(tags)
    return {"Components": {
        "Transform": {"Position": vec(*corner), "Rotation": {"Pitch": 0.0, "Yaw": 0.0, "Roll": 0.0}},
        "TriggerVolume": tv,
    }}


def box_size(lo, hi):
    return (hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1)


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


def tag_is(event, key, value, entry=0):
    """The volume's own tag `key` holds exactly `value`."""
    return condition("TagCondition", event, entry, Source="Self", TagKey=key, Comparison="Exactly",
                     CompareAgainst="Value", TagValue=value, Inverted=False, MatchKey="", MatchValue="",
                     Radius=50.0, Center=CENTER_VOLUME)


def set_own_tag(event, key, value, entry=0, delay=0.0):
    """Set a tag on this volume only (a blank MatchKey targets the triggering volume)."""
    return effect("ModifyTags", event, entry, delay, Operation="Set", TagKey=key, TagValue=value, MatchKey="",
                  MatchValue="", Radius=50.0, Center=CENTER_VOLUME)


def swap_cells(event, origin, lo, hi, from_blocks, to_block, entry=0, delay=0.0):
    """Replace `from_blocks` with `to_block` over the cells lo..hi, offset from the volume's corner cell `origin`.

    Bounds Aabb spans floor(origin + Offset) to floor(origin + Offset + size) (ReplaceBlockTypeEffect.java:458-471),
    so a size of (cells - 1) + 0.5 covers exactly the cells asked for."""
    off = (lo[0] - origin[0], lo[1] - origin[1], lo[2] - origin[2])
    size = [hi[i] - lo[i] + 0.5 for i in range(3)]
    return effect("ReplaceBlockType", event, entry, delay, FromBlockTypes=list(from_blocks), FromBlockState="",
                  ToBlockType=to_block, ToBlockState="", Bounds="Aabb", X=size[0], Y=size[1], Z=size[2],
                  Offset=vec(*off), Origin="VolumeOrigin", Rotation="None", Pitch="None", Roll="None")


# ---------------------------------------------------------------------------------------------------------------
# Templates


def sealed_combat(params, ctx):
    """The sealed combat room: entering seals the listed doorways with bars and wakes the room's manual spawn markers;
    once no living NPC is left inside, the bars drop and the room says so with a signal.

    params: Interior {Min, Max} (the cells the fight happens in), Doors (indices into the spec's Doors; default all),
    Markers (spawn marker ids TriggerNpcMarkers wakes; default every manual marker inside), ArmDelay (seconds before the
    clear check starts, so a slow spawn cannot open the room at once; default 1.5), Interval (seconds between clear
    checks; default 0.5), ClearedSignal (the MatchKey told when the room is cleared; default Cleared@room).
    ctx: doors (the spec's doors with their opening cells), slot (palette lookup), name prefix."""
    lo = pio.xyz_tuple(params["Interior"]["Min"])
    hi = pio.xyz_tuple(params["Interior"]["Max"])
    doors = [ctx["doors"][i] for i in params.get("Doors", range(len(ctx["doors"])))]
    bars = ctx["slot"](params.get("Bars", "Bars"))
    arm = float(params.get("ArmDelay", 1.5))
    interval = float(params.get("Interval", 0.5))
    cleared = params.get("ClearedSignal", "Cleared" + ROOM)
    effects = []
    for d in doors:
        effects.append(swap_cells("ENTER", lo, d["OpeningMin"], d["OpeningMax"], ["Empty"], bars, entry=1))
    effects.append(set_own_tag("ENTER", "State", "Arming", entry=1))
    markers = params.get("Markers") or [None]
    for m in markers:
        fields = {"Range": 0.0, "MatchTag": "", "Radius": 0.0, "Center": CENTER_VOLUME}
        if m:
            fields["MarkerType"] = m
        effects.append(effect("TriggerNpcMarkers", "ENTER", 1, **fields))
    effects.append(set_own_tag("ENTER", "State", "Sealed", entry=1, delay=arm))
    for d in doors:
        effects.append(swap_cells("TICK", lo, d["OpeningMin"], d["OpeningMax"], [bars], "Empty", entry=2,
                                  delay=0.0))
    effects.append(set_own_tag("TICK", "State", "Cleared", entry=2))
    effects.append(effect("SendSignal", "TICK", 2, MatchKey=cleared, MatchValue="", Radius=64.0, Center=CENTER_VOLUME,
                          SignalKeys=["Room_Cleared"]))
    for e in effects:
        if e["Event"] == "TICK":
            e["Interval"] = interval
    conditions = [
        tag_is("ENTER", "State", "Ready", entry=1),
        tag_is("TICK", "State", "Sealed", entry=2),
        condition("EntityCountCondition", "TICK", 2, Comparison="Exactly", Count=0),
    ]
    tags = {"State": "Ready", "Combat" + ROOM: "Arena"}
    return [volume_entity(ctx["name"] + "Combat", lo, box_size(lo, hi), effects, conditions, tags)]


def lever_door(params, ctx):
    """The lever door: a doorway the spec closes with bars opens when a player uses the lever beside it, once.

    params: Lever {X, Y, Z} (the lever block's cell; the spec's Props place the block), Door (index into the spec's
    Doors; that door must be authored Closed), Key (the tag key the door listens on; default Door@room).
    The lever's volume covers the lever cell and the cell above it, the door's volume the bars."""
    lever = pio.xyz_tuple(params["Lever"])
    door = ctx["doors"][params["Door"]]
    key = params.get("Key", "Door" + ROOM)
    bars = ctx["slot"](params.get("Bars", "Bars"))
    lever_volume = volume_entity(
        ctx["name"] + "Lever", lever, (1, 2, 1),
        [effect("SendSignal", "BLOCK_USED", MatchKey=key, MatchValue="", Radius=64.0, Center=CENTER_VOLUME,
                SignalKeys=["Open"]),
         set_own_tag("BLOCK_USED", "State", "Pulled")],
        [tag_is("BLOCK_USED", "State", "Ready")],
        {"State": "Ready"})
    lo, hi = door["OpeningMin"], door["OpeningMax"]
    door_volume = volume_entity(
        ctx["name"] + "Door", lo, box_size(lo, hi),
        [swap_cells("SIGNAL_RECEIVED", lo, lo, hi, [bars], "Empty")],
        (), {key: "Door"})
    return [lever_volume, door_volume]


def fall_rescue(params, ctx):
    """The fall rescue: a player who drops into the region is put back at the return cell, facing the way given.

    params: Region {Min, Max} (cells), Return {X, Y, Z} (the cell the player's feet land in), Yaw (degrees; 0 faces
    North, the engine's convention; default 0).
    The teleport's Rotation carries the yaw in X: TeleportEffect.toRotation builds Rotation3f(pitch = Y, yaw = X,
    roll = Z) (TeleportEffect.java:133-138, Rotation3f.java:103), as vanilla's Deepworks fall teleports author it."""
    lo = pio.xyz_tuple(params["Region"]["Min"])
    hi = pio.xyz_tuple(params["Region"]["Max"])
    ret = pio.xyz_tuple(params["Return"])
    yaw = float(params.get("Yaw", 0.0))
    pos = vec(ret[0] - lo[0] + 0.5, ret[1] - lo[1], ret[2] - lo[2] + 0.5)
    tp = effect("Teleport", "ENTER", Position=pos, World="", ResetVelocity=True, RelativeToEntity=False,
                RelativeToVolume=True, UseRotation=True, Rotation=vec(yaw, 0.0, 0.0))
    return [volume_entity(ctx["name"] + "Rescue", lo, box_size(lo, hi), [tp])]


TEMPLATES = {
    "SealedCombat": sealed_combat,
    "LeverDoor": lever_door,
    "FallRescue": fall_rescue,
}


def room_scoped_keys(entities):
    """Every MatchKey and tag key the entities author with @room (for the socket report and the tests)."""
    keys = set()
    for ent in entities:
        tv = ent.get("Components", {}).get("TriggerVolume")
        if not tv:
            continue
        for k in (tv.get("Tags") or {}):
            if k.endswith(ROOM):
                keys.add(k)
        for e in list(tv.get("Effects") or []) + list(tv.get("Conditions") or []):
            mk = e.get("MatchKey")
            if mk and mk.endswith(ROOM):
                keys.add(mk)
    return sorted(keys)


def world_absolute_uses(entities):
    """Effects that would write to a world-absolute position (none of ours may)."""
    found = []
    for ent in entities:
        tv = ent.get("Components", {}).get("TriggerVolume") or {}
        for e in list(tv.get("Effects") or []) + list(tv.get("RejectionEffects") or []):
            if e.get("Origin") == "WorldAbsolute" or e.get("RelativeMode") == "WorldAbsolute":
                found.append((tv.get("Name"), e.get("Type")))
            if e.get("Type") == "Teleport" and not e.get("RelativeToVolume") and not e.get("RelativeToEntity"):
                found.append((tv.get("Name"), "Teleport"))
    return found
