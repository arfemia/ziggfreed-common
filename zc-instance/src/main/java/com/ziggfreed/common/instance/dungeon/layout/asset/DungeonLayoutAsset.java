package com.ziggfreed.common.instance.dungeon.layout.asset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan;

/**
 * One layout plan, as a file: {@code Server/ZiggfreedCommon/DungeonLayouts/<Id>.json}, native {@code Parent} for every
 * leaf. It says which rooms a run draws on, the beats of its path, the connectors between them, its side branches
 * and the box the layout must fit; the run's seed does the rest ({@code layout/LayoutGenerator}).
 *
 * <pre>{@code
 * {
 *   "Rooms": { "Tags": ["Zc_Kit_Stone"] },
 *   "Strata": [ { "AnyOf": ["Ruins", "Crypt"], "Fill": "Rock_Stone" }, { "AnyOf": ["Void"] } ],
 *   "Path": [ { "Role": "Entry" }, { "Role": "Combat", "Count": { "Min": 2, "Max": 3 } }, { "Role": "Puzzle" },
 *             { "Role": "Checkpoint" }, { "Role": "Boss", "Stratum": 1 } ],
 *   "Links": { "Role": "Corridor", "Count": { "Min": 0, "Max": 2 } },
 *   "Branches": [ { "Role": "Treasure", "Count": { "Min": 0, "Max": 2 }, "Gate": "Puzzle" } ],
 *   "Bounds": { "X": 512, "Y": 128, "Z": 512, "Entry": { "Y": 120 } },
 *   "MaxTries": 64
 * }
 * }</pre>
 *
 * <p>The generator reads the folded {@link LayoutPlan} ({@link #toPlan()}).
 */
