package com.ziggfreed.common.instance.dungeon.layout.asset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.Vec3i;
import com.ziggfreed.common.instance.dungeon.layout.Box;
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.instance.dungeon.layout.DoorSpec;
import com.ziggfreed.common.instance.dungeon.layout.Facing;
import com.ziggfreed.common.instance.dungeon.layout.RoomSpec;

/**
 * One room a dungeon layout can place, as a file: {@code Server/ZiggfreedCommon/DungeonRooms/<Id>.json}, subfolders
 * allowed, native {@code Parent} for every leaf.
 *
 * <pre>{@code
 * {
 *   "Prefab": "Dungeon.Stone.Level_1.Room_Small.*",
 *   "Box": { "Min": { "X": -8, "Y": 0, "Z": -8 }, "Max": { "X": 8, "Y": 9, "Z": 8 } },
 *   "Doors": [ { "At": { "Z": -8 }, "Facing": "North", "Kind": "Stone_Small" },
 *              { "At": { "Z": 8 }, "Facing": "South", "Kind": "Stone_Small" } ],
 *   "Roles": ["Combat"],
 *   "Tags": { "Kit": ["Zc_Kit_Stone"], "Stratum": ["Ruins"] },
 *   "Weight": 1,
 *   "Depth": { "Min": 0.1, "Max": 0.9 },
 *   "Needs": [],
 *   "Keep": { "Markers": false, "Containers": false },
 *   "Fill": "Rock_Stone"
 * }
 * }</pre>
 *
 * <p>{@code Tags} is the engine's own tag map, expanded the engine's way (each key, each value and each
 * {@code Key=Value}), so a plan's {@code Rooms.Tags} and its strata name a room by any of them. The generator reads
 * the folded {@link RoomSpec} ({@link #toSpec()}); door sockets, box and anchor are the prefab's own frame, unrotated.
 */
