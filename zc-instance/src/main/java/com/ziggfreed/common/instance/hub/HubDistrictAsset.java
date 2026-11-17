package com.ziggfreed.common.instance.hub;

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
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.asset.SeasonLeaf;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.instance.hub.HubLots.Size;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.season.SeasonGate;

/**
 * One district of the temple hub, as a file: {@code Server/ZiggfreedCommon/HubDistricts/<Id>.json}, the filename its
 * id. Every hub copy is laid out from the districts in force when it opens ({@link HubPlan}): each stands on the lot
 * it names, built from its prefabs, with named spots inside it that an NPC placement stands on and a map marker.
 *
 * <pre>{@code
 * {
 *   "Requires": { "Factors": [ { "Factor": "hytale:mod_installed", "Param": "MyGroup:MyPack", "Min": 1 } ] },
 *   "Season":   "Harvest_Feast",
 *   "Lot":      { "Id": "North", "Size": "Large" },
 *   "Prefab":   { "Base": "MyPack.Hub.Guild_Hall", "Overlays": ["MyPack.Hub.Guild_Hall_Banners"],
 *                 "Floor": "Rock_Chalk_Brick_Smooth" },
 *   "Anchors":  [ { "Name": "Trainer", "At": { "X": 4, "Y": 1, "Z": -6 }, "Yaw": 180 } ],
 *   "Marker":   { "Icon": "Warp.png", "NameKey": "mypack.hub.guild_hall", "Anchor": "Trainer" }
 * }
 * }</pre>
 *
 * <p><b>When it appears.</b> In every hub copy that opens while it is {@code Enabled} (the owner's switch), its
 * {@code Season} runs, and every mod its {@code Requires} names is installed (a file gated on a missing mod is left out
 * of the store at load). A copy keeps the districts it opened with until it closes, so a season that starts or ends
 * changes the next copy.
 *
 * <p><b>Where.</b> {@code Lot.Id} claims one of the hub's fixed lots ({@link HubLots}), and the prefab is authored with
 * its front facing North (-Z), which its lot turns to face its street. A district naming no lot with
 * {@code Lot.FillsEmpty} stands on every lot of its {@code Size} that nothing claimed: the library's scaffolding.
 * {@link HubLayout} is the rule, and a server owner moves a district or switches it off in
 * {@code mods/ziggfreedcommon/hub-districts.json} ({@link HubDistrictOwnerLayers}).
 *
 * <p>Every group is an orthogonal knob and every leaf is nullable: unauthored means the default each getter names, and
 * under native {@code Parent} a child keeps every leaf it does not restate.
 */
