package com.ziggfreed.common.achievement.asset;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

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
import com.ziggfreed.common.progress.asset.ObjectiveLeafAsset;
import com.ziggfreed.common.progress.gate.FeatureLift;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.text.ContentTextAsset;

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
    @Nullable private Occurrence occurrence;
    @Nullable private Map<String, ObjectiveLeafAsset> criteria;
    @Nullable private String[] metaChildren;
    @Nullable private MetaSelector metaSelector;
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
                    + "unauthored block asks for nothing. A plain top-level condition on hytale:mod_installed is "
                    + "read as whether the achievement EXISTS on this server rather than as a lock: where that mod "
                    + "is missing it is out of circulation instead of showing locked. A feature switch "
                    + "(<namespace>:feature) stays a lock.")
            .add()
            .appendInherited(new KeyedCodec<>("Occurrence", Occurrence.CODEC, false),
                    (a, v) -> a.occurrence = v, a -> a.occurrence, (a, p) -> a.occurrence = p.occurrence)
            .documentation("The calendar event this achievement comes back with every year. One copy is kept "
                    + "per yearly occurrence, named '<this id>_<year>' and earned separately; a copy counts "
                    + "progress only while its own year runs, and afterwards it is a feat its earners keep and "
                    + "nobody else can earn. Unauthored means an ordinary achievement.")
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
                    + "over a set. An achievement with these needs no Criteria of its own. MetaSelector picks "
                    + "more by category, subcategory or tags.")
            .add()
            .appendInherited(new KeyedCodec<>("MetaSelector", MetaSelector.CODEC, false),
                    (a, v) -> a.metaSelector = v, a -> a.metaSelector, (a, p) -> a.metaSelector = p.metaSelector)
            .documentation("A capstone over every achievement these leaves pick, beside any MetaChildren listed "
                    + "by id: an achievement is picked when it matches every leaf written here. It never picks "
                    + "this achievement itself or any other capstone, and a yearly copy (see Occurrence) picks "
                    + "only that year's copies of the same event. With AnyYear, an ordinary capstone stands on "
                    + "every year's copies instead, counted once per calendar event; Needs says how many must be "
                    + "earned, and AtLeast is a floor under that number. A selector writing no Category, "
                    + "Subcategory or Tags picks nothing.")
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

    /** The calendar event this comes back with every year, or null for an ordinary achievement. */
    @Nullable
    public Occurrence getOccurrence() {
        return occurrence;
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

    /** The authored selector, or null when this capstone lists its children by id alone. */
    @Nullable
    public MetaSelector getMetaSelector() {
        return metaSelector;
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
     * Would {@code selector} pick this file's achievements by what they are filed under: its Category,
     * Subcategory and every one of its Tags (inherited ones included)? The occurrence and capstone
     * rules the fold adds are not asked here; this is the reading for a check over the loaded files.
     */
    public boolean matchedBy(@Nonnull MetaSelector selector) {
        Listing filed = listing;
        List<String> tags = filed == null ? List.of() : filed.tagList();
        return selector.matches(filed == null ? null : filed.getCategory(),
                filed == null ? null : filed.getSubcategory(),
                wanted -> tags.stream().anyMatch(tag -> tag.equalsIgnoreCase(wanted)));
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
        return toDefinition(null);
    }

    /**
     * Fold this asset as itself ({@code mint} null), or as one yearly copy. A copy carries its minted
     * id, so its criterion progress is filed under that year's own keys; it is in circulation only
     * while its year's occurrence is live and reads as a feat once it is not, both asked on every
     * look; the year sentinel in its text arguments and reward parameters reads as its year; and each
     * explicit child id is read through the mint's rewrite.
     */
    @Nonnull
    AchievementDefinition toDefinition(@Nullable OccurrenceMinting.Mint mint) {
        String achievementId = mint != null ? mint.id() : (id == null ? "" : id);
        boolean feat = listing != null && listing.isFeat();

        Achievement.Builder achievement = Achievement.builder(achievementId)
                .hidden(listing != null && listing.isHidden())
                .requirePrerequisites(listing != null && listing.isRequirePrerequisites())
                .points(scoring == null ? Scoring.DEFAULT_POINTS : scoring.pointsOrDefault())
                .countsTowardTotal(scoring == null || scoring.isCountsTowardTotal())
                .tags(listing == null ? List.of() : listing.tagList())
                .legacySince(listing == null ? null : listing.getLegacySince());
        // A companion mod's presence decides whether the achievement exists here, as it does a quest's;
        // a feature condition stays in the gate (a refusal the self-heal re-reads).
        FeatureLift.Result lift = FeatureLift.liftModPresence(requires);
        List<FeatureLift.Lifted> lifted = lift.lifted();
        GateSpec remaining = lifted.isEmpty() ? requires : lift.requires();
        boolean enabled = isEnabled();
        if (mint == null) {
            if (lifted.isEmpty()) {
                achievement.available(enabled);
            } else {
                BooleanSupplier live = () -> enabled && FeatureLift.allOn(lifted);
                achievement.available(live);
            }
            achievement.featOfStrength(feat);
        } else {
            BooleanSupplier year = mint.availability(enabled);
            BooleanSupplier live = lifted.isEmpty() ? year : () -> year.getAsBoolean() && FeatureLift.allOn(lifted);
            achievement.available(live)
                    .featOfStrength(mint.featOfStrength(feat))
                    .occurrence(mint.occurrence());
        }

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
                String childId = child.trim().toLowerCase(Locale.ROOT);
                children.add(mint == null ? childId : mint.child(childId));
            }
        }
        achievement.metaChildren(children);

        ContentRewardsAsset pay = rewards;
        if (pay != null) {
            achievement.autoRewards(mint == null ? pay.auto() : mint.rewards(pay.auto()));
            achievement.claimRewards(mint == null ? pay.claim() : mint.rewards(pay.claim()));
        }

        List<String> titleArgs = text == null ? List.of() : text.titleArgs();
        List<String> flavorArgs = text == null ? List.of() : text.flavorArgs();
        if (mint != null) {
            titleArgs = mint.args(titleArgs);
            flavorArgs = mint.args(flavorArgs);
        }

        return new AchievementDefinition(achievementId, achievement.build(),
                text == null ? null : text.getTitleKey(),
                text == null ? null : text.getFlavorKey(),
                text == null ? null : text.getDisplayName(),
                titleArgs, flavorArgs,
                listing == null ? null : listing.getCategory(),
                listing == null ? null : listing.getSubcategory(),
                listing == null ? 0 : listing.sortOrderOrZero(),
                listing == null ? List.of() : listing.chainList(),
                listing == null ? null : listing.getIcon(),
                remaining == null ? GateSpec.OPEN : remaining,
                criterionText, metaOrEmpty());
    }

    // ==================== Listing ====================

    /** How it is grouped, ordered, illustrated, and whether it is listed before it is earned. */
    public static final class Listing extends ContentListingAsset {

        @Nullable protected String subcategory;
        @Nullable protected Boolean feat;
        @Nullable protected String legacySince;

        public static final BuilderCodec<Listing> CODEC =
                appendLeaves(BuilderCodec.builder(Listing.class, Listing::new))
                        .appendInherited(new KeyedCodec<>("Subcategory", Codec.STRING, false),
                                (o, v) -> o.subcategory = v, o -> o.subcategory,
                                (o, p) -> o.subcategory = p.subcategory)
                        .documentation("A second level of grouping inside a Category, for a category big "
                                + "enough to need one.").add()
                        .appendInherited(new KeyedCodec<>("Feat", Codec.BOOLEAN, false),
                                (o, v) -> o.feat = v, o -> o.feat, (o, p) -> o.feat = p.feat)
                        .metadata(EditorSchema.defaultValue(false))
                        .documentation("A feat of strength: listed in its own earned-only section instead "
                                + "of the browse list, for something exceptional or retired. It changes only "
                                + "where it is listed; whether its points count stays Scoring.CountsTowardTotal's "
                                + "call. Unauthored means false.").add()
                        .appendInherited(new KeyedCodec<>("LegacySince", Codec.STRING, false),
                                (o, v) -> o.legacySince = v, o -> o.legacySince,
                                (o, p) -> o.legacySince = p.legacySince)
                        .documentation("The version this stopped being earnable in, shown beside a feat so a "
                                + "player can tell a retired achievement from one they have not reached yet.").add()
                        .build();

        public Listing() {
        }

        @Nullable
        public String getSubcategory() {
            return subcategory;
        }

        /** A feat of strength? Unauthored means false. */
        public boolean isFeat() {
            return feat != null && feat;
        }

        /** The version it was retired in, trimmed, or null while it is still earnable. */
        @Nullable
        public String getLegacySince() {
            return legacySince == null || legacySince.isBlank() ? null : legacySince.trim();
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

    // ==================== Occurrence ====================

    /**
     * The calendar event an achievement comes back with every year. {@code "Occurrence": { "Event":
     * "yourmod_festival" }} keeps one copy per year that event runs (see {@code OccurrenceMinting}).
     */
    public static final class Occurrence {

        /**
         * The sentinel a copy answers with its own year: a {@code Text.TextArgs} entry, or a reward
         * parameter whose WHOLE value it is. Anywhere else, and in an ordinary achievement, it stays as
         * written, like every unanswered sentinel.
         */
        public static final String ARG_YEAR = "@year";

        @Nullable protected String event;

        public static final BuilderCodec<Occurrence> CODEC = BuilderCodec.builder(Occurrence.class, Occurrence::new)
                .appendInherited(new KeyedCodec<>("Event", Codec.STRING, false),
                        (o, v) -> o.event = v, o -> o.event, (o, p) -> o.event = p.event)
                .documentation("The calendar event's id (its file name). Write @year in Text.TextArgs, or as a "
                        + "reward parameter's whole value, for the copy's own year.").add()
                .build();

        public Occurrence() {
        }

        /** The event's id, trimmed and lower-cased, or null when none was written. */
        @Nullable
        public String eventIdOrNull() {
            return event == null || event.isBlank() ? null : event.trim().toLowerCase(Locale.ROOT);
        }
    }

    // ==================== MetaSelector ====================

    /**
     * Which achievements a capstone stands on, picked by what they are filed under rather than by id:
     * {@code "MetaSelector": { "Category": "festival", "Tags": [ "lanterns" ] }}. With {@code AnyYear}
     * (an ordinary capstone only) every year's copy of a yearly achievement stands for its base, and
     * the picks are counted once per calendar event; {@code Needs} says how many must be earned, and
     * {@code AtLeast} is a floor under that number.
     */
    public static final class MetaSelector {

        /** What an unauthored {@code AtLeast} reads as: every capstone needs one group earned anyway. */
        public static final int DEFAULT_AT_LEAST = 1;

        @Nullable protected String category;
        @Nullable protected String subcategory;
        @Nullable protected String[] tags;
        @Nullable protected Boolean anyYear;
        @Nullable protected Integer needs;
        @Nullable protected Integer atLeast;

        public static final BuilderCodec<MetaSelector> CODEC = BuilderCodec.builder(MetaSelector.class, MetaSelector::new)
                .appendInherited(new KeyedCodec<>("Category", Codec.STRING, false),
                        (o, v) -> o.category = v, o -> o.category, (o, p) -> o.category = p.category)
                .documentation("Pick what is filed under this Listing.Category.").add()
                .appendInherited(new KeyedCodec<>("Subcategory", Codec.STRING, false),
                        (o, v) -> o.subcategory = v, o -> o.subcategory, (o, p) -> o.subcategory = p.subcategory)
                .documentation("Pick what is filed under this Listing.Subcategory.").add()
                .appendInherited(new KeyedCodec<>("Tags", Codec.STRING_ARRAY, false),
                        (o, v) -> o.tags = v, o -> o.tags, (o, p) -> o.tags = p.tags)
                .documentation("Pick what carries every one of these Listing.Tags.").add()
                .appendInherited(new KeyedCodec<>("AnyYear", Codec.BOOLEAN, false),
                        (o, v) -> o.anyYear = v, o -> o.anyYear, (o, p) -> o.anyYear = p.anyYear)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Let every year's copy of a yearly achievement (see Occurrence) stand for it, and "
                        + "count what is picked once per calendar event: two years of one event count once, and "
                        + "an event this server switched off leaves the count until it is switched back on. Only "
                        + "on an ordinary capstone. Unauthored means false: an ordinary capstone never picks a "
                        + "yearly copy.").add()
                .appendInherited(new KeyedCodec<>("Needs", Codec.INTEGER, false),
                        (o, v) -> o.needs = v, o -> o.needs, (o, p) -> o.needs = p.needs)
                .documentation("How many groups must each hold one earned pick: with AnyYear a group is one "
                        + "calendar event, otherwise each pick and each MetaChildren id is its own group. "
                        + "Unauthored means all of them, read live, so the number grows when a new season "
                        + "ships; once earned, the capstone stays earned.").add()
                .appendInherited(new KeyedCodec<>("AtLeast", Codec.INTEGER, false),
                        (o, v) -> o.atLeast = v, o -> o.atLeast, (o, p) -> o.atLeast = p.atLeast)
                .metadata(EditorSchema.defaultValue(DEFAULT_AT_LEAST))
                .documentation("A floor under how many groups must be earned: never fewer than this, whether "
                        + "Needs names the number or it is all of them. On a server running fewer events than a "
                        + "ladder was written for, it keeps an all-of-them rung from coming before a rung that "
                        + "names a number. Unauthored means " + DEFAULT_AT_LEAST + ", which every capstone needs "
                        + "anyway.").add()
                .build();

        public MetaSelector() {
        }

        /** True when no Category, Subcategory or Tags was written, which picks nothing. */
        public boolean isEmpty() {
            return blankToNull(category) == null && blankToNull(subcategory) == null && tagList().isEmpty();
        }

        /** Does a yearly copy stand for its base, counted once per calendar event? Unauthored means false. */
        public boolean isAnyYear() {
            return anyYear != null && anyYear;
        }

        /** How many groups must be earned, as authored; null means every counted group. */
        @Nullable
        public Integer getNeeds() {
            return needs;
        }

        /** The floor under how many groups must be earned, as authored; null means none was written. */
        @Nullable
        public Integer getAtLeast() {
            return atLeast;
        }

        /** Does {@code candidate} match every leaf written here, without regard to case? */
        public boolean matches(@Nonnull Achievement candidate) {
            return matches(candidate.category(), candidate.subcategory(), candidate::hasTag);
        }

        /**
         * The same test over listing facts alone: a category, a subcategory, and whether a tag is
         * carried (the caller answers it without regard to case).
         */
        public boolean matches(@Nullable String candidateCategory, @Nullable String candidateSubcategory,
                @Nonnull Predicate<String> carriesTag) {
            String wantedCategory = blankToNull(category);
            if (wantedCategory != null && !wantedCategory.equalsIgnoreCase(candidateCategory)) {
                return false;
            }
            String wantedSubcategory = blankToNull(subcategory);
            if (wantedSubcategory != null && !wantedSubcategory.equalsIgnoreCase(candidateSubcategory)) {
                return false;
            }
            for (String tag : tagList()) {
                if (!carriesTag.test(tag)) {
                    return false;
                }
            }
            return true;
        }

        @Nonnull
        private List<String> tagList() {
            List<String> out = new ArrayList<>();
            if (tags != null) {
                for (String tag : tags) {
                    if (tag != null && !tag.isBlank()) {
                        out.add(tag.trim());
                    }
                }
            }
            return out;
        }

        @Nullable
        private static String blankToNull(@Nullable String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
