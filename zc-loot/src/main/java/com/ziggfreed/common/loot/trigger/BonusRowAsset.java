package com.ziggfreed.common.loot.trigger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.asset.SeasonLeaf;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.season.SeasonGate;

/**
 * Something extra a moment hands over, ON TOP of what the game already gives for it: a find in a
 * broken block, a bonus for a kill, an extra in a hand harvest. One row per file at
 * {@code Server/ZiggfreedCommon/BonusRows/<Id>.json}; the filename is the row's id, so prefix it
 * with your pack's own prefix, since two packs' rows with one filename are one row.
 *
 * <pre>{@code
 * {
 *   "When":   { "Kind": "BreakBlock", "Match": "Rock_*" },
 *   "Chance": { "Base": 2.0 },
 *   "Loot":   { "Rolls": [ { "Grants": { "Items": [ { "Item": "My_Pack_Geode", "Count": 1 } ] },
 *                            "Cue": "My_Pack_Geode_Found" } ] }
 * }
 * }</pre>
 *
 * <p>A row fires on every matching moment with no other gate: any block a player breaks that they
 * did not place, any kill credited to a player, any item a player harvests by hand. Where several
 * rows match one name the one pinning down the most characters wins; a calendar event's run is the
 * row's own {@code Season}; anything else more selective than a name (a skill, a held tool) belongs in
 * a roll's own {@code Conditions}. A row with no {@code Loot} is a deliberate hole that stops a
 * broader pattern covering the name; {@code Enabled: false} takes the row out entirely, and so does
 * its {@code Season} while that event is not running. A server owner retunes or switches off a row
 * by id in {@code mods/ziggfreedcommon/bonus-rows.json} ({@link BonusRowOwnerLayers}).
 */
public final class BonusRowAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, BonusRowAsset>> {

    /** Where the rows live under a pack's {@code Server/} folder. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/BonusRows";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private When when;
    @Nullable private FactorFormula chance;
    @Nullable private LootRef loot;
    @Nullable private Boolean enabled;
    @Nullable private String season;

    /** WHEN the row fires: the moment, and which block, mob or item within it. */
    public static final class When {

        @Nullable protected String kind;
        @Nullable protected String match;

        public static final BuilderCodec<When> CODEC = BuilderCodec.builder(When.class, When::new)
                .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                        (o, v) -> o.kind = v, o -> o.kind, (o, p) -> o.kind = p.kind)
                .metadata(EditorSchema.oneOfDocumented(
                        "BreakBlock", "Fires when a player breaks a matching block they did not place",
                        "KillMob", "Fires when a player is credited with killing a matching mob",
                        "PickupItem", "Fires when a player harvests a matching item by hand, after the harvest lands"))
                .documentation("Which moment this row answers for: \"BreakBlock\", \"KillMob\" or "
                        + "\"PickupItem\". Each fires with no other gate. A row naming no moment never "
                        + "fires, and /zigloot validate names it.").add()
                .appendInherited(new KeyedCodec<>("Match", Codec.STRING, false),
                        (o, v) -> o.match = v, o -> o.match, (o, p) -> o.match = p.match)
                .documentation("Which block, mob or item, as a name pattern: \"Rock_Stone\" exactly, "
                        + "\"Rock_*\" starting with, \"*_Cracked\" ending with, \"*Geode*\" anywhere in the "
                        + "name, \"*\" everything. Case is ignored, and where several rows match one name "
                        + "the one pinning down the most characters wins. Omit it to cover everything in "
                        + "the moment.").add()
                .build();

        public When() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static When of(@Nullable BonusMoment moment, @Nullable String match) {
            When when = new When();
            when.kind = moment == null ? null : moment.token();
            when.match = match;
            return when;
        }

        /** The moment named, or null when none was named or the name was unknown. */
        @Nullable
        public BonusMoment moment() {
            return BonusMoment.parse(kind);
        }

        /** The moment exactly as authored, for a message that has to quote an unknown one. */
        @Nonnull
        public String rawKind() {
            return kind == null ? "" : kind.trim();
        }

        /** The name pattern, trimmed; blank means everything in the moment. */
        @Nonnull
        public String match() {
            return match == null ? "" : match.trim();
        }
    }

    public static final AssetBuilderCodec<String, BonusRowAsset> CODEC = SeasonLeaf.append(AssetBuilderCodec.builder(
                    BonusRowAsset.class,
                    BonusRowAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("When", When.CODEC, false),
                    (a, v) -> a.when = v, a -> a.when, (a, p) -> a.when = p.when)
            .documentation("Which moments this row answers for: the Kind of moment, and the name pattern "
                    + "within it. A row that names no moment never fires.").add()
            .appendInherited(new KeyedCodec<>("Chance", FactorFormula.codec(EditorDataSets.FACTORS), false),
                    (a, v) -> a.chance = v, a -> a.chance, (a, p) -> a.chance = p.chance)
            .documentation("How likely the row is, as a PERCENT: Base plus each factor's value * Weight, "
                    + "held inside Clamp, so 5 is five percent, not 0.05. Omit it and the row always "
                    + "fires, leaving the odds to the rolls in its Loot.").add()
            .appendInherited(new KeyedCodec<>("Loot", LootRef.CODEC, false),
                    (a, v) -> a.loot = v, a -> a.loot, (a, p) -> a.loot = p.loot)
            .documentation("What the moment hands over: shared Lootables by id (a named table's Pool "
                    + "draws too), Rolls written here, or both. A Moment_Item reward hands over another of "
                    + "the harvested stack, in a PickupItem row only. A Cue on a roll names the "
                    + "FeedbackMoment that announces the find. A row granting nothing is a deliberate hole "
                    + "that stops a broader pattern covering the same name.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to take this row out of the table entirely, which lets a broader "
                    + "pattern cover the same names again. To keep the row but hand nothing over, leave it "
                    + "on and write no Loot.").add(),
                    (a, v) -> a.season = v, a -> a.season)
            .build();

    public BonusRowAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static BonusRowAsset of(@Nullable BonusMoment moment, @Nullable String match,
            @Nullable FactorFormula chance, @Nullable LootRef loot, @Nullable Boolean enabled) {
        BonusRowAsset asset = new BonusRowAsset();
        asset.when = When.of(moment, match);
        asset.chance = chance;
        asset.loot = loot;
        asset.enabled = enabled;
        return asset;
    }

    @Override
    public String getId() {
        return id;
    }

    /** The moment group, or null when the file wrote none. */
    @Nullable
    public When getWhen() {
        return when;
    }

    /** The moment this row answers for, or null when none was named or the name was unknown. */
    @Nullable
    public BonusMoment moment() {
        return when == null ? null : when.moment();
    }

    /** The name pattern; blank means everything in its moment. */
    @Nonnull
    public String match() {
        return when == null ? "" : when.match();
    }

    /** The odds the row states for itself as a percent, or null to always fire. */
    @Nullable
    public FactorFormula getChance() {
        return chance;
    }

    @Nullable
    public LootRef getLoot() {
        return loot;
    }

    /** True unless the row was switched off. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /**
     * The calendar event this row belongs to, trimmed, or null when it rolls all year. Out of its
     * season the row is out of the table, exactly as {@code Enabled: false} would leave it.
     */
    @Nullable
    public String getSeason() {
        return SeasonGate.normalize(season);
    }
}
