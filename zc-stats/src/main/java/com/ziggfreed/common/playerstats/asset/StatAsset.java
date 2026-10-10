package com.ziggfreed.common.playerstats.asset;

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
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.playerstats.StatBreakdown;
import com.ziggfreed.common.playerstats.StatFormat;
import com.ziggfreed.common.playerstats.StatKind;

/**
 * One stat the player stat store knows, written as a file:
 * {@code Server/ZiggfreedCommon/Stats/<Id>.json}. The FILENAME is the stat id, kept in the spelling the
 * file gives it, and it is the key every player's tally is filed under, so renaming a file starts the
 * stat over.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/Stats/Mob_Kills.json
 * { "NameKey": "ziggfreedcommon.stats.mob_kills", "Kind": "Sum", "Format": "Count",
 *   "From": [ { "Kind": "KILL_ENTITY" } ], "Breakdown": "Target" }
 * }</pre>
 *
 * <p>A stat's file ships with whoever feeds it, so a server shows no stat nobody counts. A Sum, Max or
 * Min stat is counted off the shared moment stream through its {@code From} entries, or by the mod that
 * ships it through {@code PlayerStats}; a Gauge is set by the mod that owns the number; a Native stat is
 * read from what the engine records.
 */
public final class StatAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, StatAsset>> {

    /** Where these files live. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Stats";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected String nameKey;
    @Nullable protected String kind;
    @Nullable protected String format;
    @Nullable protected StatSourceAsset[] from;
    @Nullable protected String breakdown;

    public static final AssetBuilderCodec<String, StatAsset> CODEC = AssetBuilderCodec.builder(
                    StatAsset.class,
                    StatAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                    (a, v) -> a.nameKey = v, a -> a.nameKey, (a, p) -> a.nameKey = p.nameKey)
            .documentation("The full localization key that names this stat wherever it is shown, from your own "
                    + "lang file. Without one it reads as its id.").add()
            .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                    (a, v) -> a.kind = v, a -> a.kind, (a, p) -> a.kind = p.kind)
            .metadata(EditorSchema.oneOfDocumented(
                    "Sum", "A running total: each counted moment adds to it",
                    "Max", "A best that grows: a counted moment raises it when it beats it",
                    "Min", "A best that shrinks, such as a fastest time: a counted moment lowers it",
                    "Gauge", "A number a mod owns and sets, such as a level: ranked, never counted",
                    "Native", "A number the game already records: read, never counted again"))
            .metadata(EditorSchema.defaultValue("Sum"))
            .documentation("What the number means: Sum, Max, Min, Gauge or Native. Unauthored means Sum.").add()
            .appendInherited(new KeyedCodec<>("Format", Codec.STRING, false),
                    (a, v) -> a.format = v, a -> a.format, (a, p) -> a.format = p.format)
            .metadata(EditorSchema.oneOfDocumented(
                    "Count", "A plain count",
                    "Time", "Seconds, read in minutes and seconds: a short span, such as a best time",
                    "Duration", "Seconds, read in hours and minutes: a long span, such as time played",
                    "Distance", "Metres, read as a distance",
                    "Percent", "A share out of 100"))
            .metadata(EditorSchema.defaultValue("Count"))
            .documentation("How the number reads: Count, Time (seconds as minutes and seconds), Duration "
                    + "(seconds as hours and minutes), Distance (metres) or Percent. Unauthored means Count.").add()
            .appendInherited(new KeyedCodec<>("From",
                            new ArrayCodec<>(StatSourceAsset.CODEC, StatSourceAsset[]::new), false),
                    (a, v) -> a.from = v, a -> a.from, (a, p) -> a.from = p.from)
            .documentation("The produced moments that count toward a Sum, Max or Min stat, each written like a "
                    + "quest step (Kind, Target, MatchMode, Qualifier, Zone) plus Count. Leave it out for a "
                    + "stat its mod counts in code, a Gauge or a Native stat. Under Parent this list replaces "
                    + "the parent's whole.").add()
            .appendInherited(new KeyedCodec<>("Breakdown", Codec.STRING, false),
                    (a, v) -> a.breakdown = v, a -> a.breakdown, (a, p) -> a.breakdown = p.breakdown)
            .metadata(EditorSchema.oneOfDocumented(
                    "None", "The grand total only",
                    "Target", "One line per target: the mob, the block, the item",
                    "Qualifier", "One line per qualifier: the tier, the variant",
                    "TargetAtQualifier", "One line per target@qualifier"))
            .metadata(EditorSchema.defaultValue("None"))
            .documentation("Which part of a counted moment names the line it is also filed under, beside the "
                    + "grand total: None, Target, Qualifier or TargetAtQualifier. Unauthored means None.").add()
            .build();

    public StatAsset() {
    }

    /** The stat id, exactly as the filename spells it. */
    @Override
    public String getId() {
        return id;
    }

    /** The localization key naming the stat, or null when the file names none. */
    @Nullable
    public String nameKey() {
        return nameKey == null || nameKey.isBlank() ? null : nameKey.trim();
    }

    /** The authored {@code Kind}, unparsed. */
    @Nullable
    public String getKind() {
        return kind;
    }

    /** The authored {@code Format}, unparsed. */
    @Nullable
    public String getFormat() {
        return format;
    }

    /** The authored {@code Breakdown}, unparsed. */
    @Nullable
    public String getBreakdown() {
        return breakdown;
    }

    /** What the number means; Sum when unauthored, null when the file names a kind nobody knows. */
    @Nullable
    public StatKind kind() {
        return StatKind.parse(kind);
    }

    /** How the number reads; Count when unauthored, null when unknown. */
    @Nullable
    public StatFormat format() {
        return StatFormat.parse(format);
    }

    /** Which part of a moment names its breakdown line; None when unauthored, null when unknown. */
    @Nullable
    public StatBreakdown breakdown() {
        return StatBreakdown.parse(breakdown);
    }

    /** The {@code From} entries, in file order, nulls skipped; empty when none is authored. */
    @Nonnull
    public List<StatSourceAsset> from() {
        List<StatSourceAsset> out = new ArrayList<>();
        if (from != null) {
            for (StatSourceAsset source : from) {
                if (source != null) {
                    out.add(source);
                }
            }
        }
        return out;
    }
}
