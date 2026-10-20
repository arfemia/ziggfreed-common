package com.ziggfreed.common.track.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.track.curve.CurveTable;

/**
 * One level curve tracks share, at {@code Server/ZiggfreedCommon/TrackCurves/<Id>.json}: how much value each
 * level takes, what lies past the last level, and the names of level ranges. A track names it by id
 * ({@code Curve}), so every skill on one curve reads one table.
 *
 * <pre>{@code
 * { "Pieces": [ { "Hybrid": { "Base": 300, "Scale": 200 }, "To": 200 } ] }
 *
 * { "Pieces": [ { "Floors": [ 0, 3000, 9000, 21000, 42000 ] } ],
 *   "Beyond": { "Every": 10000 },
 *   "Tiers": { "Trusted": { "From": 3, "Name": "yourmod.track.tier.trusted" } } }
 * }</pre>
 *
 * <p>The table is built once per curve id and kept until the curves load again ({@code TrackCurves}); what a
 * piece means is {@link CurveTable}'s javadoc. Every leaf inherits through {@code Parent}: {@code Pieces}
 * replaces whole, {@code Tiers} merges by tier id.
 */
public final class TrackCurveAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TrackCurveAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackCurves";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Piece[] pieces;
    @Nullable protected Beyond beyond;
    @Nullable protected Map<String, Tier> tiers;

    public static final AssetBuilderCodec<String, TrackCurveAsset> CODEC = AssetBuilderCodec.builder(
                    TrackCurveAsset.class,
                    TrackCurveAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Pieces", new ArrayCodec<>(Piece.CODEC, Piece[]::new), false),
                    (a, v) -> a.pieces = v, a -> a.pieces, (a, p) -> a.pieces = p.pieces)
            .documentation("The curve's pieces, in level order: each covers the levels after the one before it "
                    + "ends. A Hybrid piece costs each level what the hybrid formula says a level of that number "
                    + "costs, up to its To level; a Floors piece lists the value each of its levels starts at. A "
                    + "curve of one Hybrid piece from level 1 is the formula exactly. Under Parent this list "
                    + "replaces the parent's whole.").add()
            .appendInherited(new KeyedCodec<>("Beyond", Beyond.CODEC, false),
                    (a, v) -> a.beyond = v, a -> a.beyond, (a, p) -> a.beyond = p.beyond)
            .documentation("What lies past the last piece's top level: each Every more value is one more level, "
                    + "without end (a track's Cap stops it). Leave it out and the last level is the top.").add()
            .appendInherited(new KeyedCodec<>("Tiers", new InheritMapCodec<>(Tier.CODEC), false),
                    (a, v) -> a.tiers = v, a -> a.tiers, (a, p) -> a.tiers = p.tiers)
            .documentation("Names for ranges of levels, by tier id: each tier holds from its From level to the "
                    + "next tier's. A requirement can ask for a tier (ziggfreedcommon:track_tier) and a quest step "
                    + "can count reaching one (TRACK_TIER). Under Parent this merges by tier id.").add()
            .build();

    public TrackCurveAsset() {
    }

    /** Java-side factory, for a test or a consumer's own default; sets the same fields the codec fills. */
    @Nonnull
    public static TrackCurveAsset of(@Nonnull String id, @Nullable Piece[] pieces, @Nullable Long every,
            @Nullable Map<String, Tier> tiers) {
        TrackCurveAsset a = new TrackCurveAsset();
        a.id = id;
        a.pieces = pieces;
        if (every != null) {
            a.beyond = new Beyond();
            a.beyond.every = every;
        }
        a.tiers = tiers;
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    /** The pieces as written, nulls dropped; empty when none. */
    @Nonnull
    public List<Piece> pieces() {
        List<Piece> out = new ArrayList<>();
        if (pieces != null) {
            for (Piece piece : pieces) {
                if (piece != null) {
                    out.add(piece);
                }
            }
        }
        return out;
    }

    /** The Beyond step, 0 when unauthored, zero or negative. */
    public long beyondEvery() {
        Long every = beyond == null ? null : beyond.every;
        return every == null || every <= 0 ? 0L : every;
    }

    /** The tiers as written, blank ids dropped. */
    @Nonnull
    public List<CurveTable.Tier> tiers() {
        List<CurveTable.Tier> out = new ArrayList<>();
        if (tiers == null) {
            return out;
        }
        for (Map.Entry<String, Tier> entry : tiers.entrySet()) {
            String tierId = entry.getKey() == null ? "" : entry.getKey().trim();
            Tier tier = entry.getValue();
            if (!tierId.isEmpty() && tier != null) {
                out.add(new CurveTable.Tier(tierId, tier.from == null ? 1 : tier.from, blankToNull(tier.name)));
            }
        }
        return out;
    }

    /** This curve tabulated. */
    @Nonnull
    public CurveTable table() {
        List<CurveTable.Piece> plain = new ArrayList<>();
        for (Piece piece : pieces()) {
            plain.add(piece.plain());
        }
        return CurveTable.build(plain, beyondEvery(), tiers());
    }

    /**
     * This curve tabulated as {@link #table()}, with its last piece, when that is a Hybrid one, carried on by the same
     * formula to {@code level} when that is past its own {@code To}: what a level past the top would start at, for a
     * reading that shows how far the next level lies from the top (no climb reaches it, since a track stops at the
     * curve's top). A {@code level} at or below the top changes nothing.
     */
    @Nonnull
    public CurveTable tableThrough(int level) {
        return through(level).table();
    }

    /**
     * This curve with its last piece, when that is a Hybrid one, carried on by the same formula to {@code level} when
     * that is past its own {@code To}, every level below kept to the bit and everything else (its id, Beyond and Tiers)
     * as written: what a consumer runs its own copy of a shared curve to when its levels go past the top (a server
     * owner's raised level cap, written into the curve store's owner layer). This curve itself when its last piece is
     * no Hybrid one or already reaches {@code level}.
     */
    @Nonnull
    public TrackCurveAsset through(int level) {
        List<Piece> written = pieces();
        Piece last = written.isEmpty() ? null : written.get(written.size() - 1);
        if (last == null || last.hasFloors() || last.hybrid == null || last.to == null || level <= last.to) {
            return this;
        }
        Piece carried = new Piece();
        carried.hybrid = last.hybrid;
        carried.to = level;
        Piece[] out = written.toArray(new Piece[0]);
        out[out.length - 1] = carried;
        TrackCurveAsset copy = new TrackCurveAsset();
        copy.id = id;
        copy.data = data;
        copy.pieces = out;
        copy.beyond = beyond;
        copy.tiers = tiers;
        return copy;
    }

    @Nullable
    static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** One piece: a {@code Hybrid} group with its {@code To} level, or a list of {@code Floors}. */
    public static final class Piece {

        @Nullable protected Hybrid hybrid;
        @Nullable protected long[] floors;
        @Nullable protected Integer to;

        public static final BuilderCodec<Piece> CODEC = BuilderCodec.builder(Piece.class, Piece::new)
                .appendInherited(new KeyedCodec<>("Hybrid", Hybrid.CODEC, false),
                        (o, v) -> o.hybrid = v, o -> o.hybrid, (o, p) -> o.hybrid = p.hybrid)
                .documentation("The hybrid formula for this piece's levels, which needs a To. A level costs the "
                        + "OSRS sum of floor(l + Scale * 2^(l / 7)) over the levels below it, quartered, plus "
                        + "Base * level * (level - 1) / 2. Write Hybrid or Floors, never both.").add()
                .appendInherited(new KeyedCodec<>("Floors", Codec.LONG_ARRAY, false),
                        (o, v) -> o.floors = v, o -> o.floors, (o, p) -> o.floors = p.floors)
                .documentation("The value each of this piece's levels starts at, in order and ascending. In the "
                        + "first piece the first number is level 1's, which may be below 0 for a value a player "
                        + "can lose. Its levels end where the list does, so it takes no To.").add()
                .appendInherited(new KeyedCodec<>("To", Codec.INTEGER, false),
                        (o, v) -> o.to = v, o -> o.to, (o, p) -> o.to = p.to)
                .documentation("The last level a Hybrid piece covers. A Hybrid piece without it adds no levels.")
                .add()
                .build();

        public Piece() {
        }

        /** Java-side factory for a Hybrid piece. */
        @Nonnull
        public static Piece hybrid(double base, double scale, int to) {
            Piece p = new Piece();
            p.hybrid = new Hybrid();
            p.hybrid.base = base;
            p.hybrid.scale = scale;
            p.to = to;
            return p;
        }

        /** Java-side factory for a Floors piece. */
        @Nonnull
        public static Piece floors(@Nonnull long... floors) {
            Piece p = new Piece();
            p.floors = floors.clone();
            return p;
        }

        /** True when a Hybrid group is written. */
        public boolean hasHybrid() {
            return hybrid != null;
        }

        /** True when a non-empty Floors list is written. */
        public boolean hasFloors() {
            return floors != null && floors.length > 0;
        }

        @Nullable
        public Integer to() {
            return to;
        }

        /** The floors as written, or an empty array. */
        @Nonnull
        public long[] floorsOrEmpty() {
            return floors == null ? new long[0] : floors.clone();
        }

        /** This piece as the table reads it. */
        @Nonnull
        CurveTable.Piece plain() {
            if (hasFloors()) {
                return new CurveTable.Piece(null, null, floors.clone(), null);
            }
            if (hybrid != null) {
                return new CurveTable.Piece(hybrid.base == null ? 0.0 : hybrid.base,
                        hybrid.scale == null ? 0.0 : hybrid.scale, null, to);
            }
            return new CurveTable.Piece(null, null, null, to);
        }
    }

    /** The hybrid formula's two numbers. */
    public static final class Hybrid {

        @Nullable protected Double base;
        @Nullable protected Double scale;

        public static final BuilderCodec<Hybrid> CODEC = BuilderCodec.builder(Hybrid.class, Hybrid::new)
                .appendInherited(new KeyedCodec<>("Base", Codec.DOUBLE, false),
                        (o, v) -> o.base = v, o -> o.base, (o, p) -> o.base = p.base)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("The quadratic half: each level costs Base * level * (level - 1) / 2 on top of "
                        + "the sum. 0 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Scale", Codec.DOUBLE, false),
                        (o, v) -> o.scale = v, o -> o.scale, (o, p) -> o.scale = p.scale)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("The OSRS half: the sum of floor(l + Scale * 2^(l / 7)) over the levels below, "
                        + "quartered, which doubles about every seven levels. 0 when unauthored.").add()
                .build();

        public Hybrid() {
        }
    }

    /** The Beyond group: the levels past the top. */
    public static final class Beyond {

        @Nullable protected Long every;

        public static final BuilderCodec<Beyond> CODEC = BuilderCodec.builder(Beyond.class, Beyond::new)
                .appendInherited(new KeyedCodec<>("Every", Codec.LONG, false),
                        (o, v) -> o.every = v, o -> o.every, (o, p) -> o.every = p.every)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("How much more value each level past the top takes. Zero or less, or "
                        + "unauthored, adds no levels past the top.").add()
                .build();

        public Beyond() {
        }
    }

    /** One named range of levels. */
    public static final class Tier {

        @Nullable protected Integer from;
        @Nullable protected String name;

        public static final BuilderCodec<Tier> CODEC = BuilderCodec.builder(Tier.class, Tier::new)
                .appendInherited(new KeyedCodec<>("From", Codec.INTEGER, false),
                        (o, v) -> o.from = v, o -> o.from, (o, p) -> o.from = p.from)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("The first level this tier holds; it lasts until the next tier's From. 1 when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("Name", Codec.STRING, false),
                        (o, v) -> o.name = v, o -> o.name, (o, p) -> o.name = p.name)
                .documentation("Localization key for the tier's name, in your own lang file. Unauthored, the "
                        + "tier is named by its id.").add()
                .build();

        public Tier() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Tier of(int from, @Nullable String nameKey) {
            Tier t = new Tier();
            t.from = from;
            t.name = blankToNull(nameKey);
            return t;
        }
    }
}
