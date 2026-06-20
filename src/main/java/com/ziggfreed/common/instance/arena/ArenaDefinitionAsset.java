package com.ziggfreed.common.instance.arena;

import java.util.ArrayList;
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

/**
 * A pack-authorable, mod-agnostic ARENA definition, loaded from
 * {@code Server/ZiggfreedCommon/Arenas/*.json} (one arena per file, PascalCase
 * filename). It captures the SPATIAL layout of a minigame arena as pure DATA - the
 * worldgen instance to spawn, the per-team spawn anchors, the objective points, and the
 * pickup spots - so any minigame (capture, king-of-the-hill, collect-the-orbs, a dungeon
 * room) authors its battlefields in JSON instead of baking coordinates as Java literals.
 *
 * <p><b>Pattern A - full structured asset</b> (mirrors common's {@code InstancePresetAsset}
 * / {@code WeightedPrefabPlacementAsset} / {@code LeaderboardLayoutAsset} field-for-field).
 * The engine decodes an arena DIRECTLY into typed fields via {@link #CODEC} - the codec IS
 * the single schema authority on both the pack layer and an owner override layer (the same
 * CODEC decodes an owner override). Every {@code KeyedCodec} field name is PascalCase (the
 * constructor rejects a lower-case first letter at static init, throwing at server start;
 * the {@code AssetCodecInitTest}-style guard enforces it).
 *
 * <p>The nested lists use the list-of-objects form (a {@link ArrayCodec} over a nested
 * {@link BuilderCodec}, the same idiom {@code LeaderboardLayoutAsset}'s tab axes use):
 * {@link TeamSpawnGroup} (one team, an array of {@link AnchorEntry} spawn points),
 * {@link ObjectiveAnchorEntry} (an objective point with an id + a radius), and
 * {@link PickupAnchorEntry} (a pickup spot). The {@code StructureRoll} block is OMITTED
 * for now (see the class followups) - a consumer that needs roll-in arena structures
 * already has the generic {@code WeightedPrefabPlacementAsset} table.
 *
 * <p>Reading is via {@link ArenaDefinitionConfig} (the {@code defaults < pack < owner} fold).
 * The accessor methods build small immutable nested records ({@link Anchor},
 * {@link ObjectiveAnchor}, {@link PickupAnchor}) so the caller never touches the codec
 * scaffolding shape; an absent list yields an empty list.
 *
 * <p>Pack JSON shape (all fields optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "frostpeak",
 *   "Id": "frostpeak",
 *   "InstanceName": "MyMod/Frostpeak.bson",
 *   "Tags": [ "capture", "2v2" ],
 *   "TeamSpawns": [
 *     { "Anchors": [ { "X": 10.0, "Y": 64.0, "Z": 10.0, "Yaw": 90.0 },
 *                    { "X": 12.0, "Y": 64.0, "Z": 10.0, "Yaw": 90.0 } ] },
 *     { "Anchors": [ { "X": 90.0, "Y": 64.0, "Z": 90.0, "Yaw": 270.0 } ] } ],
 *   "ObjectiveAnchors": [
 *     { "Id": "hill", "X": 50.0, "Z": 50.0, "Radius": 6.0 } ],
 *   "PickupAnchors": [
 *     { "X": 30.0, "Z": 50.0 }, { "X": 70.0, "Z": 50.0 } ] }
 * }</pre>
 */
public final class ArenaDefinitionAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, ArenaDefinitionAsset>> {

    /** Default objective trigger radius when none is authored. */
    private static final double DEFAULT_OBJECTIVE_RADIUS = 4.0;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String instanceName;
    @Nullable private String[] tags;
    @Nullable private TeamSpawnGroup[] teamSpawns;
    @Nullable private ObjectiveAnchorEntry[] objectiveAnchors;
    @Nullable private PickupAnchorEntry[] pickupAnchors;

