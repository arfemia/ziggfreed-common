package com.ziggfreed.common.track.source;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * What a track source's diminishing returns count per, at {@code Server/ZiggfreedCommon/DiminishKeys/<Id>.json}: a
 * source's {@code Diminish.Key} names one by id, and every moment it pays counts under the value this key reads off
 * the moment, so each value keeps a count of its own.
 *
 * <pre>{@code
 * { "Cell": 16 }                      // Cell16: each 16-block cell of a world counts apart (a mob farm's spot)
 * { "Cell": 1 }                       // Position: each block counts apart (once a spot a day for building)
 * { "Target": true }                  // Target: each block, creature or item id counts apart
 * { "Station": true }                 // Station: each station a moment was worked at counts apart
 * { "Victim": true }                  // Victim: each creature or player killed counts apart
 * { "Target": true, "Cell": 8 }       // a pack's own: each id in each 8-block cell
 * }</pre>
 *
 * <p>The switches add up: a key reads every part it switches on, so a pack makes a new key by writing a file, with
 * no code. A part the moment does not carry (a victim on a block broken, a station on a kill) reads as one shared
 * value, so those moments count together under it. A moment's place is the block it happened at (a break, a
 * placement), else the creature it happened to, else the player. The library ships the five keys above; the owner
 * file {@code mods/ziggfreedcommon/diminish-keys.json} retunes one (the owner menu's Diminishing Keys screen writes
 * it).
 */
public final class DiminishKeyAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, DiminishKeyAsset>> {

    /** The store's content path. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/DiminishKeys";

    /** What a part the moment does not carry reads as. */
    static final String NONE = "-";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean target;
    @Nullable protected Boolean qualifier;
    @Nullable protected Boolean victim;
    @Nullable protected Boolean station;
    @Nullable protected Integer cell;

    public static final AssetBuilderCodec<String, DiminishKeyAsset> CODEC = AssetBuilderCodec.builder(
                    DiminishKeyAsset.class,
                    DiminishKeyAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Target", Codec.BOOLEAN, false),
                    (a, v) -> a.target = v, a -> a.target, (a, p) -> a.target = p.target)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True counts each thing a moment happened to apart: the block broken or placed, the "
                    + "creature hit or killed, the item picked up, by id. Unauthored means false.").add()
            .appendInherited(new KeyedCodec<>("Qualifier", Codec.BOOLEAN, false),
                    (a, v) -> a.qualifier = v, a -> a.qualifier, (a, p) -> a.qualifier = p.qualifier)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True counts each qualifier apart: the weapon a hit was dealt with, a craft's recipe "
                    + "kind. Unauthored means false.").add()
            .appendInherited(new KeyedCodec<>("Victim", Codec.BOOLEAN, false),
                    (a, v) -> a.victim = v, a -> a.victim, (a, p) -> a.victim = p.victim)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True counts each creature or player a moment happened to apart, that one entity rather "
                    + "than its kind (the same player killed again). Unauthored means false.").add()
            .appendInherited(new KeyedCodec<>("Station", Codec.BOOLEAN, false),
                    (a, v) -> a.station = v, a -> a.station, (a, p) -> a.station = p.station)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True counts each station a moment was worked at apart (a bench, a sawmill), where the "
                    + "moment names one. Unauthored means false.").add()
            .appendInherited(new KeyedCodec<>("Cell", Codec.INTEGER, false),
                    (a, v) -> a.cell = v, a -> a.cell, (a, p) -> a.cell = p.cell)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("Count each part of the world apart, in cubes this many blocks wide: 1 is each block, "
                    + "16 a cube of 16 blocks each way, each world apart. 0 or unauthored leaves the place out.").add()
            .build();

    public DiminishKeyAsset() {
    }

    /** Java-side factory (a test); sets the same fields the codec fills. */
    @Nonnull
    public static DiminishKeyAsset of(@Nonnull String id, boolean target, boolean qualifier, boolean victim,
            boolean station, int cell) {
        DiminishKeyAsset asset = new DiminishKeyAsset();
        asset.id = id;
        asset.target = target;
        asset.qualifier = qualifier;
        asset.victim = victim;
        asset.station = station;
        asset.cell = cell;
        return asset;
    }

    @Override
    public String getId() {
        return id;
    }

    /** Does it count each target apart? */
    public boolean byTarget() {
        return Boolean.TRUE.equals(target);
    }

    /** Does it count each qualifier apart? */
    public boolean byQualifier() {
        return Boolean.TRUE.equals(qualifier);
    }

    /** Does it count each victim apart? */
    public boolean byVictim() {
        return Boolean.TRUE.equals(victim);
    }

    /** Does it count each station apart? */
    public boolean byStation() {
        return Boolean.TRUE.equals(station);
    }

    /** The cell width it counts places in, 0 for none. */
    public int cell() {
        return cell == null ? 0 : Math.max(0, cell);
    }

    /** Does it read the moment's place at all? */
    public boolean byPlace() {
        return cell() > 0;
    }

    /**
     * What a moment carries for a key to read: its target and qualifier, the one entity it happened to, the station
     * it was worked at, and its place (a world, and a block when {@code placed}); each part null where the moment
     * carries none.
     */
    public record Facts(@Nullable String target, @Nullable String qualifier, @Nullable String victim,
                        @Nullable String station, @Nullable String world, boolean placed, int x, int y, int z) {
    }

    /**
     * The value this key reads off {@code facts}: each part it switches on, in a fixed order, a part the moment does
     * not carry as {@link #NONE}; the empty string for a key that switches nothing on (every moment one count).
     * Case does not matter. Pure.
     */
    @Nonnull
    public String valueOf(@Nonnull Facts facts) {
        StringBuilder out = new StringBuilder();
        if (byTarget()) {
            part(out, "t", facts.target());
        }
        if (byQualifier()) {
            part(out, "q", facts.qualifier());
        }
        if (byVictim()) {
            part(out, "v", facts.victim());
        }
        if (byStation()) {
            part(out, "s", facts.station());
        }
        if (byPlace()) {
            int width = cell();
            String place = facts.placed()
                    ? facts.world() + ":" + Math.floorDiv(facts.x(), width) + "," + Math.floorDiv(facts.y(), width)
                            + "," + Math.floorDiv(facts.z(), width)
                    : null;
            part(out, "c" + width, place);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static void part(@Nonnull StringBuilder out, @Nonnull String tag, @Nullable String value) {
        if (!out.isEmpty()) {
            out.append('|');
        }
        out.append(tag).append('=').append(value == null || value.isBlank() ? NONE : value.trim());
    }
}
