package com.ziggfreed.common.world.gather;

import java.util.Locale;

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
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;

/**
 * A gather shape: what more a player's break or harvest takes beyond the block in hand, for a player who meets its
 * {@code Requires} (a talent, a held tool, anything the factor vocabulary reads). One shape per file at
 * {@code Server/ZiggfreedCommon/GatherShapes/<Id>.json}, the filename its id.
 *
 * <pre>{@code
 * {
 *   "Requires": { "Factors": [ { "Factor": "ziggfreedcommon:track_pick", "Param": "Mining/My_Vein_Pick", "Min": 1 } ] },
 *   "Origin":   { "Families": ["Ore"], "Crouching": true, "Placed": false },
 *   "Shape":    { "Type": "Connected" },
 *   "Count":    { "Base": 12 }
 * }
 * }</pre>
 *
 * <p>{@code On} names the moment: a {@code Break} shape runs after a block the player broke by hand is gone, breaking
 * each further block through the engine's own break call with the player's held tool, so each one fires the engine's
 * break event with the player (a claim or protection mod refuses there; a block-break reward pays as for a hand break),
 * drops what a hand break drops (into the bag with {@code Pickup}), and costs {@code Wear} durability unless it is soft;
 * every block must be one the held tool could break by hand and one the world lets the player break ({@code
 * world/BreakPermission}). A {@code Harvest} shape runs after a farming block was harvested by hand and harvests each
 * further ripe one the engine's own way, once the engine's use event for it goes uncancelled. {@code Instant} makes
 * a {@code Break} shape's first block break in one hit. Every number is a factor formula, so a talent's rank sizes it.
 * A server owner retunes or switches off a shape by id in {@code mods/ziggfreedcommon/gather-shapes.json}.
 */
public final class GatherShapeAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, GatherShapeAsset>> {

    /** Where the shapes live under a pack's {@code Server/} folder. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/GatherShapes";

    /** The moment a shape answers. */
    public enum Moment {
        BREAK, HARVEST;