    public static final AssetBuilderCodec<String, ArenaDefinitionAsset> CODEC = AssetBuilderCodec.builder(
                    ArenaDefinitionAsset.class,
                    ArenaDefinitionAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // Name is an optional human-readable echo of the asset key (the authoritative
            // key is the filename) - a no-op setter so it doesn't trip "Unused key(s)".
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id comes from the filename */ },
                    a -> a.id)
            .add()
            // Id is an optional explicit echo of the asset key (the authoritative key is
            // still the filename) - a no-op setter so authoring "Id" never trips "Unused key(s)".
            .append(new KeyedCodec<>("Id", Codec.STRING, false),
                    (a, v) -> { /* no-op - id comes from the filename */ },
                    a -> a.id)
            .add()
            .append(new KeyedCodec<>("InstanceName", Codec.STRING, false),
                    (a, v) -> a.instanceName = v, a -> a.instanceName)
            .add()
            .append(new KeyedCodec<>("Tags", Codec.STRING_ARRAY, false),
                    (a, v) -> a.tags = v, a -> a.tags)
            .add()
            .append(new KeyedCodec<>("TeamSpawns", new ArrayCodec<>(TeamSpawnGroup.CODEC, TeamSpawnGroup[]::new), false),
                    (a, v) -> a.teamSpawns = v, a -> a.teamSpawns)
            .add()
            .append(new KeyedCodec<>("ObjectiveAnchors", new ArrayCodec<>(ObjectiveAnchorEntry.CODEC, ObjectiveAnchorEntry[]::new), false),
                    (a, v) -> a.objectiveAnchors = v, a -> a.objectiveAnchors)
            .add()
            .append(new KeyedCodec<>("PickupAnchors", new ArrayCodec<>(PickupAnchorEntry.CODEC, PickupAnchorEntry[]::new), false),
                    (a, v) -> a.pickupAnchors = v, a -> a.pickupAnchors)
            .add()
            .build();

    public ArenaDefinitionAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The worldgen instance.bson to spawn for this arena (e.g. {@code MyMod/Frostpeak.bson}), or {@code null}. */
    @Nullable
    public String instanceName() {
        return instanceName;
    }

    /** The authored selection tags (lowercased, de-blanked), or an empty list. */
    @Nonnull
    public List<String> tags() {
        if (tags == null || tags.length == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(tags.length);
        for (String t : tags) {
            if (t != null && !t.isBlank()) {
                out.add(t.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return List.copyOf(out);
    }

    /** True if this arena carries the given tag (case-insensitive). */
    public boolean hasTag(@Nullable String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        return tags().contains(tag.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The per-team spawn groups: the outer list is one entry per team, each inner list the
     * team's spawn anchors (in authored order). An absent/blank group is skipped. A fresh
     * snapshot; safe to iterate.
     */
    @Nonnull
    public List<List<Anchor>> teamSpawns() {
        if (teamSpawns == null || teamSpawns.length == 0) {
            return List.of();
        }
        List<List<Anchor>> out = new ArrayList<>(teamSpawns.length);
        for (TeamSpawnGroup g : teamSpawns) {
            if (g == null) {
                continue;
            }
            out.add(g.toAnchors());
        }
        return List.copyOf(out);
    }

    /** The objective points (id + center + radius), in authored order. A fresh snapshot. */
    @Nonnull
    public List<ObjectiveAnchor> objectiveAnchors() {
        if (objectiveAnchors == null || objectiveAnchors.length == 0) {
            return List.of();
        }
        List<ObjectiveAnchor> out = new ArrayList<>(objectiveAnchors.length);
        for (ObjectiveAnchorEntry e : objectiveAnchors) {
            if (e == null) {
                continue;
            }
            out.add(e.toObjectiveAnchor());
        }
        return List.copyOf(out);
    }

    /** The pickup spots (x,z), in authored order. A fresh snapshot. */
    @Nonnull
    public List<PickupAnchor> pickupAnchors() {
        if (pickupAnchors == null || pickupAnchors.length == 0) {
            return List.of();
        }
        List<PickupAnchor> out = new ArrayList<>(pickupAnchors.length);
        for (PickupAnchorEntry e : pickupAnchors) {
            if (e == null) {
                continue;
            }
            out.add(e.toPickupAnchor());
        }
        return List.copyOf(out);
    }

    // --- Immutable runtime records (the caller's view; never the codec scaffolding) ---

    /** An immutable spawn/placement anchor (world coordinate + facing yaw, in degrees). */
    public record Anchor(double x, double y, double z, double yaw) {
    }

    /** An immutable objective point: an id, a center (x,z), and a trigger radius. */
    public record ObjectiveAnchor(@Nonnull String id, double x, double z, double radius) {
    }

    /** An immutable pickup spot (x,z). */
    public record PickupAnchor(double x, double z) {
    }

    // --- Codec scaffolding (nested BuilderCodecs; package-visible fields, PascalCase keys) ---

    /**
     * One team's spawn group: an array of {@link AnchorEntry} spawn points. PascalCase keys
     * (the codec rejects a lower-case first letter at static init). A nested
     * {@link BuilderCodec}, not the asset key, so the field names are free here.
     */
    public static final class TeamSpawnGroup {
        public static final BuilderCodec<TeamSpawnGroup> CODEC = BuilderCodec.builder(TeamSpawnGroup.class, TeamSpawnGroup::new)
                .append(new KeyedCodec<>("Anchors", new ArrayCodec<>(AnchorEntry.CODEC, AnchorEntry[]::new), false),
                        (g, v) -> g.anchors = v, g -> g.anchors)
                .add()
                .build();

        @Nullable protected AnchorEntry[] anchors;

        public TeamSpawnGroup() {
        }

        @Nonnull
        List<Anchor> toAnchors() {
            if (anchors == null || anchors.length == 0) {
                return List.of();
            }
            List<Anchor> out = new ArrayList<>(anchors.length);
            for (AnchorEntry e : anchors) {
                if (e == null) {
                    continue;
                }
                out.add(e.toAnchor());
            }
            return List.copyOf(out);
        }
    }

    /** One spawn anchor: world (X,Y,Z) + facing Yaw (degrees). PascalCase keys. */
    public static final class AnchorEntry {
        public static final BuilderCodec<AnchorEntry> CODEC = BuilderCodec.builder(AnchorEntry.class, AnchorEntry::new)
                .append(new KeyedCodec<>("X", Codec.DOUBLE, false), (e, v) -> e.x = v, e -> e.x).add()
                .append(new KeyedCodec<>("Y", Codec.DOUBLE, false), (e, v) -> e.y = v, e -> e.y).add()
                .append(new KeyedCodec<>("Z", Codec.DOUBLE, false), (e, v) -> e.z = v, e -> e.z).add()
                .append(new KeyedCodec<>("Yaw", Codec.DOUBLE, false), (e, v) -> e.yaw = v, e -> e.yaw).add()
                .build();

        protected double x;
        protected double y;
        protected double z;
        protected double yaw;

        public AnchorEntry() {
        }

        @Nonnull
        Anchor toAnchor() {
            return new Anchor(x, y, z, yaw);
        }
    }

    /** One objective point: an Id + center (X,Z) + trigger Radius. PascalCase keys. */
    public static final class ObjectiveAnchorEntry {
        public static final BuilderCodec<ObjectiveAnchorEntry> CODEC = BuilderCodec.builder(ObjectiveAnchorEntry.class, ObjectiveAnchorEntry::new)
                .append(new KeyedCodec<>("Id", Codec.STRING, false), (e, v) -> e.id = v, e -> e.id).add()
                .append(new KeyedCodec<>("X", Codec.DOUBLE, false), (e, v) -> e.x = v, e -> e.x).add()
                .append(new KeyedCodec<>("Z", Codec.DOUBLE, false), (e, v) -> e.z = v, e -> e.z).add()
                .append(new KeyedCodec<>("Radius", Codec.DOUBLE, false), (e, v) -> e.radius = v, e -> e.radius).add()
                .build();

        @Nullable protected String id;
        protected double x;
        protected double z;
        protected double radius = DEFAULT_OBJECTIVE_RADIUS;

        public ObjectiveAnchorEntry() {
        }

        @Nonnull
        ObjectiveAnchor toObjectiveAnchor() {
            String oid = (id != null && !id.isBlank()) ? id.trim() : "";
            double r = radius > 0.0 ? radius : DEFAULT_OBJECTIVE_RADIUS;
            return new ObjectiveAnchor(oid, x, z, r);
        }
    }

    /** One pickup spot: (X,Z). PascalCase keys. */
    public static final class PickupAnchorEntry {
        public static final BuilderCodec<PickupAnchorEntry> CODEC = BuilderCodec.builder(PickupAnchorEntry.class, PickupAnchorEntry::new)
                .append(new KeyedCodec<>("X", Codec.DOUBLE, false), (e, v) -> e.x = v, e -> e.x).add()
                .append(new KeyedCodec<>("Z", Codec.DOUBLE, false), (e, v) -> e.z = v, e -> e.z).add()
                .build();

        protected double x;
        protected double z;

        public PickupAnchorEntry() {
        }

        @Nonnull
        PickupAnchor toPickupAnchor() {
            return new PickupAnchor(x, z);
        }
    }
}