public final class DungeonLayoutAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, DungeonLayoutAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/DungeonLayouts";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Rooms rooms;
    @Nullable private Stratum[] strata;
    @Nullable private Beat[] path;
    @Nullable private Links links;
    @Nullable private Branch[] branches;
    @Nullable private Bounds bounds;
    @Nullable private Integer maxTries;

    public static final AssetBuilderCodec<String, DungeonLayoutAsset> CODEC = AssetBuilderCodec.builder(
                    DungeonLayoutAsset.class,
                    DungeonLayoutAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Rooms", Rooms.CODEC, false),
                    (a, v) -> a.rooms = v, a -> a.rooms, (a, p) -> a.rooms = p.rooms)
            .documentation("Which DungeonRooms the plan draws on.").add()
            .appendInherited(new KeyedCodec<>("Strata", new ArrayCodec<>(Stratum.CODEC, Stratum[]::new), false),
                    (a, v) -> a.strata = v, a -> a.strata, (a, p) -> a.strata = p.strata)
            .documentation("The themed layers a run descends through, in order: for each, the run's seed picks one "
                    + "tag from its AnyOf, and a beat's rooms must carry its stratum's tag. Unauthored, rooms are "
                    + "not filtered by stratum. This is ONE leaf: authoring it replaces an inherited list whole.").add()
            .appendInherited(new KeyedCodec<>("Path", new ArrayCodec<>(Beat.CODEC, Beat[]::new), false),
                    (a, v) -> a.path = v, a -> a.path, (a, p) -> a.path = p.path)
            .documentation("The beats of the run's path in order, the first one the entry. This is ONE leaf: "
                    + "authoring it replaces an inherited list whole.").add()
            .appendInherited(new KeyedCodec<>("Links", Links.CODEC, false),
                    (a, v) -> a.links = v, a -> a.links, (a, p) -> a.links = p.links)
            .documentation("The connector rooms put between two beats. Unauthored, beats join directly.").add()
            .appendInherited(new KeyedCodec<>("Branches", new ArrayCodec<>(Branch.CODEC, Branch[]::new), false),
                    (a, v) -> a.branches = v, a -> a.branches, (a, p) -> a.branches = p.branches)
            .documentation("Side branches off the path, each one room behind an optional gate room. This is ONE "
                    + "leaf: authoring it replaces an inherited list whole.").add()
            .appendInherited(new KeyedCodec<>("Bounds", Bounds.CODEC, false),
                    (a, v) -> a.bounds = v, a -> a.bounds, (a, p) -> a.bounds = p.bounds)
            .documentation("The box every room of a layout stands in, and where in it the entry stands. "
                    + "Unauthored means 512 by 128 by 512 blocks with the entry at its centre.").add()
            .appendInherited(new KeyedCodec<>("MaxTries", Codec.INTEGER, false),
                    (a, v) -> a.maxTries = v, a -> a.maxTries, (a, p) -> a.maxTries = p.maxTries)
            .metadata(EditorSchema.defaultValue((long) LayoutPlan.DEFAULT_MAX_TRIES))
            .documentation("How many seeds a run tries before it relaxes the plan (optional branches dropped, then "
                    + "every count at its least), and again at each relaxed step. Unauthored means 64.").add()
            .build();

    public DungeonLayoutAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The plan as the generator reads it: folded, every default applied. */
    @Nonnull
    public LayoutPlan toPlan() {
        List<LayoutPlan.Stratum> strataList = new ArrayList<>();
        if (strata != null) {
            for (Stratum stratum : strata) {
                strataList.add(stratum == null ? new LayoutPlan.Stratum(List.of(), null)
                        : new LayoutPlan.Stratum(stratum.anyOf == null ? List.of() : Arrays.asList(stratum.anyOf),
                                stratum.fill));
            }
        }
        List<LayoutPlan.Beat> beats = new ArrayList<>();
        if (path != null) {
            for (Beat beat : path) {
                if (beat != null) {
                    beats.add(new LayoutPlan.Beat(beat.role == null ? "" : beat.role, Count.of(beat.count),
                            beat.stratum == null ? -1 : beat.stratum));
                }
            }
        }
        List<LayoutPlan.Branch> branchList = new ArrayList<>();
        if (branches != null) {
            for (Branch branch : branches) {
                if (branch != null) {
                    branchList.add(new LayoutPlan.Branch(branch.role == null ? "" : branch.role, Count.of(branch.count),
                            branch.chance == null ? 1 : branch.chance, branch.gate));
                }
            }
        }
        LayoutPlan.Links linkPlan = links == null || links.role == null || links.role.isBlank() ? null
                : new LayoutPlan.Links(links.role, Count.of(links.count));
        Cell size = LayoutPlan.DEFAULT_BOUNDS;
        Cell entry = null;
        if (bounds != null) {
            size = new Cell(size(bounds.x, size.x()), size(bounds.y, size.y()), size(bounds.z, size.z()));
            if (bounds.entry != null) {
                Vec3i at = bounds.entry;
                entry = new Cell(at.getX() == null ? size.x() / 2 : at.getX(),
                        at.getY() == null ? size.y() / 2 : at.getY(), at.getZ() == null ? size.z() / 2 : at.getZ());
            }
        }
        return new LayoutPlan(id == null ? "" : id,
                rooms == null || rooms.tags == null ? List.of() : Arrays.asList(rooms.tags), strataList, beats,
                linkPlan, branchList, size, entry, maxTries == null ? LayoutPlan.DEFAULT_MAX_TRIES : maxTries);
    }

    private static int size(@Nullable Integer authored, int fallback) {
        return authored == null || authored <= 0 ? fallback : authored;
    }

    // ==================== Rooms ====================

    /** Which rooms the plan draws on. */
    public static final class Rooms {

        @Nullable protected String[] tags;

        public static final BuilderCodec<Rooms> CODEC = BuilderCodec.builder(Rooms.class, Rooms::new)
                .appendInherited(new KeyedCodec<>("Tags", Codec.STRING_ARRAY, false),
                        (o, v) -> o.tags = v, o -> o.tags, (o, p) -> o.tags = p.tags)
                .documentation("Tags every room the plan places must carry, matched without regard to case against "
                        + "a room's Tags as the engine expands them (a key, a value, or Key=Value). Unauthored, "
                        + "every room may be placed.").add()
                .build();

        public Rooms() {
        }
    }

    // ==================== Strata ====================

    /** One stratum: the tags the seed picks one from, and the block its rooms' unlisted cells are filled with. */
    public static final class Stratum {

        @Nullable protected String[] anyOf;
        @Nullable protected String fill;

        public static final BuilderCodec<Stratum> CODEC = BuilderCodec.builder(Stratum.class, Stratum::new)
                .appendInherited(new KeyedCodec<>("AnyOf", Codec.STRING_ARRAY, false),
                        (o, v) -> o.anyOf = v, o -> o.anyOf, (o, p) -> o.anyOf = p.anyOf)
                .documentation("The tags the run's seed picks one from for this stratum, such as a kit or a "
                        + "theme. Unauthored or empty, the stratum lets any room in.").add()
                .appendInherited(new KeyedCodec<>("Fill", Codec.STRING, false),
                        (o, v) -> o.fill = v, o -> o.fill, (o, p) -> o.fill = p.fill)
                .metadata(EditorSchema.assetRef(BlockType.class))
                .documentation("A block the painter fills the cells a room's prefab leaves unlisted inside its Box "
                        + "with, for each room of this stratum whose file names no Fill of its own. Unauthored, "
                        + "none.").add()
                .build();

        public Stratum() {
        }
    }

    // ==================== Bounds ====================

    /** The box a layout stands in, and the entry's place in it. */
    public static final class Bounds {

        @Nullable protected Integer x;
        @Nullable protected Integer y;
        @Nullable protected Integer z;
        @Nullable protected Vec3i entry;

        public static final BuilderCodec<Bounds> CODEC = BuilderCodec.builder(Bounds.class, Bounds::new)
                .appendInherited(new KeyedCodec<>("X", Codec.INTEGER, false),
                        (o, v) -> o.x = v, o -> o.x, (o, p) -> o.x = p.x)
                .metadata(EditorSchema.defaultValue((long) LayoutPlan.DEFAULT_BOUNDS.x()))
                .documentation("The box's size along X in blocks. Unauthored or 0 means 512.").add()
                .appendInherited(new KeyedCodec<>("Y", Codec.INTEGER, false),
                        (o, v) -> o.y = v, o -> o.y, (o, p) -> o.y = p.y)
                .metadata(EditorSchema.defaultValue((long) LayoutPlan.DEFAULT_BOUNDS.y()))
                .documentation("The box's size along Y in blocks. Unauthored or 0 means 128.").add()
                .appendInherited(new KeyedCodec<>("Z", Codec.INTEGER, false),
                        (o, v) -> o.z = v, o -> o.z, (o, p) -> o.z = p.z)
                .metadata(EditorSchema.defaultValue((long) LayoutPlan.DEFAULT_BOUNDS.z()))
                .documentation("The box's size along Z in blocks. Unauthored or 0 means 512.").add()
                .appendInherited(new KeyedCodec<>("Entry", Vec3i.CODEC, false),
                        (o, v) -> o.entry = v, o -> o.entry, (o, p) -> o.entry = p.entry)
                .documentation("The cell the entry room's anchor stands on, in blocks from the box's least corner "
                        + "(0 to the size less one on each axis), such as near the top for a dungeon that "
                        + "descends. An unauthored axis is the box's centre on that axis. One outside the box is "
                        + "reported, and the plan makes no layout.").add()
                .build();

        public Bounds() {
        }
    }

    // ==================== Count ====================

    /** A count range, shared by beats, connectors and branches. */
    public static final class Count {

        @Nullable protected Integer min;
        @Nullable protected Integer max;

        public static final BuilderCodec<Count> CODEC = BuilderCodec.builder(Count.class, Count::new)
                .appendInherited(new KeyedCodec<>("Min", Codec.INTEGER, false),
                        (o, v) -> o.min = v, o -> o.min, (o, p) -> o.min = p.min)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("The least count. Unauthored means 1.").add()
                .appendInherited(new KeyedCodec<>("Max", Codec.INTEGER, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .documentation("The most count, the run's seed picking between Min and this. Unauthored means the "
                        + "same as Min (so no default is declared: it follows Min); less than Min means Min.").add()
                .build();

        public Count() {
        }

        /** {@code count} folded; unauthored means exactly one. */
        @Nonnull
        static LayoutPlan.Count of(@Nullable Count count) {
            if (count == null) {
                return LayoutPlan.Count.ONE;
            }
            int least = count.min == null ? 1 : count.min;
            return new LayoutPlan.Count(least, count.max == null ? least : count.max);
        }
    }

    // ==================== Path ====================

    /** One beat of the path. */
    public static final class Beat {

        @Nullable protected String role;
        @Nullable protected Count count;
        @Nullable protected Integer stratum;

        public static final BuilderCodec<Beat> CODEC = BuilderCodec.builder(Beat.class, Beat::new)
                .appendInherited(new KeyedCodec<>("Role", Codec.STRING, false),
                        (o, v) -> o.role = v, o -> o.role, (o, p) -> o.role = p.role)
                .documentation("The role its rooms fill, matched without regard to case against a room's "
                        + "Roles.").add()
                .appendInherited(new KeyedCodec<>("Count", Count.CODEC, false),
                        (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                .documentation("How many rooms in a row fill the beat. Unauthored means 1.").add()
                .appendInherited(new KeyedCodec<>("Stratum", Codec.INTEGER, false),
                        (o, v) -> o.stratum = v, o -> o.stratum, (o, p) -> o.stratum = p.stratum)
                .documentation("The index in Strata (from 0) its rooms come from. Unauthored means the previous "
                        + "beat's; the first beat's is 0.").add()
                .build();

        public Beat() {
        }
    }

    // ==================== Links ====================

    /** The connectors between beats. */
    public static final class Links {

        @Nullable protected String role;
        @Nullable protected Count count;

        public static final BuilderCodec<Links> CODEC = BuilderCodec.builder(Links.class, Links::new)
                .appendInherited(new KeyedCodec<>("Role", Codec.STRING, false),
                        (o, v) -> o.role = v, o -> o.role, (o, p) -> o.role = p.role)
                .documentation("The role of the connector rooms, such as Corridor.").add()
                .appendInherited(new KeyedCodec<>("Count", Count.CODEC, false),
                        (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                .documentation("How many connectors stand between two beats. Unauthored means 1. A connector "
                        + "between two strata may carry either stratum's tag.").add()
                .build();

        public Links() {
        }
    }

    // ==================== Branches ====================

    /** One kind of side branch. */
    public static final class Branch {

        @Nullable protected String role;
        @Nullable protected Count count;
        @Nullable protected Double chance;
        @Nullable protected String gate;

        public static final BuilderCodec<Branch> CODEC = BuilderCodec.builder(Branch.class, Branch::new)
                .appendInherited(new KeyedCodec<>("Role", Codec.STRING, false),
                        (o, v) -> o.role = v, o -> o.role, (o, p) -> o.role = p.role)
                .documentation("The role of the room at the branch's end, such as Treasure.").add()
                .appendInherited(new KeyedCodec<>("Count", Count.CODEC, false),
                        (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                .documentation("How many such branches a run gets. Unauthored means 1.").add()
                .appendInherited(new KeyedCodec<>("Chance", Codec.DOUBLE, false),
                        (o, v) -> o.chance = v, o -> o.chance, (o, p) -> o.chance = p.chance)
                .metadata(EditorSchema.defaultValue(1.0))
                .documentation("The chance, 0 to 1, each of those branches is made. Unauthored means 1. A branch "
                        + "whose Count may be 0 or that is left to chance is the first thing dropped when a run "
                        + "cannot fit the plan.").add()
                .appendInherited(new KeyedCodec<>("Gate", Codec.STRING, false),
                        (o, v) -> o.gate = v, o -> o.gate, (o, p) -> o.gate = p.gate)
                .documentation("The role of a room placed where the branch leaves the path, before its end room, "
                        + "such as a puzzle guarding a treasure. Unauthored, the end room joins the path "
                        + "directly.").add()
                .build();

        public Branch() {
        }
    }
}
