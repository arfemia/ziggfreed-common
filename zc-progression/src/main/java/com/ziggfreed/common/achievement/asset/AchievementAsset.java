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
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.asset.NestedAssetId;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.asset.ContentListingAsset;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.asset.ObjectiveLeafAsset;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
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
 *   "Criteria":[ { "Kind": "BREAK_BLOCK", "Target": "Copper_Ore", "Amount": 500 },
 *                { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 500 } ],
 *   "Rewards": [ { "Kind": "yourmod:currency", "Params": { "Id": "coin", "Amount": "50" } } ],
 *   "Meta":    { "yourmod": { "Chain": { "Id": "prospecting", "Tier": 2 } } } }
 * }</pre>
 *
 * <p><b>{@code Criteria} IS AN ORDERED ARRAY AND THE ORDER IS PERMANENT.</b> Progress is stored per
 * criterion by its POSITION in this array, so:
 * <ul>
 *   <li>APPENDING a criterion is safe - the existing positions do not move;</li>
 *   <li>INSERTING, REMOVING, or REORDERING one moves every player's progress onto a different
 *   criterion, which is a data migration rather than an edit;</li>
 *   <li>a {@code Parent} that authors criteria and a child that authors criteria do NOT merge per
 *   entry - the child's array REPLACES the parent's whole. There is no per-index merge, and there
 *   deliberately never will be: a merge keyed by position would let a parent edit silently re-point
 *   every child's stored progress. Inherit the criteria untouched, or state them all.</li>
 * </ul>
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
    @Nullable private ObjectiveLeafAsset[] criteria;
    @Nullable private String[] metaChildren;
    @Nullable private RewardEntryAsset[] rewards;
    @Nullable private RewardEntryAsset[] claimRewards;
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
                            new ArrayCodec<>(ObjectiveLeafAsset.CODEC, ObjectiveLeafAsset[]::new), false),
                    (a, v) -> a.criteria = v, a -> a.criteria, (a, p) -> a.criteria = p.criteria)
            .documentation("Everything that has to be done, ALL of it, in a FIXED order. Progress is stored by "
                    + "each entry's POSITION, so appending is safe while inserting, removing, or reordering moves "
                    + "every player's progress onto a different criterion. This is ONE leaf: a child that authors "
                    + "Criteria replaces the inherited list whole rather than merging into it.")
            .add()
            .appendInherited(new KeyedCodec<>("MetaChildren", Codec.STRING_ARRAY, false),
                    (a, v) -> a.metaChildren = v, a -> a.metaChildren,
                    (a, p) -> a.metaChildren = p.metaChildren)
            .documentation("Achievement ids that must all be earned for this one to earn itself, for a capstone "
                    + "over a set. An achievement with these needs no Criteria of its own.")
            .add()
            .appendInherited(new KeyedCodec<>("Rewards",
                            new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new), false),
                    (a, v) -> a.rewards = v, a -> a.rewards, (a, p) -> a.rewards = p.rewards)
            .documentation("What lands the instant it is earned. This is ONE leaf: author it and an inherited "
                    + "list is replaced whole, omit it and the inherited list carries over.")
            .add()
            .appendInherited(new KeyedCodec<>("ClaimRewards",
                            new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new), false),
                    (a, v) -> a.claimRewards = v, a -> a.claimRewards,
                    (a, p) -> a.claimRewards = p.claimRewards)
            .documentation("What waits to be collected. Authoring any of these is what makes an achievement "
                    + "something a player comes back to a menu for; leave it out and the whole payout lands at "
                    + "the moment it is earned.")
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

    /** The authored criteria in authored order. The position of each is its progress key. */
    @Nonnull
    public ObjectiveLeafAsset[] criteriaOrEmpty() {
        return criteria == null ? new ObjectiveLeafAsset[0] : criteria;
    }

    /** The authored meta children, in authored order. */
    @Nonnull
    public String[] metaChildrenOrEmpty() {
        return metaChildren == null ? new String[0] : metaChildren;
    }

    /** The rewards that land at once, in authored order. */
    @Nonnull
    public RewardEntryAsset[] rewardsOrEmpty() {
        return rewards == null ? new RewardEntryAsset[0] : rewards;
    }

    /** The rewards that wait to be collected, in authored order. */
    @Nonnull
    public RewardEntryAsset[] claimRewardsOrEmpty() {
        return claimRewards == null ? new RewardEntryAsset[0] : claimRewards;
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
     * <p>Each criterion's engine-side id is its POSITION as a decimal string, which is also what its
     * progress is stored under, so what a reader sees and what a store writes cannot disagree.
     */
    @Nonnull
    public AchievementDefinition toDefinition() {
        String achievementId = id == null ? "" : id;

        Achievement.Builder achievement = Achievement.builder(achievementId)
                .available(isEnabled())
                .hidden(listing != null && listing.isHidden())
                .points(scoring == null ? Scoring.DEFAULT_POINTS : scoring.pointsOrDefault())
                .countsTowardTotal(scoring == null || scoring.isCountsTowardTotal())
                .tags(listing == null ? List.of() : listing.tagList());

        Map<Integer, String> criterionText = new LinkedHashMap<>();
        ObjectiveLeafAsset[] authored = criteriaOrEmpty();
        for (int i = 0; i < authored.length; i++) {
            ObjectiveLeafAsset criterion = authored[i];
            if (criterion == null) {
                continue;
            }
            ObjectiveDef def = criterion.toDefBuilder(String.valueOf(i)).build();
            achievement.criterion(def);
            if (criterion.getTextKey() != null && !criterion.getTextKey().isBlank()) {
                criterionText.put(i, criterion.getTextKey());
            }
        }

        List<String> children = new ArrayList<>();
        for (String child : metaChildrenOrEmpty()) {
            if (child != null && !child.isBlank()) {
                children.add(child.trim().toLowerCase(Locale.ROOT));
            }
        }
        achievement.metaChildren(children);

        achievement.autoRewards(specs(rewardsOrEmpty()));
        achievement.claimRewards(specs(claimRewardsOrEmpty()));

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

    @Nonnull
    private static List<RewardSpec> specs(@Nonnull RewardEntryAsset[] authored) {
        List<RewardSpec> out = new ArrayList<>(authored.length);
        for (RewardEntryAsset reward : authored) {
            RewardSpec spec = reward == null ? null : reward.toSpec();
            if (spec != null) {
                out.add(spec);
            }
        }
        return out;
    }

    // ==================== Listing ====================

    /** How it is grouped, ordered, illustrated, and whether it is listed before it is earned. */
    public static final class Listing extends ContentListingAsset {

        @Nullable protected String subcategory;
        @Nullable protected String icon;
        @Nullable protected Boolean hidden;

        public static final BuilderCodec<Listing> CODEC =
                appendLeaves(BuilderCodec.builder(Listing.class, Listing::new))
                        .appendInherited(new KeyedCodec<>("Subcategory", Codec.STRING, false),
                                (o, v) -> o.subcategory = v, o -> o.subcategory,
                                (o, p) -> o.subcategory = p.subcategory)
                        .documentation("A second level of grouping inside a Category, for a category big "
                                + "enough to need one.").add()
                        .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                                (o, v) -> o.icon = v, o -> o.icon, (o, p) -> o.icon = p.icon)
                        .documentation("An item id to illustrate it with. Unauthored leaves the choice to "
                                + "whatever renders it.").add()
                        .appendInherited(new KeyedCodec<>("Hidden", Codec.BOOLEAN, false),
                                (o, v) -> o.hidden = v, o -> o.hidden, (o, p) -> o.hidden = p.hidden)
                        .documentation("Keep it off the list until it is earned, for a surprise or a retired "
                                + "one-off. It still progresses and can still be earned; only the listing is "
                                + "affected, and it always appears once a player has it.").add()
                        .build();

        public Listing() {
        }

        @Nullable
        public String getSubcategory() {
            return subcategory;
        }

        @Nullable
        public String getIcon() {
            return icon == null || icon.isBlank() ? null : icon.trim();
        }

        public boolean isHidden() {
            return hidden != null && hidden;
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
                .documentation("What earning this is worth; unauthored means " + DEFAULT_POINTS + ". Keep the "
                        + "scale consistent across a pack, since a player's total is the sum and a milestone "
                        + "reward is measured against it.").add()
                .appendInherited(new KeyedCodec<>("CountsTowardTotal", Codec.BOOLEAN, false),
                        (o, v) -> o.countsTowardTotal = v, o -> o.countsTowardTotal,
                        (o, p) -> o.countsTowardTotal = p.countsTowardTotal)
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
