package com.ziggfreed.common.achievement.asset;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.asset.NestedAssetId;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.asset.ContentListingAsset;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.asset.ContentRewardsAsset;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.asset.ObjectiveLeafAsset;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * One authored achievement, at {@code Server/ZiggfreedCommon/Achievements/<id>.json}. The FILE NAME
 * is the achievement id.
 *
 * <p>Pattern A: this codec IS the schema, and the engine decodes straight into typed fields. Every
 * leaf of every group is {@code appendInherited}, so a file that carries {@code "Parent": "<id>"}
 * may retune one number and inherit everything it did not mention.
 *
 * <pre>{@code
 * { "Parent": "gather_base",
 *   "Enabled": true,
 *   "Text":    { "TitleKey": "yourmod.ach.prospector.title", "FlavorKey": "yourmod.ach.prospector.flavor" },
 *   "Listing": { "Category": "gathering", "Subcategory": "ore", "SortOrder": 10,
 *                "Icon": "Copper_Ore", "Hidden": false, "Tags": ["gathering"] },
 *   "Scoring": { "Points": 20, "CountsTowardTotal": true },
 *   "Requires":{ "Factors": [ {"Factor": "yourmod:trade_rank", "Min": 5} ] },
 *   "Criteria":{ "mine-copper":   { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore", "Amount": 500 },
 *                "gather-copper": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 500 } },
 *   "Rewards": { "Auto": [ { "Kind": "yourmod:currency", "Params": { "Id": "coin", "Amount": "50" } } ] },
 *   "Meta":    { "yourmod": { "Chain": { "Id": "prospecting", "Tier": 2 } } } }
 * }</pre>
 *
 * <p><b>{@code Criteria} is keyed by criterion id, and the KEY is what progress is stored under</b>
 * (exactly like a quest's {@code Objectives}), so renaming a key starts that criterion over for
 * everybody while adding, removing, or reordering entries never moves anyone's progress. A child
 * that carries {@code Parent} may retune one criterion by key and keeps every criterion it did not
 * mention.
 *
 * <p><b>Display text is keys, never sentences.</b> {@code Text.TitleKey} and {@code Text.FlavorKey}
 * are localization keys the player's own client resolves in the player's own language.
 *
 * <p><b>To retune one somebody else shipped</b>, override the file by id (a same-named file in a
 * later pack), or ship your own file with {@code Parent} set to theirs and author only what you
 * change. To take one out of circulation, set {@code Enabled} to false rather than deleting it, so
 * a player who already earned it keeps it.
 */
public final class AchievementAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, AchievementAsset>> {

    private String id;
    private AssetExtraInfo.Data data;
    /** Where the file was read from, for a finding that has to name it. Never authored. */
    @Nullable private String sourcePath;

    @Nullable private Boolean enabled;
    @Nullable private Boolean isAbstract;
    @Nullable private ContentTextAsset text;
    @Nullable private Listing listing;
    @Nullable private Scoring scoring;
    @Nullable private GateSpec requires;
    @Nullable private Map<String, ObjectiveLeafAsset> criteria;
    @Nullable private String[] metaChildren;
    @Nullable private ContentRewardsAsset rewards;
    @Nullable private Map<String, JsonElement> meta;

    public static final AssetBuilderCodec<String, AchievementAsset> CODEC = AssetBuilderCodec.builder(
                    AchievementAsset.class,
                    AchievementAsset::new,
                    Codec.STRING,
                    // The engine's asset key is the verbatim filename while every consumer addresses
                    // an achievement lower-cased; canonicalizing at the one decode authority keeps
                    // getId() the same string everywhere.
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // An optional human-readable echo of the asset key (the authoritative key is the
            // filename), consumed by a no-op setter and emitted on encode for round-trip.
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op: the id comes from the filename */ },
                    a -> a.id)
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether the achievement is in circulation; unauthored means true. Setting false "
                    + "stops it being earned or listed while leaving it with whoever already earned it.")
            .add()
            // The ONE field that deliberately does NOT inherit: a child of a skeleton is a real
            // achievement, so inheriting this would make every child of a base unearnable too.
            .append(new KeyedCodec<>("Abstract", Codec.BOOLEAN, false),
                    (a, v) -> a.isAbstract = v, a -> a.isAbstract)
            .documentation("Mark a file that exists only to be inherited from. It stays available as a Parent "
                    + "target and is never earnable, so a shared skeleton needs no criteria of its own. It never "
                    + "carries down to a child: inheriting from a skeleton makes a real achievement.")
            .add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the player reads, as localization keys.")
            .add()
            .appendInherited(new KeyedCodec<>("Listing", Listing.CODEC, false),
                    (a, v) -> a.listing = v, a -> a.listing, (a, p) -> a.listing = p.listing)
            .documentation("How it is grouped, ordered, illustrated, and whether it is listed before it is "
                    + "earned.")
            .add()
            .appendInherited(new KeyedCodec<>("Scoring", Scoring.CODEC, false),
                    (a, v) -> a.scoring = v, a -> a.scoring, (a, p) -> a.scoring = p.scoring)
            .documentation("What it is worth, and whether that worth counts toward a player's total.")
            .add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation("What a player must already have or have done before this can progress at all. An "
                    + "unauthored block asks for nothing.")
            .add()
            .appendInherited(new KeyedCodec<>("Criteria",
                            new InheritMapCodec<>(ObjectiveLeafAsset.CODEC), false),
                    (a, v) -> a.criteria = v, a -> a.criteria, (a, p) -> a.criteria = p.criteria)
            .documentation("Everything that has to be done, ALL of it, keyed by criterion id. The key is also "
                    + "what progress is stored under, so renaming one starts that criterion over. A child "
                    + "achievement may retune one criterion by id and keeps every criterion it did not mention.")
            .add()
            .appendInherited(new KeyedCodec<>("MetaChildren", Codec.STRING_ARRAY, false),
                    (a, v) -> a.metaChildren = v, a -> a.metaChildren,
                    (a, p) -> a.metaChildren = p.metaChildren)
            .documentation("Achievement ids that must all be earned for this one to earn itself, for a capstone "
                    + "over a set. An achievement with these needs no Criteria of its own.")
            .add()
            .appendInherited(new KeyedCodec<>("Rewards", ContentRewardsAsset.CODEC, false),
                    (a, v) -> a.rewards = v, a -> a.rewards, (a, p) -> a.rewards = p.rewards)
            .documentation("What earning it pays, split by the two moments a payout can land in: Auto lands the "
                    + "instant it is earned, Claim waits on the achievements surface to be collected.")
            .add()
            .appendInherited(new KeyedCodec<>(ContentMeta.KEY, ContentMeta.CODEC, false),
                    (a, v) -> a.meta = v, a -> a.meta, (a, p) -> a.meta = p.meta)
            .documentation(ContentMeta.DOCUMENTATION)
            .add()
            // The engine names an asset after its FILE and ignores the folders above it. This folds
            // every _-marked folder back into the id, so an author can group achievements into
            // folders AND keep the ids apart. See NestedAssetId.
            .afterDecode(AchievementAsset::applyNestedId)
            .build();

    public AchievementAsset() {
    }

    /** The store's content path, the root {@link NestedAssetId} measures folder depth from. */
    static final String TYPE_ROOT = "ZiggfreedCommon/Achievements";

    /**
     * Fold the {@code _}-marked folders above this file into its id, and remember where it was read
     * from. Runs once per decode, off the path the asset store hands every codec.
     */
    private static void applyNestedId(@Nonnull AchievementAsset asset, @Nullable ExtraInfo extraInfo) {
        if (!(extraInfo instanceof AssetExtraInfo<?> assetInfo)) {
            return;
        }
        Path path = assetInfo.getAssetPath();
        if (path == null || asset.id == null) {
            return;
        }
        asset.sourcePath = path.toString();
        asset.id = NestedAssetId.effectiveId(path, TYPE_ROOT, asset.id);
    }

    @Override
    public String getId() {
        return id;
    }

    /** Where this file was read from, or null when it was not read from one. */
    @Nullable
    public String getSourcePath() {
        return sourcePath;
    }

    /**
     * The id this file named as its {@code Parent}, lower-cased, or null when it named none.
     *
     * <p>Read back off the asset key the engine supplied rather than off a field of our own: the
     * engine has already RESOLVED the parent by the time a decode finishes, so this is a record of
     * what was asked for, which is exactly what a load-time audit needs to notice a typo.
     */
    @Nullable
    public String getParentId() {
        Object parentKey = data == null ? null : data.getParentKey();
        if (!(parentKey instanceof String parentId) || parentId.isBlank()) {
            return null;
        }
        return parentId.trim().toLowerCase(Locale.ROOT);
    }

    /** In circulation? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** A skeleton that exists only to be inherited from, never earnable. */
    public boolean isAbstract() {
        return isAbstract != null && isAbstract;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    @Nullable
    public Listing getListing() {
        return listing;
    }

    @Nullable
    public Scoring getScoring() {
        return scoring;
    }

    /** The authored requirements, or null when it asks for nothing. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /** The authored criteria in authored order, keyed by criterion id (the progress key). */
    @Nonnull
    public Map<String, ObjectiveLeafAsset> criteriaOrEmpty() {
        return criteria == null ? Map.of() : criteria;
    }

    /** The authored meta children, in authored order. */
    @Nonnull
    public String[] metaChildrenOrEmpty() {
        return metaChildren == null ? new String[0] : metaChildren;
    }

    /** The authored rewards group, or null when it pays nothing. */
    @Nullable
    public ContentRewardsAsset getRewards() {
        return rewards;
    }

    /** The per-namespace extra facts, exactly as authored; empty when the file carried none. */
    @Nonnull
    public Map<String, JsonElement> metaOrEmpty() {
        return ContentMeta.orEmpty(meta);
    }

    /**
     * Fold this asset into the runtime {@link AchievementDefinition}: the engine's
     * {@link Achievement} plus the presentation and gate data the engine deliberately does not model.
     *
     * <p>Each criterion's engine-side id is its authored KEY, which is also what its progress is
     * stored under, so what a reader sees and what a store writes cannot disagree.
     */
    @Nonnull
    public AchievementDefinition toDefinition() {
        String achievementId = id == null ? "" : id;

        Achievement.Builder achievement = Achievement.builder(achievementId)
                .available(isEnabled())
                .hidden(listing != null && listing.isHidden())
                .requirePrerequisites(listing != null && listing.isRequirePrerequisites())
                .points(scoring == null ? Scoring.DEFAULT_POINTS : scoring.pointsOrDefault())
                .countsTowardTotal(scoring == null || scoring.isCountsTowardTotal())
                .tags(listing == null ? List.of() : listing.tagList());

        Map<String, String> criterionText = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectiveLeafAsset> entry : criteriaOrEmpty().entrySet()) {
            ObjectiveLeafAsset criterion = entry.getValue();
            if (criterion == null) {
                continue;
            }
            ObjectiveDef def = criterion.toDefBuilder(entry.getKey()).build();
            achievement.criterion(def);
            if (criterion.getTextKey() != null && !criterion.getTextKey().isBlank()) {
                criterionText.put(entry.getKey(), criterion.getTextKey());
            }
        }

        List<String> children = new ArrayList<>();
        for (String child : metaChildrenOrEmpty()) {
            if (child != null && !child.isBlank()) {
                children.add(child.trim().toLowerCase(Locale.ROOT));
            }
        }
        achievement.metaChildren(children);

        ContentRewardsAsset pay = rewards;
        if (pay != null) {
            achievement.autoRewards(pay.auto());
            achievement.claimRewards(pay.claim());
        }

        return new AchievementDefinition(achievementId, achievement.build(),
                text == null ? null : text.getTitleKey(),
                text == null ? null : text.getFlavorKey(),
                text == null ? null : text.getDisplayName(),
                text == null ? List.of() : text.titleArgs(),
                text == null ? List.of() : text.flavorArgs(),
                listing == null ? null : listing.getCategory(),
                listing == null ? null : listing.getSubcategory(),
                listing == null ? 0 : listing.sortOrderOrZero(),
                listing == null ? List.of() : listing.chainList(),
                listing == null ? null : listing.getIcon(),
                requires == null ? GateSpec.OPEN : requires,
                criterionText, metaOrEmpty());
    }

    // ==================== Listing ====================

    /** How it is grouped, ordered, illustrated, and whether it is listed before it is earned. */
    public static final class Listing extends ContentListingAsset {

        @Nullable protected String subcategory;

        public static final BuilderCodec<Listing> CODEC =
                appendLeaves(BuilderCodec.builder(Listing.class, Listing::new))
                        .appendInherited(new KeyedCodec<>("Subcategory", Codec.STRING, false),
                                (o, v) -> o.subcategory = v, o -> o.subcategory,
                                (o, p) -> o.subcategory = p.subcategory)
                        .documentation("A second level of grouping inside a Category, for a category big "
                                + "enough to need one.").add()
                        .build();

        public Listing() {
        }

        @Nullable
        public String getSubcategory() {
            return subcategory;
        }
    }

    // ==================== Scoring ====================

    /** What it is worth, and whether that worth counts toward a player's total. */
    public static final class Scoring {

        /** What an achievement authoring no {@code Points} is worth. */
        public static final int DEFAULT_POINTS = 10;

        @Nullable protected Integer points;
        @Nullable protected Boolean countsTowardTotal;

        public static final BuilderCodec<Scoring> CODEC = BuilderCodec.builder(Scoring.class, Scoring::new)
                .appendInherited(new KeyedCodec<>("Points", Codec.INTEGER, false),
                        (o, v) -> o.points = v, o -> o.points, (o, p) -> o.points = p.points)
                .metadata(EditorSchema.defaultValue(DEFAULT_POINTS))
                .documentation("What earning this is worth; unauthored means " + DEFAULT_POINTS + ". Keep the "
                        + "scale consistent across a pack, since a player's total is the sum and a milestone "
                        + "reward is measured against it.").add()
                .appendInherited(new KeyedCodec<>("CountsTowardTotal", Codec.BOOLEAN, false),
                        (o, v) -> o.countsTowardTotal = v, o -> o.countsTowardTotal,
                        (o, p) -> o.countsTowardTotal = p.countsTowardTotal)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether the points count toward a player's total; unauthored means true. Set "
                        + "false for something nobody can earn any more, so a total stays comparable between a "
                        + "long-standing player and a new one.").add()
                .build();

        public Scoring() {
        }

        @Nullable
        public Integer getPoints() {
            return points;
        }

        public int pointsOrDefault() {
            return points == null ? DEFAULT_POINTS : Math.max(0, points);
        }

        public boolean isCountsTowardTotal() {
            return countsTowardTotal == null || countsTowardTotal;
        }
    }
}