public final class DungeonRoomAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, DungeonRoomAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/DungeonRooms";

    /** A room's weight when its file says nothing. */
    public static final double DEFAULT_WEIGHT = 1.0;

    /** The authored spelling of a door's kind when its file says nothing. */
    public static final String DEFAULT_KIND = "Default";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String prefab;
    @Nullable private Bounds box;
    @Nullable private Door[] doors;
    @Nullable private String[] roles;
    @Nullable private Double weight;
    @Nullable private Depth depth;
    @Nullable private String[] needs;
    @Nullable private Keep keep;
    @Nullable private String fill;

    public static final AssetBuilderCodec<String, DungeonRoomAsset> CODEC = AssetBuilderCodec.builder(
                    DungeonRoomAsset.class,
                    DungeonRoomAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Prefab", Codec.STRING, false),
                    (a, v) -> a.prefab = v, a -> a.prefab, (a, p) -> a.prefab = p.prefab)
            .documentation("The prefab the room pastes, under Server/Prefabs/ in the engine's dot form, such as "
                    + "Dungeon.Stone.Level_1.Room_Small.Room_A. End it with .* for a pool of every prefab in that "
                    + "folder, each an interchangeable variant the run's seed picks from (vanilla's cave-node "
                    + "syntax).").add()
            .appendInherited(new KeyedCodec<>("Box", Bounds.CODEC, false),
                    (a, v) -> a.box = v, a -> a.box, (a, p) -> a.box = p.box)
            .documentation("The blocks the room covers, relative to its prefab's anchor and unrotated, covering every "
                    + "variant of its pool: no other room is placed over them.").add()
            .appendInherited(new KeyedCodec<>("Doors", new ArrayCodec<>(Door.CODEC, Door[]::new), false),
                    (a, v) -> a.doors = v, a -> a.doors, (a, p) -> a.doors = p.doors)
            .documentation("The room's door sockets. A door joins another room's door of the same Kind facing the "
                    + "opposite way one block out; every door left open takes a Cap room of its kind. This is ONE "
                    + "leaf: authoring it replaces an inherited list whole.").add()
            .appendInherited(new KeyedCodec<>("Roles", Codec.STRING_ARRAY, false),
                    (a, v) -> a.roles = v, a -> a.roles, (a, p) -> a.roles = p.roles)
            .documentation("The beats the room can fill, matched without regard to case: Entry, Corridor, Combat, "
                    + "Puzzle, Cap and any a pack's plan names. A Cap room closes one open door and has exactly "
                    + "one door.").add()
            .appendInherited(new KeyedCodec<>("Weight", Codec.DOUBLE, false),
                    (a, v) -> a.weight = v, a -> a.weight, (a, p) -> a.weight = p.weight)
            .metadata(EditorSchema.defaultValue(DEFAULT_WEIGHT))
            .documentation("How likely the room is picked among the rooms that fit a spot. Unauthored means 1; 0 "
                    + "never picks it.").add()
            .appendInherited(new KeyedCodec<>("Depth", Depth.CODEC, false),
                    (a, v) -> a.depth = v, a -> a.depth, (a, p) -> a.depth = p.depth)
            .documentation("How far along the run's path the room may stand, as fractions from 0 (the entry) to 1 "
                    + "(the last room). A room on a side branch stands where its branch leaves the path.").add()
            .appendInherited(new KeyedCodec<>("Needs", Codec.STRING_ARRAY, false),
                    (a, v) -> a.needs = v, a -> a.needs, (a, p) -> a.needs = p.needs)
            .documentation("Roles a room placed earlier must fill, each on a side branch off a path room before "
                    + "this one: a puzzle's clue, a carried core's source.").add()
            .appendInherited(new KeyedCodec<>("Keep", Keep.CODEC, false),
                    (a, v) -> a.keep = v, a -> a.keep, (a, p) -> a.keep = p.keep)
            .documentation("Which of the prefab's own pieces the painter keeps. Unauthored, neither: the room's "
                    + "logic owns its mobs and rewards.").add()
            .appendInherited(new KeyedCodec<>("Fill", Codec.STRING, false),
                    (a, v) -> a.fill = v, a -> a.fill, (a, p) -> a.fill = p.fill)
            .metadata(EditorSchema.assetRef(BlockType.class))
            .documentation("A block the painter fills every cell of the Box the prefab leaves unlisted with, for a "
                    + "piece cut to sit in solid rock that would show holes in an empty world. Unauthored, the "
                    + "stratum's Fill, else none.").add()
            .build();

    public DungeonRoomAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The room as the generator reads it: folded, every default applied, the engine's tags expanded. */
    @Nonnull
    public RoomSpec toSpec() {
        List<DoorSpec> doorSpecs = new ArrayList<>();
        if (doors != null) {
            for (Door door : doors) {
                if (door != null) {
                    doorSpecs.add(door.toSpec());
                }
            }
        }
        double min = depth == null || depth.min == null ? 0 : depth.min;
        double max = depth == null || depth.max == null ? 1 : depth.max;
        return new RoomSpec(id == null ? "" : id, prefab == null ? "" : prefab,
                new Box(cell(box == null ? null : box.min), cell(box == null ? null : box.max)), doorSpecs,
                roles == null ? List.of() : Arrays.asList(roles), expandedTags(),
                weight == null ? DEFAULT_WEIGHT : weight, min, max, needs == null ? List.of() : Arrays.asList(needs),
                keep != null && Boolean.TRUE.equals(keep.markers), keep != null && Boolean.TRUE.equals(keep.containers),
                fill);
    }

    /**
     * The file's {@code Tags} the engine's way: each key, each value and each {@code Key=Value}, keys in sorted order
     * (the engine keeps them in a hash map) and values as written.
     */
    @Nonnull
    List<String> expandedTags() {
        Map<String, String[]> raw = data == null ? null : data.getRawTags();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String[]> entry : new TreeMap<>(raw).entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            out.add(key.trim());
            if (entry.getValue() != null) {
                for (String value : entry.getValue()) {
                    if (value != null && !value.isBlank()) {
                        out.add(value.trim());
                        out.add(key.trim() + "=" + value.trim());
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static Cell cell(@Nullable Vec3i v) {
        return v == null ? Cell.ZERO : new Cell(v.effectiveX(), v.effectiveY(), v.effectiveZ());
    }

    // ==================== Box ====================

    /** The {@code Box} group: the cells a room covers. */
    public static final class Bounds {

        @Nullable protected Vec3i min;
        @Nullable protected Vec3i max;

        public static final BuilderCodec<Bounds> CODEC = BuilderCodec.builder(Bounds.class, Bounds::new)
                .appendInherited(new KeyedCodec<>("Min", Vec3i.CODEC, false),
                        (o, v) -> o.min = v, o -> o.min, (o, p) -> o.min = p.min)
                .documentation("One corner, the least of each axis, relative to the prefab's anchor. Unauthored "
                        + "axes are 0.").add()
                .appendInherited(new KeyedCodec<>("Max", Vec3i.CODEC, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .documentation("The other corner, the most of each axis, relative to the prefab's anchor; both "
                        + "corners are covered. Unauthored axes are 0.").add()
                .build();

        public Bounds() {
        }
    }

    // ==================== Doors ====================

    /** One door socket. */
    public static final class Door {

        @Nullable protected Vec3i at;
        @Nullable protected String facing;
        @Nullable protected String kind;

        public static final BuilderCodec<Door> CODEC = BuilderCodec.builder(Door.class, Door::new)
                .appendInherited(new KeyedCodec<>("At", Vec3i.CODEC, false),
                        (o, v) -> o.at = v, o -> o.at, (o, p) -> o.at = p.at)
                .documentation("The floor cell at the doorway's centre, just inside the room, relative to the "
                        + "prefab's anchor. Unauthored axes are 0.").add()
                .appendInherited(new KeyedCodec<>("Facing", Codec.STRING, false),
                        (o, v) -> o.facing = v, o -> o.facing, (o, p) -> o.facing = p.facing)
                .metadata(EditorSchema.oneOf(Facing.words()))
                .documentation("The way the door opens out of the room on the engine's compass: North (-Z), East "
                        + "(+X), South (+Z) or West (-X). A door with none never joins.").add()
                .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                        (o, v) -> o.kind = v, o -> o.kind, (o, p) -> o.kind = p.kind)
                .metadata(EditorSchema.defaultValue(DEFAULT_KIND))
                .documentation("The socket's kind, matched without regard to case: only doors of the same kind "
                        + "join, such as a kit's door size. Unauthored means Default.").add()
                .build();

        public Door() {
        }

        @Nonnull
        DoorSpec toSpec() {
            return new DoorSpec(cell(at), Facing.parse(facing), kind == null ? DEFAULT_KIND : kind);
        }
    }

    // ==================== Depth ====================

    /** How far along the path a room may stand. */
    public static final class Depth {

        @Nullable protected Double min;
        @Nullable protected Double max;

        public static final BuilderCodec<Depth> CODEC = BuilderCodec.builder(Depth.class, Depth::new)
                .appendInherited(new KeyedCodec<>("Min", Codec.DOUBLE, false),
                        (o, v) -> o.min = v, o -> o.min, (o, p) -> o.min = p.min)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("The least fraction of the path, 0 to 1. Unauthored means 0.").add()
                .appendInherited(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .metadata(EditorSchema.defaultValue(1.0))
                .documentation("The most fraction of the path, 0 to 1. Unauthored means 1.").add()
                .build();

        public Depth() {
        }
    }

    // ==================== Keep ====================

    /** Which of the prefab's own pieces the painter keeps. */
    public static final class Keep {

        @Nullable protected Boolean markers;
        @Nullable protected Boolean containers;

        public static final BuilderCodec<Keep> CODEC = BuilderCodec.builder(Keep.class, Keep::new)
                .appendInherited(new KeyedCodec<>("Markers", Codec.BOOLEAN, false),
                        (o, v) -> o.markers = v, o -> o.markers, (o, p) -> o.markers = p.markers)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Whether the prefab's own spawn markers are kept. Unauthored means false.").add()
                .appendInherited(new KeyedCodec<>("Containers", Codec.BOOLEAN, false),
                        (o, v) -> o.containers = v, o -> o.containers, (o, p) -> o.containers = p.containers)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Whether the prefab's own containers are kept. Unauthored means false.").add()
                .build();

        public Keep() {
        }
    }
}