        /** The moment {@code raw} names, {@link #BREAK} when blank, null when unknown. */
        @Nullable
        static Moment parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return BREAK;
            }
            String folded = raw.trim().toUpperCase(Locale.ROOT);
            for (Moment moment : values()) {
                if (moment.name().equals(folded)) {
                    return moment;
                }
            }
            return null;
        }
    }

    /** How a shape walks out from its first block. */
    public enum Walk {
        CONNECTED, TREE, OFFSETS, AREA;

        /** The walk {@code raw} names, null when unknown or blank. */
        @Nullable
        static Walk parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String folded = raw.trim().toUpperCase(Locale.ROOT);
            for (Walk walk : values()) {
                if (walk.name().equals(folded)) {
                    return walk;
                }
            }
            return null;
        }
    }

    /** Which first block, held how, starts the shape. Each leaf left out asks nothing. */
    public static final class Origin {

        @Nullable protected String[] families;
        @Nullable protected Boolean crouching;
        @Nullable protected Boolean placed;
        @Nullable protected FactorFormula placedWithin;
        @Nullable protected Boolean lowest;
        @Nullable protected Boolean own;

        public static final BuilderCodec<Origin> CODEC = BuilderCodec.builder(Origin.class, Origin::new)
                .appendInherited(new KeyedCodec<>("Families", Codec.STRING_ARRAY, false),
                        (o, v) -> o.families = v, o -> o.families, (o, p) -> o.families = p.families)
                .documentation("Block families (Server/ZiggfreedCommon/BlockFamilies) the first block must be in, any "
                        + "one of them. Left out, any block starts the shape.").add()
                .appendInherited(new KeyedCodec<>("Crouching", Codec.BOOLEAN, false),
                        (o, v) -> o.crouching = v, o -> o.crouching, (o, p) -> o.crouching = p.crouching)
                .documentation("True: only while the player crouches. False: only while they do not. Left out: "
                        + "either.").add()
                .appendInherited(new KeyedCodec<>("Placed", Codec.BOOLEAN, false),
                        (o, v) -> o.placed = v, o -> o.placed, (o, p) -> o.placed = p.placed)
                .documentation("True: only a block a player placed (the placed-block ledger, or a log the engine "
                        + "marks as placed). False: only a block nobody placed. Left out: either.").add()
                .appendInherited(new KeyedCodec<>("PlacedWithin", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.placedWithin = v, o -> o.placedWithin, (o, p) -> o.placedWithin = p.placedWithin)
                .documentation("Seconds: only a block this player placed this recently. A formula, so a talent's "
                        + "rank lengthens it; 0 or less never matches.").add()
                .appendInherited(new KeyedCodec<>("Lowest", Codec.BOOLEAN, false),
                        (o, v) -> o.lowest = v, o -> o.lowest, (o, p) -> o.lowest = p.lowest)
                .documentation("True: only when the block below is not the same block (a tree's lowest log).").add()
                .appendInherited(new KeyedCodec<>("Own", Codec.BOOLEAN, false),
                        (o, v) -> o.own = v, o -> o.own, (o, p) -> o.own = p.own)
                .documentation("True: only a block this player placed: the placed-block ledger's placer, or the "
                        + "engine's own record on a farming block (a crop, a sapling or a trap they set down).").add()
                .build();

        public Origin() {
        }

        @Nonnull
        public String[] families() {
            return families == null ? new String[0] : families;
        }

        @Nullable
        public Boolean crouching() {
            return crouching;
        }

        @Nullable
        public Boolean placed() {
            return placed;
        }

        @Nullable
        public FactorFormula placedWithin() {
            return placedWithin;
        }

        public boolean lowest() {
            return Boolean.TRUE.equals(lowest);
        }

        public boolean own() {
            return Boolean.TRUE.equals(own);
        }
    }

    /** One cell of an {@code Offsets} walk, in the player's frame: ahead of them, up, and to their right. */
    public static final class Offset {

        protected int forward;
        protected int up;
        protected int right;

        public static final BuilderCodec<Offset> CODEC = BuilderCodec.builder(Offset.class, Offset::new)
                .append(new KeyedCodec<>("Forward", Codec.INTEGER, false), (o, v) -> o.forward = v == null ? 0 : v,
                        o -> o.forward)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("Blocks further from the player, along the side of the first block they face.").add()
                .append(new KeyedCodec<>("Up", Codec.INTEGER, false), (o, v) -> o.up = v == null ? 0 : v, o -> o.up)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("Blocks above the first block; negative for below.").add()
                .append(new KeyedCodec<>("Right", Codec.INTEGER, false), (o, v) -> o.right = v == null ? 0 : v,
                        o -> o.right)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("Blocks to the player's right; negative for their left.").add()
                .build();

        public Offset() {
        }

        public int forward() {
            return forward;
        }

        public int up() {
            return up;
        }

        public int right() {
            return right;
        }
    }

    /** What the shape takes beyond its first block. */
    public static final class Shape {

        @Nullable protected String type;
        @Nullable protected String[] families;
        @Nullable protected Boolean placed;
        @Nullable protected Offset[] offsets;
        @Nullable protected FactorFormula radius;
        @Nullable protected Integer above;
        @Nullable protected Integer below;
        @Nullable protected Boolean aboveFeet;
        @Nullable protected Boolean own;
        @Nullable protected Canopy canopy;

        public static final BuilderCodec<Shape> CODEC = BuilderCodec.builder(Shape.class, Shape::new)
                .appendInherited(new KeyedCodec<>("Type", Codec.STRING, false),
                        (o, v) -> o.type = v, o -> o.type, (o, p) -> o.type = p.type)
                .metadata(EditorSchema.oneOfDocumented(
                        "Connected", "Every block joined to the first, corner to corner, that the shape takes",
                        "Tree", "A tree: the logs joined to the first, and with a Canopy its leaves",
                        "Offsets", "The cells Offsets names, in the player's frame",
                        "Area", "A square around the first block, Radius blocks out, between Below and Above"))
                .documentation("How the shape walks out from its first block. A shape naming no walk takes "
                        + "nothing.").add()
                .appendInherited(new KeyedCodec<>("Families", Codec.STRING_ARRAY, false),
                        (o, v) -> o.families = v, o -> o.families, (o, p) -> o.families = p.families)
                .documentation("Block families the shape takes, any one of them. Left out, it takes only the "
                        + "first block's own block.").add()
                .appendInherited(new KeyedCodec<>("Placed", Codec.BOOLEAN, false),
                        (o, v) -> o.placed = v, o -> o.placed, (o, p) -> o.placed = p.placed)
                .documentation("True: takes only placed blocks. False: never a placed one. Left out: either.").add()
                .appendInherited(new KeyedCodec<>("Offsets", new ArrayCodec<>(Offset.CODEC, Offset[]::new), false),
                        (o, v) -> o.offsets = v, o -> o.offsets, (o, p) -> o.offsets = p.offsets)
                .documentation("The cells an Offsets walk names, each Forward, Up and Right of the first block, "
                        + "taken in this order.").add()
                .appendInherited(new KeyedCodec<>("Radius", FactorFormula.codec(EditorDataSets.FACTORS), false),
                        (o, v) -> o.radius = v, o -> o.radius, (o, p) -> o.radius = p.radius)
                .documentation("How many blocks out an Area walk reaches each way (1 is a 3 by 3). A formula.").add()
                .appendInherited(new KeyedCodec<>("Above", Codec.INTEGER, false),
                        (o, v) -> o.above = v, o -> o.above, (o, p) -> o.above = p.above)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("How many layers above the first block an Area walk reaches.").add()
                .appendInherited(new KeyedCodec<>("Below", Codec.INTEGER, false),
                        (o, v) -> o.below = v, o -> o.below, (o, p) -> o.below = p.below)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("How many layers below the first block an Area walk reaches.").add()
                .appendInherited(new KeyedCodec<>("AboveFeet", Codec.BOOLEAN, false),
                        (o, v) -> o.aboveFeet = v, o -> o.aboveFeet, (o, p) -> o.aboveFeet = p.aboveFeet)
                .documentation("True: an Area walk never reaches below the level of the player's feet, so it "
                        + "cuts the ground flat.").add()
                .appendInherited(new KeyedCodec<>("Own", Codec.BOOLEAN, false),
                        (o, v) -> o.own = v, o -> o.own, (o, p) -> o.own = p.own)
                .documentation("True: takes only blocks this player placed, read as Origin's Own reads it.").add()
                .appendInherited(new KeyedCodec<>("Canopy", Canopy.CODEC, false),
                        (o, v) -> o.canopy = v, o -> o.canopy, (o, p) -> o.canopy = p.canopy)
                .documentation("A Tree walk's leaves: taken too, for a player meeting its own Requires.").add()
                .build();

        public Shape() {
        }

        /** The walk named, or null when none (or an unknown one) was. */
        @Nullable
        public Walk walk() {
            return Walk.parse(type);
        }

        @Nullable
        public String rawType() {
            return type;
        }

        @Nonnull
        public String[] families() {
            return families == null ? new String[0] : families;
        }

        @Nullable
        public Boolean placed() {
            return placed;
        }

        @Nonnull
        public Offset[] offsets() {
            return offsets == null ? new Offset[0] : offsets;
        }

        @Nullable
        public FactorFormula radius() {
            return radius;
        }

        public int above() {
            return above == null ? 0 : Math.max(0, above);
        }

        public int below() {
            return below == null ? 0 : Math.max(0, below);
        }

        public boolean aboveFeet() {
            return Boolean.TRUE.equals(aboveFeet);
        }

        public boolean own() {
            return Boolean.TRUE.equals(own);
        }

        @Nullable
        public Canopy canopy() {
            return canopy;
        }
    }

    /** A tree's leaves: the families they are in, and who takes them. */
    public static final class Canopy {

        @Nullable protected String[] families;
        @Nullable protected GateSpec requires;
        @Nullable private volatile GateSpec[] live;

        public static final BuilderCodec<Canopy> CODEC = BuilderCodec.builder(Canopy.class, Canopy::new)
                .appendInherited(new KeyedCodec<>("Families", Codec.STRING_ARRAY, false),
                        (o, v) -> o.families = v, o -> o.families, (o, p) -> o.families = p.families)
                .documentation("The block families a tree's leaves are in.").add()
                .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                        (o, v) -> o.requires = v, o -> o.requires, (o, p) -> o.requires = p.requires)
                .documentation("What a player must meet for the leaves to come down too. Left out, they always "
                        + "do.").add()
                .build();

        public Canopy() {
        }

        @Nonnull
        public String[] families() {
            return families == null ? new String[0] : families;
        }

        /** The part of the canopy's {@code Requires} asked of the player; null when nothing is. */
        @Nullable
        public GateSpec liveRequires() {
            GateSpec[] held = live;
            if (held == null) {
                held = new GateSpec[] {StoreGates.live(requires)};
                live = held;
            }
            return held[0];
        }
    }

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String on;
    @Nullable private GateSpec requires;
    @Nullable private Origin origin;
    @Nullable private Boolean instant;
    @Nullable private Shape shape;
    @Nullable private FactorFormula count;
    @Nullable private FactorFormula wear;
    @Nullable private Boolean pickup;
    @Nullable private GateSpec rows;
    @Nullable private Boolean enabled;
    @Nullable private volatile GateSpec[] live;
    @Nullable private volatile GateSpec[] liveRows;

    public static final AssetBuilderCodec<String, GatherShapeAsset> CODEC = AssetBuilderCodec.builder(
                    GatherShapeAsset.class,
                    GatherShapeAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("On", Codec.STRING, false),
                    (a, v) -> a.on = v, a -> a.on, (a, p) -> a.on = p.on)
            .metadata(EditorSchema.oneOfDocumented(
                    "Break", "After a block the player broke by hand is gone",
                    "Harvest", "After a farming block the player harvested by hand"))
            .documentation("The moment the shape answers: Break (the default) or Harvest.").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation(StoreGates.DOCUMENTATION).add()
            .appendInherited(new KeyedCodec<>("Origin", Origin.CODEC, false),
                    (a, v) -> a.origin = v, a -> a.origin, (a, p) -> a.origin = p.origin)
            .documentation("Which first block, broken or harvested how, starts the shape.").add()
            .appendInherited(new KeyedCodec<>("Instant", Codec.BOOLEAN, false),
                    (a, v) -> a.instant = v, a -> a.instant, (a, p) -> a.instant = p.instant)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True: a Break shape's first block breaks in one hit, for a tool that can break it at "
                    + "all.").add()
            .appendInherited(new KeyedCodec<>("Shape", Shape.CODEC, false),
                    (a, v) -> a.shape = v, a -> a.shape, (a, p) -> a.shape = p.shape)
            .documentation("What the shape takes beyond its first block. Left out, it takes nothing more (an "
                    + "Instant shape needs none).").add()
            .appendInherited(new KeyedCodec<>("Count", FactorFormula.codec(EditorDataSets.FACTORS), false),
                    (a, v) -> a.count = v, a -> a.count, (a, p) -> a.count = p.count)
            .documentation("The most blocks the shape takes beyond the first, a formula (a talent's rank adds to "
                    + "it), never more than 128.").add()
            .appendInherited(new KeyedCodec<>("Wear", FactorFormula.codec(EditorDataSets.FACTORS), false),
                    (a, v) -> a.wear = v, a -> a.wear, (a, p) -> a.wear = p.wear)
            .documentation("Durability each further block a Break shape takes costs the held tool, a formula; "
                    + "1 when left out. A soft block (a leaf, a plant, a web) costs nothing, as a hand hit on it "
                    + "does. The shape stops before the tool would break.").add()
            .appendInherited(new KeyedCodec<>("Pickup", Codec.BOOLEAN, false),
                    (a, v) -> a.pickup = v, a -> a.pickup, (a, p) -> a.pickup = p.pickup)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True: a Break shape's further blocks go straight into the player's bag as a pickup, "
                    + "what does not fit dropping at their feet, rather than dropping where they stood.").add()
            .appendInherited(new KeyedCodec<>("Rows", GateSpec.CODEC, false),
                    (a, v) -> a.rows = v, a -> a.rows, (a, p) -> a.rows = p.rows)
            .documentation("Who has the further blocks of a Break shape roll a mod's bonus drops as a hand break "
                    + "would. Left out, they never do: a mod's bonus rows ask ShapedBreaks.rowsAllowed.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to switch the shape off.").add()
            .build();

    public GatherShapeAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The moment named, or null when an unknown one was. */
    @Nullable
    public Moment moment() {
        return Moment.parse(on);
    }

    @Nullable
    public String rawOn() {
        return on;
    }

    @Nullable
    public GateSpec requires() {
        return requires;
    }

    @Nonnull
    public Origin origin() {
        return origin == null ? new Origin() : origin;
    }

    public boolean instant() {
        return Boolean.TRUE.equals(instant);
    }

    @Nullable
    public Shape shape() {
        return shape;
    }

    @Nullable
    public FactorFormula count() {
        return count;
    }

    @Nullable
    public FactorFormula wear() {
        return wear;
    }

    public boolean pickup() {
        return Boolean.TRUE.equals(pickup);
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** The part of {@link #requires()} asked of the player, lifted once; null when nothing is. */
    @Nullable
    public GateSpec liveRequires() {
        GateSpec[] held = live;
        if (held == null) {
            held = new GateSpec[] {StoreGates.live(requires)};
            live = held;
        }
        return held[0];
    }

    /** Whether the shape names a {@code Rows} block at all (left out, its further blocks never roll). */
    public boolean writesRows() {
        return rows != null;
    }

    /** The part of the {@code Rows} block asked of the player; null when nothing is (or none is written). */
    @Nullable
    public GateSpec liveRows() {
        GateSpec[] held = liveRows;
        if (held == null) {
            held = new GateSpec[] {StoreGates.live(rows)};
            liveRows = held;
        }
        return held[0];
    }
}
