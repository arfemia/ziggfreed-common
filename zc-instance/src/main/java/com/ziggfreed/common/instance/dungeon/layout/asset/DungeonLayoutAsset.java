package com.ziggfreed.common.instance.dungeon.layout.asset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
import com.ziggfreed.common.instance.dungeon.room.RoomFoes;

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
 *   "MaxTries": 64,
 *   "Apart": [ { "Tag": "Arena", "Blocks": 100 } ],
 *   "MaxPerRun": [ { "Role": "Puzzle", "Max": 1 } ]
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
    @Nullable private Apart[] apart;
    @Nullable private MaxPerRun[] maxPerRun;
    @Nullable private Roster[] rosters;
    @Nullable private FoeRule[] foes;
    @Nullable private String bars;

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
            .appendInherited(new KeyedCodec<>("Apart", new ArrayCodec<>(Apart.CODEC, Apart[]::new), false),
                    (a, v) -> a.apart = v, a -> a.apart, (a, p) -> a.apart = p.apart)
            .documentation("Distances rooms sharing a tag keep: two rooms that both carry a rule's Tag stand with "
                    + "their nearest blocks at least its Blocks apart, such as two boss arenas whose fights reach "
                    + "far round them. A layout that cannot keep one is retried on another seed, and relaxing the "
                    + "plan never loosens one. Unauthored, rooms only never share a block. This is ONE leaf: "
                    + "authoring it replaces an inherited list whole.").add()
            .appendInherited(new KeyedCodec<>("MaxPerRun", new ArrayCodec<>(MaxPerRun.CODEC, MaxPerRun[]::new), false),
                    (a, v) -> a.maxPerRun = v, a -> a.maxPerRun, (a, p) -> a.maxPerRun = p.maxPerRun)
            .documentation("How many times one room may stand in a run: each room a rule matches (by its Role, its "
                    + "Tag, or both) is placed at most Max times, counted per room, so a run of puzzles meets every "
                    + "puzzle it can before one repeats. A layout that cannot keep one is retried on another seed, "
                    + "and relaxing the plan never loosens one, so keep each beat's count within the rooms that can "
                    + "fill it. Unauthored, a room may stand any number of times. This is ONE leaf: authoring it "
                    + "replaces an inherited list whole.").add()
            .appendInherited(new KeyedCodec<>("Rosters", new ArrayCodec<>(Roster.CODEC, Roster[]::new), false),
                    (a, v) -> a.rosters = v, a -> a.rosters, (a, p) -> a.rosters = p.rosters)
            .documentation("The creatures a run's rooms raise, by the rooms' tags: a room draws on the first roster "
                    + "whose AnyOf names one of its tags, so each stratum or kit fights with its own. The rooms that "
                    + "raise them, and how many, are Foes. Unauthored, no room raises any. This is ONE leaf: "
                    + "authoring it replaces an inherited list whole, so a tier's plan with Parent shares its "
                    + "rosters by writing none.").add()
            .appendInherited(new KeyedCodec<>("Foes", new ArrayCodec<>(FoeRule.CODEC, FoeRule[]::new), false),
                    (a, v) -> a.foes = v, a -> a.foes, (a, p) -> a.foes = p.foes)
            .documentation("Which rooms raise foes from the Rosters and how many: each rule names the role a room "
                    + "fills in the run and sizes its foes for the party. A room with no rule, or whose tags fit no "
                    + "roster, raises none. This is ONE leaf: authoring it replaces an inherited list whole, so "
                    + "each tier's plan writes its own counts.").add()
            .appendInherited(new KeyedCodec<>("Bars", Codec.STRING, false),
                    (a, v) -> a.bars = v, a -> a.bars, (a, p) -> a.bars = p.bars)
            .metadata(EditorSchema.assetRef(BlockType.class))
            .documentation("The block a sealed room's doorways close with while its foes stand, filling only the "
                    + "air of each doorway. Unauthored, a sealed room closes no doorway and only counts its "
                    + "clear.").add()
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
        List<LayoutPlan.Apart> apartList = new ArrayList<>();
        if (apart != null) {
            for (Apart rule : apart) {
                if (rule != null && rule.tag != null && rule.blocks != null) {
                    apartList.add(new LayoutPlan.Apart(rule.tag, rule.blocks));
                }
            }
        }
        List<LayoutPlan.MaxPerRun> maxList = new ArrayList<>();
        if (maxPerRun != null) {
            for (MaxPerRun rule : maxPerRun) {
                if (rule != null) {
                    maxList.add(new LayoutPlan.MaxPerRun(rule.role, rule.tag, rule.max == null ? 1 : rule.max));
                }
            }
        }
        return new LayoutPlan(id == null ? "" : id,
                rooms == null || rooms.tags == null ? List.of() : Arrays.asList(rooms.tags), strataList, beats,
                linkPlan, branchList, size, entry, maxTries == null ? LayoutPlan.DEFAULT_MAX_TRIES : maxTries,
                apartList, maxList);
    }

    private static int size(@Nullable Integer authored, int fallback) {
        return authored == null || authored <= 0 ? fallback : authored;
    }

    /** The plan's foes as the room logic reads them ({@code Rosters}, {@code Foes}, {@code Bars}): folded. */
    @Nonnull
    public RoomFoes.Plan foePlan() {
        List<RoomFoes.Roster> rosterList = new ArrayList<>();
        if (rosters != null) {
            for (Roster roster : rosters) {
                if (roster != null) {
                    rosterList.add(new RoomFoes.Roster(listOf(roster.anyOf), listOf(roster.front),
                            listOf(roster.ranged), listOf(roster.elite), new HashSet<>(listOf(roster.partyScaled))));
                }
            }
        }
        List<RoomFoes.Rule> ruleList = new ArrayList<>();
        if (foes != null) {
            for (FoeRule rule : foes) {
                if (rule != null && rule.role != null && !rule.role.isBlank()) {
                    ruleList.add(new RoomFoes.Rule(rule.role, rule.sealed == null || rule.sealed,
                            rule.count == null ? 0 : rule.count,
                            rule.perExtraPlayer == null ? 0.0 : rule.perExtraPlayer,
                            rule.elite == null ? 0.0 : rule.elite));
                }
            }
        }
        return new RoomFoes.Plan(rosterList, ruleList, bars);
    }

    @Nonnull
    private static List<String> listOf(@Nullable String[] values) {
        return values == null ? List.of() : Arrays.asList(values);
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

    // ==================== Apart ====================

    /** One distance rooms sharing a tag keep. */
    public static final class Apart {

        @Nullable protected String tag;
        @Nullable protected Integer blocks;

        public static final BuilderCodec<Apart> CODEC = BuilderCodec.builder(Apart.class, Apart::new)
                .appendInherited(new KeyedCodec<>("Tag", Codec.STRING, false),
                        (o, v) -> o.tag = v, o -> o.tag, (o, p) -> o.tag = p.tag)
                .documentation("The tag the rule binds, matched without regard to case against a room's Tags as "
                        + "the engine expands them (a key, a value, or Key=Value): Arena binds every room with an "
                        + "Arena tag, whatever its value. A rule with no Tag binds nothing.").add()
                .appendInherited(new KeyedCodec<>("Blocks", Codec.INTEGER, false),
                        (o, v) -> o.blocks = v, o -> o.blocks, (o, p) -> o.blocks = p.blocks)
                .documentation("The least distance, in blocks, between the nearest blocks of two rooms that both "
                        + "carry the Tag. A rule with no Blocks, or 0, binds nothing.").add()
                .build();

        public Apart() {
        }
    }

    // ==================== MaxPerRun ====================

    /** How many times each room a rule matches may stand in one run. */
    public static final class MaxPerRun {

        @Nullable protected String role;
        @Nullable protected String tag;
        @Nullable protected Integer max;

        public static final BuilderCodec<MaxPerRun> CODEC = BuilderCodec.builder(MaxPerRun.class, MaxPerRun::new)
                .appendInherited(new KeyedCodec<>("Role", Codec.STRING, false),
                        (o, v) -> o.role = v, o -> o.role, (o, p) -> o.role = p.role)
                .documentation("The role a room must fill for the rule to count it, matched without regard to case "
                        + "against its Roles, such as Puzzle. Unauthored, any role.").add()
                .appendInherited(new KeyedCodec<>("Tag", Codec.STRING, false),
                        (o, v) -> o.tag = v, o -> o.tag, (o, p) -> o.tag = p.tag)
                .documentation("A tag a room must carry for the rule to count it, matched as a plan's Rooms.Tags "
                        + "are (a key, a value, or Key=Value). Unauthored, any tags. A rule naming neither a Role nor "
                        + "a Tag binds nothing.").add()
                .appendInherited(new KeyedCodec<>("Max", Codec.INTEGER, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("How many times each matched room may stand in one run. Unauthored, or less than 1, "
                        + "means 1.").add()
                .build();

        public MaxPerRun() {
        }
    }

    // ==================== Rosters ====================

    /** The creatures the rooms with any of some tags raise. */
    public static final class Roster {

        @Nullable protected String[] anyOf;
        @Nullable protected String[] front;
        @Nullable protected String[] ranged;
        @Nullable protected String[] elite;
        @Nullable protected String[] partyScaled;

        public static final BuilderCodec<Roster> CODEC = BuilderCodec.builder(Roster.class, Roster::new)
                .appendInherited(new KeyedCodec<>("AnyOf", Codec.STRING_ARRAY, false),
                        (o, v) -> o.anyOf = v, o -> o.anyOf, (o, p) -> o.anyOf = p.anyOf)
                .documentation("The room tags this roster serves, matched as a plan's Rooms.Tags are (a key, a "
                        + "value, or Key=Value), such as Kit=Cursed_Crypt. A room takes the first roster naming one "
                        + "of its tags. Unauthored or empty, every room.").add()
                .appendInherited(new KeyedCodec<>("Front", Codec.STRING_ARRAY, false),
                        (o, v) -> o.front = v, o -> o.front, (o, p) -> o.front = p.front)
                .metadata(EditorSchema.assetRef("NPCRole"))
                .documentation("The roles most of a room's foes are drawn from, by NPC role id, each pick as likely "
                        + "as the next.").add()
                .appendInherited(new KeyedCodec<>("Ranged", Codec.STRING_ARRAY, false),
                        (o, v) -> o.ranged = v, o -> o.ranged, (o, p) -> o.ranged = p.ranged)
                .metadata(EditorSchema.assetRef("NPCRole"))
                .documentation("The roles every fourth foe is drawn from: archers, casters, fliers. Unauthored, every "
                        + "foe comes from Front.").add()
                .appendInherited(new KeyedCodec<>("Elite", Codec.STRING_ARRAY, false),
                        (o, v) -> o.elite = v, o -> o.elite, (o, p) -> o.elite = p.elite)
                .metadata(EditorSchema.assetRef("NPCRole"))
                .documentation("The roles a room's one elite is drawn from, when its rule's Elite chance comes up. "
                        + "Unauthored, a room never has one.").add()
                .appendInherited(new KeyedCodec<>("PartyScaled", Codec.STRING_ARRAY, false),
                        (o, v) -> o.partyScaled = v, o -> o.partyScaled, (o, p) -> o.partyScaled = p.partyScaled)
                .metadata(EditorSchema.assetRef("NPCRole"))
                .documentation("Roles that already grow with the party by themselves (the game's own dungeon goblins "
                        + "count the players near them as they first fight): never drawn for a foe a bigger party "
                        + "adds, so a party never pays twice.").add()
                .build();

        public Roster() {
        }
    }

    // ==================== Foes ====================

    /** How the rooms filling one role raise their foes. */
    public static final class FoeRule {

        @Nullable protected String role;
        @Nullable protected Boolean sealed;
        @Nullable protected Integer count;
        @Nullable protected Double perExtraPlayer;
        @Nullable protected Double elite;

        public static final BuilderCodec<FoeRule> CODEC = BuilderCodec.builder(FoeRule.class, FoeRule::new)
                .appendInherited(new KeyedCodec<>("Role", Codec.STRING, false),
                        (o, v) -> o.role = v, o -> o.role, (o, p) -> o.role = p.role)
                .documentation("The role a room fills in the run, such as Combat or Junction. A rule with none "
                        + "binds nothing.").add()
                .appendInherited(new KeyedCodec<>("Sealed", Codec.BOOLEAN, false),
                        (o, v) -> o.sealed = v, o -> o.sealed, (o, p) -> o.sealed = p.sealed)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("True: the first player in seals the room's doorways (with the plan's Bars) and wakes "
                        + "its foes, and the doorways open, and the run counts Room_Cleared, once none of them "
                        + "stands; if every player leaves the room first, its doorways open anyway. False: an open "
                        + "pack that wakes as the first player comes in. Unauthored means true.").add()
                .appendInherited(new KeyedCodec<>("Count", Codec.INTEGER, false),
                        (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("How many foes the room raises for one player, an elite aside.").add()
                .appendInherited(new KeyedCodec<>("PerExtraPlayer", Codec.DOUBLE, false),
                        (o, v) -> o.perExtraPlayer = v, o -> o.perExtraPlayer,
                        (o, p) -> o.perExtraPlayer = p.perExtraPlayer)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("How many more foes each player past the first adds, rounded down over the party "
                        + "(0.5 is one per two players), counting the run's party as the room is placed, up to "
                        + "five players.").add()
                .appendInherited(new KeyedCodec<>("Elite", Codec.DOUBLE, false),
                        (o, v) -> o.elite = v, o -> o.elite, (o, p) -> o.elite = p.elite)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("The chance, 0 to 1, that one elite from the roster's Elite list joins the room's "
                        + "foes, rolled from the run's seed.").add()
                .build();

        public FoeRule() {
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
