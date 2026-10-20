package com.ziggfreed.common.track.source;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.match.When;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * What a moment pays onto a track, at {@code Server/ZiggfreedCommon/TrackSources/<Id>.json}: a store of its own, so
 * any pack adds rows to anyone's track. The file name is the id; prefix it with your pack's own prefix, since two
 * packs' files with one name are one file.
 *
 * <pre>{@code
 * { "Track": "Your_Mining",
 *   "When": { "Kind": "BREAK_BLOCK" },
 *   "Rows": { "Ore_Iron*": { "Xp": 50, "Level": 10 }, "Ore_*": 6, "Rock_*": 3 } }
 *
 * { "Track": "Your_Swords",
 *   "When": { "Kind": "DEAL_DAMAGE", "Qualifier": "Weapon_Sword*" },
 *   "PerAmount": 1 }
 * }</pre>
 *
 * <p>{@code When} names the moment (an objective kind id), and optionally a {@code Qualifier} every row asks of it
 * (a hit's weapon); each {@code Rows} key is a name pattern over the moment's target (the block, mob or item). The
 * top-level {@code Xp}, {@code PerAmount} and {@code PerMaxHealth} are a row of their own at {@code When.Match}
 * ({@code *} unwritten), held at the top-level {@code Max}, which also caps every row writing no {@code Max} of its
 * own ({@code { "When": { "Kind": "KILL_ENTITY" }, "PerMaxHealth": 1, "Max": 500 }} pays a kill its creature's
 * health, at most 500),
 * which a more specific row beats. Every source on one track shares one table: for each moment the most specific
 * matching row the player's level has reached pays ({@code MomentTable}; a row's {@code Level} is a soft gate, and
 * two files writing one pattern for one moment are one row, the later file by id winning it). A server owner
 * retunes a source by id in {@code mods/ziggfreedcommon/track-sources.json}.
 *
 * <p>The anti-abuse knobs are off until written: {@code Diminish} (zc-loot's {@link DiminishSpec}, counted per
 * player and source in its window, kept in memory) scales every moment it pays, and {@code SkipSpawnerMobs} pays
 * nothing for a mob a spawn marker or a spawn beacon stood up. A top-level {@code Requires} follows the mod gate
 * (a plain mod-presence check keeps the file out where that mod is missing) and asks the rest of the player at each
 * moment.
 */
public final class TrackSourceAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TrackSourceAsset>> {

    /** The store's content path. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackSources";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean enabled;
    @Nullable protected String track;
    @Nullable protected When when;
    @Nullable protected Double xp;
    @Nullable protected Double perAmount;
    @Nullable protected Double perMaxHealth;
    @Nullable protected Double max;
    @Nullable protected Map<String, SourceRow> rows;
    @Nullable protected GateSpec requires;
    @Nullable protected DiminishSpec diminish;
    @Nullable protected Boolean skipSpawnerMobs;

    /** The live part of the {@code Requires} block, lifted once per instance ({@link StoreGates#live}). */
    @Nullable private volatile GateSpec[] live;