public final class HubDistrictAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, HubDistrictAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/HubDistricts";

    /** The map marker's icon when a file's {@code Marker} names none. */
    public static final String DEFAULT_MARKER_ICON = "Warp.png";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private GateSpec requires;
    @Nullable private String season;
    @Nullable private Lot lot;
    @Nullable private Prefab prefab;
    @Nullable private Anchor[] anchors;
    @Nullable private Marker marker;

    public static final AssetBuilderCodec<String, HubDistrictAsset> CODEC = SeasonLeaf.append(AssetBuilderCodec.builder(
                    HubDistrictAsset.class,
                    HubDistrictAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to leave the district out of every hub copy. A server owner switches one off in "
                    + "mods/ziggfreedcommon/hub-districts.json.").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation("The mods the district needs: while one is not installed the file is left out of the "
                    + "store, so its lot shows the scaffolding. Only mod presence is read here, since a district "
                    + "stands for everyone in a copy or for no one.").add()
            .appendInherited(new KeyedCodec<>("Lot", Lot.CODEC, false),
                    (a, v) -> a.lot = v, a -> a.lot, (a, p) -> a.lot = p.lot)
            .documentation("Which of the hub's fixed lots the district stands on, and its size.").add()
            .appendInherited(new KeyedCodec<>("Prefab", Prefab.CODEC, false),
                    (a, v) -> a.prefab = v, a -> a.prefab, (a, p) -> a.prefab = p.prefab)
            .documentation("What the district is built from, written into the hub's ground as each copy opens. Its "
                    + "front faces North (-Z) as authored, and its lot turns it to face its street.").add()
            .appendInherited(new KeyedCodec<>("Anchors", new ArrayCodec<>(Anchor.CODEC, Anchor[]::new), false),
                    (a, v) -> a.anchors = v, a -> a.anchors, (a, p) -> a.anchors = p.anchors)
            .documentation("Named spots inside the district, which an NPC placement stands on through its Custom "
                    + "anchor ({\"Provider\": \"ziggfreedcommon:hub\", \"Params\": {\"District\": \"<this file's id>\", "
                    + "\"Anchor\": \"<a Name here>\"}}), so a character follows the district to any lot; with "
                    + "\"OrArrival\": true there too, it waits by the hub's arrival while the district stands nowhere. "
                    + "A child that writes Anchors replaces its parent's list whole.").add()
            .appendInherited(new KeyedCodec<>("Marker", Marker.CODEC, false),
                    (a, v) -> a.marker = v, a -> a.marker, (a, p) -> a.marker = p.marker)
            .documentation("The district's marker on the hub's map. Unauthored, it has none.").add(),
            (a, v) -> a.season = v, a -> a.season)
            .build();

    public HubDistrictAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    // ==================== the reads ====================

    /** {@code Enabled}, true unless written false. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** {@code Requires}, or null when unwritten. */
    @Nullable
    public GateSpec requires() {
        return requires;
    }

    /** {@code Season}, trimmed, or null for all year. */
    @Nullable
    public String getSeason() {
        return SeasonGate.normalize(season);
    }

    /** Does the district stand in a copy opening now: enabled, and its season running? */
    public boolean isLive() {
        return isEnabled() && SeasonGate.live(getSeason());
    }

    /** {@code Lot.Id}, trimmed, or null when unwritten. */
    @Nullable
    public String lotId() {
        return trimmed(lot == null ? null : lot.id);
    }

    /** {@code Lot.Size}'s raw word, trimmed, or null when unwritten. */
    @Nullable
    public String sizeWord() {
        return trimmed(lot == null ? null : lot.size);
    }

    /** {@code Lot.Size}, or null when unwritten or unknown (the audit names an unknown word). */
    @Nullable
    public Size size() {
        return Size.parse(sizeWord());
    }

    /** {@code Lot.FillsEmpty}, false unless written true. */
    public boolean fillsEmpty() {
        return lot != null && lot.fillsEmpty != null && lot.fillsEmpty;
    }

    /** {@code Prefab.Base}, trimmed, or null for none (a district may be its floor and anchors alone). */
    @Nullable
    public String basePrefab() {
        return trimmed(prefab == null ? null : prefab.base);
    }

    /** {@code Prefab.Overlays}, each trimmed, blanks dropped; empty for none. */
    @Nonnull
    public List<String> overlays() {
        List<String> out = new ArrayList<>();
        if (prefab != null && prefab.overlays != null) {
            for (String overlay : prefab.overlays) {
                String t = trimmed(overlay);
                if (t != null) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** {@code Prefab.Floor}, trimmed, or null to leave the lot's ground unfloored. */
    @Nullable
    public String floor() {
        return trimmed(prefab == null ? null : prefab.floor);
    }

    /** {@code Prefab.KeepContainers}, false unless written true. */
    public boolean keepContainers() {
        return prefab != null && prefab.keepContainers != null && prefab.keepContainers;
    }

    /** {@code Anchors}, in file order, entries with no name dropped. */
    @Nonnull
    public List<Anchor> anchors() {
        List<Anchor> out = new ArrayList<>();
        if (anchors != null) {
            for (Anchor anchor : anchors) {
                if (anchor != null && anchor.name() != null) {
                    out.add(anchor);
                }
            }
        }
        return out;
    }

    /** The anchor named {@code name}, without regard to case; null for none. */
    @Nullable
    public Anchor anchor(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Anchor anchor : anchors()) {
            if (anchor.name().equalsIgnoreCase(name.trim())) {
                return anchor;
            }
        }
        return null;
    }

    /** Is a {@code Marker} written at all? */
    public boolean hasMarker() {
        return marker != null;
    }

    /** {@code Marker.Icon}, trimmed, else {@link #DEFAULT_MARKER_ICON}. */
    @Nonnull
    public String markerIcon() {
        String icon = trimmed(marker == null ? null : marker.icon);
        return icon == null ? DEFAULT_MARKER_ICON : icon;
    }

    /** {@code Marker.NameKey}, trimmed, or null for a marker with no name. */
    @Nullable
    public String markerNameKey() {
        return trimmed(marker == null ? null : marker.nameKey);
    }

    /** {@code Marker.Anchor}, trimmed, or null to mark the lot's centre. */
    @Nullable
    public String markerAnchor() {
        return trimmed(marker == null ? null : marker.anchor);
    }

    @Nullable
    private static String trimmed(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    // ==================== the groups ====================

    /** Which lot the district stands on. */
    public static final class Lot {

        @Nullable protected String id;
        @Nullable protected String size;
        @Nullable protected Boolean fillsEmpty;

        public static final BuilderCodec<Lot> CODEC = BuilderCodec.builder(Lot.class, Lot::new)
                .appendInherited(new KeyedCodec<>("Id", Codec.STRING, false),
                        (o, v) -> o.id = v, o -> o.id, (o, p) -> o.id = p.id)
                .metadata(EditorSchema.oneOf(HubLots.ids()))
                .documentation("The lot the district stands on, one of the hub's fixed lots: Concourse in the middle, "
                        + "where players arrive; North, East, South and West, the large lots around it; and the small "
                        + "lots in the corners, North_East_1 to North_West_4. Two districts naming one lot is reported "
                        + "and the first by id keeps it, unless the owner's file moved the other there.").add()
                .appendInherited(new KeyedCodec<>("Size", Codec.STRING, false),
                        (o, v) -> o.size = v, o -> o.size, (o, p) -> o.size = p.size)
                .metadata(EditorSchema.oneOf(Size.words()))
                .documentation("The district's size: Small (32 blocks square) or Large (64). A district larger than "
                        + "the lot it names stands nowhere. Unauthored, it fits whatever lot it names.").add()
                .appendInherited(new KeyedCodec<>("FillsEmpty", Codec.BOOLEAN, false),
                        (o, v) -> o.fillsEmpty = v, o -> o.fillsEmpty, (o, p) -> o.fillsEmpty = p.fillsEmpty)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True, with no Id: the district stands on every lot of its Size that no district "
                        + "claims (or every empty lot, with no Size), as the scaffolding over lots still to be built. "
                        + "The first such district by id fills a lot.").add()
                .build();

        public Lot() {
        }
    }

    /** What the district is built from. */
    public static final class Prefab {

        @Nullable protected String base;
        @Nullable protected String[] overlays;
        @Nullable protected String floor;
        @Nullable protected Boolean keepContainers;

        public static final BuilderCodec<Prefab> CODEC = BuilderCodec.builder(Prefab.class, Prefab::new)
                .appendInherited(new KeyedCodec<>("Base", Codec.STRING, false),
                        (o, v) -> o.base = v, o -> o.base, (o, p) -> o.base = p.base)
                .documentation("The prefab the district is built from, under Server/Prefabs/ in the engine's dot "
                        + "form (MyPack.Hub.Guild_Hall), or a pool (MyPack.Hub.Halls.*), one prefab of which the lot's "
                        + "id picks. Its anchor stands on the lot's centre, on the floor's own layer, so the layer "
                        + "above it is where a player walks. A dungeon room's prefab serves as well. Unauthored, the "
                        + "district is its floor and anchors alone.").add()
                .appendInherited(new KeyedCodec<>("Overlays", Codec.STRING_ARRAY, false),
                        (o, v) -> o.overlays = v, o -> o.overlays, (o, p) -> o.overlays = p.overlays)
                .documentation("Prefabs written over the base in order, at the same anchor and turn: dressing, "
                        + "seasonal decor, a dungeon room's overlay. Each is one prefab, never a pool.").add()
                .appendInherited(new KeyedCodec<>("Floor", Codec.STRING, false),
                        (o, v) -> o.floor = v, o -> o.floor, (o, p) -> o.floor = p.floor)
                .metadata(EditorSchema.assetRef(BlockType.class))
                .documentation("The block the lot's ground is laid in, out to the middle of the streets around it, "
                        + "under the prefab. Unauthored, the lot has no ground of its own but what its prefab "
                        + "brings.").add()
                .appendInherited(new KeyedCodec<>("KeepContainers", Codec.BOOLEAN, false),
                        (o, v) -> o.keepContainers = v, o -> o.keepContainers,
                        (o, p) -> o.keepContainers = p.keepContainers)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True keeps the prefabs' chests and other containers. Unauthored they are left out, "
                        + "since every copy builds them full again.").add()
                .build();

        public Prefab() {
        }
    }

    /** One named spot inside the district. */
    public static final class Anchor {

        @Nullable protected String name;
        @Nullable protected Vec3 at;
        @Nullable protected Double yaw;

        public static final BuilderCodec<Anchor> CODEC = BuilderCodec.builder(Anchor.class, Anchor::new)
                .append(new KeyedCodec<>("Name", Codec.STRING, false), (o, v) -> o.name = v, o -> o.name)
                .documentation("The spot's name, unique in the district, matched without regard to case.").add()
                .append(new KeyedCodec<>("At", Vec3.CODEC, false), (o, v) -> o.at = v, o -> o.at)
                .documentation("Where the spot is, in blocks from the district's anchor (the lot's centre on the "
                        + "floor's layer), as the prefab is authored, facing North: Y 1 stands on the floor. It turns "
                        + "with the lot. Unauthored axes are 0.").add()
                .append(new KeyedCodec<>("Yaw", Codec.DOUBLE, false), (o, v) -> o.yaw = v, o -> o.yaw)
                .documentation("The way a character standing here faces, in degrees as the prefab is authored "
                        + "(0 looks North), turned with the lot. Unauthored means 0.").add()
                .build();

        public Anchor() {
        }

        /** Java-side construction. */
        @Nonnull
        public static Anchor of(@Nullable String name, @Nullable Vec3 at, @Nullable Double yaw) {
            Anchor a = new Anchor();
            a.name = name;
            a.at = at;
            a.yaw = yaw;
            return a;
        }

        /** The name, trimmed, or null when unwritten. */
        @Nullable
        public String name() {
            return name == null || name.isBlank() ? null : name.trim();
        }

        /** The X from the district's anchor, as authored. */
        public double x() {
            return at == null ? 0.0 : at.effectiveX();
        }

        /** The Y from the district's anchor. */
        public double y() {
            return at == null ? 0.0 : at.effectiveY();
        }

        /** The Z from the district's anchor, as authored. */
        public double z() {
            return at == null ? 0.0 : at.effectiveZ();
        }

        /** The facing in degrees, as authored. */
        public double yaw() {
            return yaw == null ? 0.0 : yaw;
        }
    }

    /** The district's marker on the hub's map. */
    public static final class Marker {

        @Nullable protected String icon;
        @Nullable protected String nameKey;
        @Nullable protected String anchor;

        public static final BuilderCodec<Marker> CODEC = BuilderCodec.builder(Marker.class, Marker::new)
                .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                        (o, v) -> o.icon = v, o -> o.icon, (o, p) -> o.icon = p.icon)
                .metadata(EditorSchema.defaultValue(DEFAULT_MARKER_ICON))
                .documentation("The map marker's icon, a texture the game's map shows (Warp.png, Portal.png, Home.png, "
                        + "Spawn.png, Campfire.png).").add()
                .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                        (o, v) -> o.nameKey = v, o -> o.nameKey, (o, p) -> o.nameKey = p.nameKey)
                .documentation("The .lang key of the name the marker shows. Unauthored, it shows none.").add()
                .appendInherited(new KeyedCodec<>("Anchor", Codec.STRING, false),
                        (o, v) -> o.anchor = v, o -> o.anchor, (o, p) -> o.anchor = p.anchor)
                .documentation("One of the district's Anchors to put the marker on. Unauthored, the lot's centre.")
                .add()
                .build();

        public Marker() {
        }
    }
}