    public static final AssetBuilderCodec<String, TrackSourceAsset> CODEC = AssetBuilderCodec.builder(
                    TrackSourceAsset.class,
                    TrackSourceAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether this source pays. Unauthored means true; false keeps the file and pays "
                    + "nothing, which lets another file's broader pattern pay those moments again.").add()
            .appendInherited(new KeyedCodec<>("Track", Codec.STRING, false),
                    (a, v) -> a.track = v, a -> a.track, (a, p) -> a.track = p.track)
            .metadata(EditorSchema.assetRef(TrackAsset.class))
            .documentation("The track this source pays onto, by id (any pack's track). A source naming no track, "
                    + "or one this server does not have, pays nothing.").add()
            .appendInherited(new KeyedCodec<>("When", When.CODEC, false),
                    (a, v) -> a.when = v, a -> a.when, (a, p) -> a.when = p.when)
            .documentation("Which moment pays: its Kind (BREAK_BLOCK, KILL_ENTITY, PICKUP_ITEM, DEAL_DAMAGE, ...), "
                    + "a Qualifier every row asks of it (DEAL_DAMAGE's is the weapon that hit), and a Match "
                    + "naming the targets the top-level Xp and PerAmount pay for. A source naming no Kind pays "
                    + "nothing.").add()
            .appendInherited(new KeyedCodec<>("Xp", Codec.DOUBLE, false),
                    (a, v) -> a.xp = v, a -> a.xp, (a, p) -> a.xp = p.xp)
            .documentation("What a moment When.Match covers pays, once per moment, when no more specific row "
                    + "does. Leave it out to pay only through Rows.").add()
            .appendInherited(new KeyedCodec<>("PerAmount", Codec.DOUBLE, false),
                    (a, v) -> a.perAmount = v, a -> a.perAmount, (a, p) -> a.perAmount = p.perAmount)
            .documentation("What each unit of a moment's amount pays for a moment When.Match covers, when no more "
                    + "specific row does: a hit's damage (capped at what the target had left), a distance, a "
                    + "craft's count.").add()
            .appendInherited(new KeyedCodec<>("PerMaxHealth", Codec.DOUBLE, false),
                    (a, v) -> a.perMaxHealth = v, a -> a.perMaxHealth, (a, p) -> a.perMaxHealth = p.perMaxHealth)
            .documentation("What each point of the target's maximum health pays for a moment When.Match covers, "
                    + "when no more specific row does: 1 on a KILL_ENTITY source pays every creature's whole health "
                    + "bar, so a tougher creature pays more with nothing written per creature.").add()
            .appendInherited(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                    (a, v) -> a.max = v, a -> a.max, (a, p) -> a.max = p.max)
            .documentation("The most one moment pays from any row of this source that writes no Max of its own: a "
                    + "cap that keeps a boss's health bar from paying a fortune. Leave it out, or write 0, for no "
                    + "limit.").add()
            .appendInherited(new KeyedCodec<>("Rows", new InheritMapCodec<>(SourceRow.NUMBER_OR_ROW), false),
                    (a, v) -> a.rows = v, a -> a.rows, (a, p) -> a.rows = p.rows)
            .documentation("What each target pays, by name pattern (\"Ore_Iron\" exactly, \"Ore_*\" starting with, "
                    + "\"*_Cracked\" ending with, \"*Gem*\" anywhere, \"*\" everything): a number is the Xp, or a "
                    + "group with Xp, PerAmount and Level. The most specific pattern the player's level has "
                    + "reached pays. Under Parent, rows merge by pattern.").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation(StoreGates.DOCUMENTATION).add()
            .appendInherited(new KeyedCodec<>("Diminish", DiminishSpec.CODEC, false),
                    (a, v) -> a.diminish = v, a -> a.diminish, (a, p) -> a.diminish = p.diminish)
            .documentation("Diminishing returns on this source: each moment it pays counts once in the window, "
                    + "and pays at that step ({ \"Steps\": [1, 1, 0.5], \"Window\": \"Daily\" }). Leave it out "
                    + "to pay every moment in full. Counts start over when the server restarts.").add()
            .appendInherited(new KeyedCodec<>("SkipSpawnerMobs", Codec.BOOLEAN, false),
                    (a, v) -> a.skipSpawnerMobs = v, a -> a.skipSpawnerMobs,
                    (a, p) -> a.skipSpawnerMobs = p.skipSpawnerMobs)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True pays nothing for a mob a spawn marker or a spawn beacon stood up (a mob farm), on "
                    + "a kill or a hit. Unauthored means false.").add()
            .build();

    public TrackSourceAsset() {
    }

    /** Java-side factory (a test); sets the same fields the codec fills. */
    @Nonnull
    public static TrackSourceAsset of(@Nonnull String id, @Nullable String track, @Nullable When when,
            @Nullable Double xp, @Nullable Double perAmount, @Nullable Map<String, SourceRow> rows) {
        TrackSourceAsset asset = new TrackSourceAsset();
        asset.id = id;
        asset.track = track;
        asset.when = when;
        asset.xp = xp;
        asset.perAmount = perAmount;
        asset.rows = rows == null ? null : new LinkedHashMap<>(rows);
        return asset;
    }

    @Override
    public String getId() {
        return id;
    }

    /** True unless switched off. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** The track id as written, trimmed, or null. */
    @Nullable
    public String trackId() {
        return track == null || track.isBlank() ? null : track.trim();
    }

    /** The moment group, or null when the file writes none. */
    @Nullable
    public When when() {
        return when;
    }

    /**
     * The row the top-level Xp, PerAmount and PerMaxHealth make at {@code When.Match}, held at the top-level Max, or
     * null when none of the three is written.
     */
    @Nullable
    public SourceRow baseRow() {
        return xp == null && perAmount == null && perMaxHealth == null ? null
                : SourceRow.of(xp, perAmount, perMaxHealth, max, null);
    }

    /** The rows as written, by pattern, or an empty map. */
    @Nonnull
    public Map<String, SourceRow> rows() {
        return rows == null ? Map.of() : rows;
    }

    /** The cap every row writing no Max of its own pays at most, as written, or null for none. */
    @Nullable
    public Double max() {
        return max;
    }

    /** The diminishing returns, or null for none. */
    @Nullable
    public DiminishSpec diminish() {
        return diminish;
    }

    /** Does this source pay nothing for a spawn marker's or a spawn beacon's mob? */
    public boolean skipsSpawnerMobs() {
        return Boolean.TRUE.equals(skipSpawnerMobs);
    }

    /** The top-level {@code Requires}, or null when none is written. */
    @Nullable
    public GateSpec requires() {
        return requires;
    }

    /** The part of {@link #requires()} asked of the player at each moment, lifted once; null when nothing is. */
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
