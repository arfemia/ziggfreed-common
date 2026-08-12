package com.ziggfreed.common.quest.asset;

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
import com.ziggfreed.common.asset.NestedAssetId;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.ContentListingAsset;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.Quest;

/**
 * One authored quest, at {@code Server/ZiggfreedCommon/Quests/<id>.json}. The FILE NAME is the
 * quest id.
 *
 * <p>Pattern A: this codec IS the schema, and the engine decodes straight into typed fields. Every
 * leaf of every group is {@code appendInherited} and {@code Objectives} merges per objective id, so
 * a file that carries {@code "Parent": "<id>"} may retune one number and inherit everything it did
 * not mention - which is what makes "the same quest, but for iron" a five-line file.
 *
 * <pre>{@code
 * { "Parent": "gather_base",
 *   "Enabled": true,
 *   "Text":       { "TitleKey": "quest.gather_copper.title", "FlavorKey": "quest.gather_copper.flavor" },
 *   "Listing":    { "Category": "gathering", "SortOrder": 10, "Tags": ["daily"] },
 *   "Flow":       { "AutoTrack": true, "Sequential": true },
 *   "Repeat":     { "Repeatable": true, "CooldownSeconds": 86400 },
 *   "Visibility": { "Hidden": false, "RequirePrerequisites": true },
 *   "Npc":        { "ViewId": "guide", "TurnInId": "giver" },
 *   "Requires":   { "Factors": [ {"Factor": "yourmod:trade_rank", "Min": 5} ] },
 *   "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 10 },
 *                   "hand_in": { "Kind": "TURN_IN", "Target": "Copper_Ore", "Amount": 10, "Order": 2 } },
 *   "Rewards":    [ { "Kind": "yourmod:currency", "Params": { "Id": "coin", "Amount": "50" } } ],
 *   "Meta":       { "yourmod": { "Dialogue": "guide_thanks" } } }
 * }</pre>
 *
 * <p><b>Display text is keys, never sentences.</b> {@code Text.TitleKey} and {@code Text.FlavorKey}
 * are localization keys the player's own client resolves in the player's own language.
 * {@code DisplayName} exists only as a fallback while a key is still being written, and anything
 * shipping to players should carry the key.
 *
 * <p><b>To retune a quest somebody else shipped</b>, override the file by id (a same-named file in a
 * later pack), or ship your own file with {@code Parent} set to theirs and author only what you
 * change. To take one out of circulation, set {@code Enabled} to false rather than deleting it, so
 * a player who already has it can still finish.
 */
public final class QuestAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, QuestAsset>> {

    private String id;
    private AssetExtraInfo.Data data;
    /** Where the file was read from, for a finding that has to name it. Never authored. */
    @Nullable private String sourcePath;

    @Nullable private Boolean enabled;
    @Nullable private Boolean isAbstract;
    @Nullable private String owner;
    @Nullable private ContentTextAsset text;
    @Nullable private Listing listing;
    @Nullable private Flow flow;
    @Nullable private Repeat repeat;
    @Nullable private Visibility visibility;
    @Nullable private Npc npc;
    @Nullable private GateSpec requires;
    @Nullable private Map<String, QuestObjectiveAsset> objectives;
    @Nullable private RewardEntryAsset[] rewards;
    @Nullable private Map<String, JsonElement> meta;

    public static final AssetBuilderCodec<String, QuestAsset> CODEC = AssetBuilderCodec.builder(
                    QuestAsset.class,
                    QuestAsset::new,
                    Codec.STRING,
                    // The engine's asset key is the verbatim filename while every consumer addresses
                    // a quest lower-cased; canonicalizing at the one decode authority keeps getId()
                    // the same string everywhere.
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
            .documentation("Whether the quest is in circulation; unauthored means true. Setting false stops it "
                    + "being offered while leaving a player who already holds it able to finish.")
            .add()
            // The ONE field that deliberately does NOT inherit: a child of a skeleton is a real
            // quest, so inheriting this would make every child of a base invisible too.
            .append(new KeyedCodec<>("Abstract", Codec.BOOLEAN, false),
                    (a, v) -> a.isAbstract = v, a -> a.isAbstract)
            .documentation("Mark a file that exists only to be inherited from. It stays available as a Parent "
                    + "target and is never offered to anybody, so a shared skeleton needs no objectives of its "
                    + "own. It never carries down to a child: inheriting from a skeleton makes a real quest.")
            .add()
            .appendInherited(new KeyedCodec<>("Owner", Codec.STRING, false),
                    (a, v) -> a.owner = v, a -> a.owner, (a, p) -> a.owner = p.owner)
            .documentation("Which game or mod this quest belongs to, so several can author into one store and "
                    + "each runs only its own. Unauthored means unowned, which every reader picks up.")
            .add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the player reads, as localization keys.")
            .add()
            .appendInherited(new KeyedCodec<>("Listing", Listing.CODEC, false),
                    (a, v) -> a.listing = v, a -> a.listing, (a, p) -> a.listing = p.listing)
            .documentation("How the quest is grouped and ordered wherever quests are listed.")
            .add()
            .appendInherited(new KeyedCodec<>("Flow", Flow.CODEC, false),
                    (a, v) -> a.flow = v, a -> a.flow, (a, p) -> a.flow = p.flow)
            .documentation("How much the player has to do by hand: take it, track it, collect the reward, and "
                    + "whether the steps run in order.")
            .add()
            .appendInherited(new KeyedCodec<>("Repeat", Repeat.CODEC, false),
                    (a, v) -> a.repeat = v, a -> a.repeat, (a, p) -> a.repeat = p.repeat)
            .documentation("Whether the quest comes back around, how long the wait is, and what else it resets.")
            .add()
            .appendInherited(new KeyedCodec<>("Visibility", Visibility.CODEC, false),
                    (a, v) -> a.visibility = v, a -> a.visibility, (a, p) -> a.visibility = p.visibility)
            .documentation("Who may SEE the quest before taking it. A quest already in progress ignores both "
                    + "knobs, because a player must always see what they are in the middle of.")
            .add()
            .appendInherited(new KeyedCodec<>("Npc", Npc.CODEC, false),
                    (a, v) -> a.npc = v, a -> a.npc, (a, p) -> a.npc = p.npc)
            .documentation("Who offers the quest and where it is handed in.")
            .add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation("What a player must already have or have done. An unauthored block asks for nothing; "
                    + "a requirement nothing can answer keeps the quest locked.")
            .add()
            .appendInherited(new KeyedCodec<>("Objectives",
                            new InheritMapCodec<>(QuestObjectiveAsset.CODEC), false),
                    (a, v) -> a.objectives = v, a -> a.objectives, (a, p) -> a.objectives = p.objectives)
            .documentation("The steps, keyed by objective id. The key is also what progress is stored under, so "
                    + "renaming one starts that step over. A child quest may retune one step by id and keeps every "
                    + "step it did not mention.")
            .add()
            .appendInherited(new KeyedCodec<>("Rewards",
                            new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new), false),
                    (a, v) -> a.rewards = v, a -> a.rewards, (a, p) -> a.rewards = p.rewards)
            .documentation("What the player gets. This is ONE leaf: author it and an inherited list is replaced "
                    + "whole, omit it and the inherited list carries over (an empty array pays out nothing).")
            .add()
            .appendInherited(new KeyedCodec<>(ContentMeta.KEY, ContentMeta.CODEC, false),
                    (a, v) -> a.meta = v, a -> a.meta, (a, p) -> a.meta = p.meta)
            .documentation(ContentMeta.DOCUMENTATION)
            .add()
            // The engine names a quest after its FILE and ignores the folders above it. This folds
            // every _-marked folder back into the id, so an author can group quests into folders AND
            // keep the ids apart. See NestedAssetId.
            .afterDecode(QuestAsset::applyNestedId)
            .build();

    public QuestAsset() {
    }

    /** The store's content path, the root {@link NestedAssetId} measures folder depth from. */
    static final String TYPE_ROOT = "ZiggfreedCommon/Quests";

    /**
     * Fold the {@code _}-marked folders above this file into its id, and remember where it was read
     * from. Runs once per decode, off the path the asset store hands every codec.
     *
     * <p>A GENERATED quest is decoded from a string with no file behind it, so there is no path,
     * no prefix, and the generator's own id stands - which is what a generated id already means.
     */
    private static void applyNestedId(@Nonnull QuestAsset asset, @Nullable ExtraInfo extraInfo) {
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

    /** Where this quest's file was read from, or null for a generated one. */
    @Nullable
    public String getSourcePath() {
        return sourcePath;
    }

    /** In circulation? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** A skeleton that exists only to be inherited from, never offered to anybody. */
    public boolean isAbstract() {
        return isAbstract != null && isAbstract;
    }

    /** Which game or mod this quest belongs to, lower-cased; null when unowned. */
    @Nullable
    public String getOwner() {
        return owner == null || owner.isBlank() ? null : owner.trim().toLowerCase(Locale.ROOT);
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
    public Flow getFlow() {
        return flow;
    }

    @Nullable
    public Repeat getRepeat() {
        return repeat;
    }

    @Nullable
    public Visibility getVisibility() {
        return visibility;
    }

    @Nullable
    public Npc getNpc() {
        return npc;
    }

    /** The authored requirements, or null when the quest asks for nothing. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /** The authored steps in authored order, keyed by objective id. */
    @Nonnull
    public Map<String, QuestObjectiveAsset> objectivesOrEmpty() {
        return objectives == null ? Map.of() : objectives;
    }

    /** The authored rewards, in authored order. */
    @Nonnull
    public RewardEntryAsset[] rewardsOrEmpty() {
        return rewards == null ? new RewardEntryAsset[0] : rewards;
    }

    /** The per-namespace extra facts, exactly as authored; empty when the file carried none. */
    @Nonnull
    public Map<String, JsonElement> metaOrEmpty() {
        return ContentMeta.orEmpty(meta);
    }

    /**
     * Fold this asset into the runtime {@link QuestDefinition}: the engine's {@link Quest} plus the
     * presentation and gate data the engine deliberately does not model.
     *
     * @param generatedBy the generator that produced this quest, or null when it was authored by hand
     */
    @Nonnull
    public QuestDefinition toDefinition(@Nullable String generatedBy) {
        String questId = id == null ? "" : id;
        String giverId = npc == null ? null : npc.getViewId();
        String questTurnIn = npc == null ? null : npc.effectiveTurnInId(giverId);

        Quest.Builder quest = Quest.builder(questId)
                .available(isEnabled())
                .sequential(flow != null && flow.isSequential())
                .autoAccept(flow != null && flow.isAutoAccept())
                .autoTrack(flow != null && flow.isAutoTrack())
                .autoClaim(flow == null || flow.isAutoClaim())
                .repeat(repeat == null ? Quest.Repeat.ONCE : repeat.toRepeat())
                .visibility(visibility == null ? Quest.Visibility.OPEN : visibility.toVisibility())
                .tags(listing == null ? List.of() : listing.tagList());

        Map<String, String> objectiveText = new LinkedHashMap<>();
        for (Map.Entry<String, QuestObjectiveAsset> entry : objectivesOrEmpty().entrySet()) {
            QuestObjectiveAsset authored = entry.getValue();
            if (authored == null) {
                continue;
            }
            quest.objective(authored.toDef(entry.getKey(), giverId, questTurnIn));
            if (authored.getTextKey() != null && !authored.getTextKey().isBlank()) {
                objectiveText.put(entry.getKey(), authored.getTextKey());
            }
        }

        for (RewardEntryAsset reward : rewardsOrEmpty()) {
            RewardSpec spec = reward == null ? null : reward.toSpec();
            if (spec != null) {
                quest.reward(spec);
            }
        }

        return new QuestDefinition(questId, quest.build(),
                text == null ? null : text.getTitleKey(),
                text == null ? null : text.getFlavorKey(),
                text == null ? null : text.getDisplayName(),
                text == null ? List.of() : text.titleArgs(),
                text == null ? List.of() : text.flavorArgs(),
                listing == null ? null : listing.getCategory(),
                listing == null ? 0 : listing.sortOrderOrZero(),
                listing == null ? List.of() : listing.chainList(),
                giverId, questTurnIn,
                requires == null ? GateSpec.OPEN : requires,
                objectiveText,
                repeat == null ? List.of() : repeat.resetsOnCompleteList(),
                getOwner(), generatedBy, metaOrEmpty());
    }

    // ==================== Listing ====================

    /** How the quest is grouped and ordered wherever quests are listed. */
    public static final class Listing extends ContentListingAsset {

        public static final BuilderCodec<Listing> CODEC =
                appendLeaves(BuilderCodec.builder(Listing.class, Listing::new)).build();

        public Listing() {
        }

        @Nonnull
        public static Listing of(@Nullable String category, @Nullable Integer sortOrder, @Nullable String[] tags) {
            Listing l = new Listing();
            l.category = category;
            l.sortOrder = sortOrder;
            l.tags = tags == null ? null : tags.clone();
            return l;
        }
    }

    // ==================== Flow ====================

    /** How much of the quest the player drives by hand, and whether its steps run in order. */
    public static final class Flow {

        @Nullable protected Boolean autoAccept;
        @Nullable protected Boolean autoTrack;
        @Nullable protected Boolean autoClaim;
        @Nullable protected Boolean sequential;

        public static final BuilderCodec<Flow> CODEC = BuilderCodec.builder(Flow.class, Flow::new)
                .appendInherited(new KeyedCodec<>("AutoAccept", Codec.BOOLEAN, false),
                        (o, v) -> o.autoAccept = v, o -> o.autoAccept, (o, p) -> o.autoAccept = p.autoAccept)
                .documentation("Start the quest as soon as the player is eligible, with no action from them. "
                        + "Unauthored means false.").add()
                .appendInherited(new KeyedCodec<>("AutoTrack", Codec.BOOLEAN, false),
                        (o, v) -> o.autoTrack = v, o -> o.autoTrack, (o, p) -> o.autoTrack = p.autoTrack)
                .documentation("Pin it to the tracker on accept if there is room. It never displaces a pin the "
                        + "player chose. Unauthored means false.").add()
                .appendInherited(new KeyedCodec<>("AutoClaim", Codec.BOOLEAN, false),
                        (o, v) -> o.autoClaim = v, o -> o.autoClaim, (o, p) -> o.autoClaim = p.autoClaim)
                .documentation("Pay out the moment the steps are done; unauthored means true. Set false for a "
                        + "quest whose reward is collected somewhere specific: it waits, finished, until then.").add()
                .appendInherited(new KeyedCodec<>("Sequential", Codec.BOOLEAN, false),
                        (o, v) -> o.sequential = v, o -> o.sequential, (o, p) -> o.sequential = p.sequential)
                .documentation("Run the steps strictly one after another in authored order. Ignored the moment "
                        + "any objective authors its own Order, which is the finer-grained way to say the same.").add()
                .build();

        public Flow() {
        }

        @Nonnull
        public static Flow of(@Nullable Boolean autoAccept, @Nullable Boolean autoTrack,
                @Nullable Boolean autoClaim, @Nullable Boolean sequential) {
            Flow f = new Flow();
            f.autoAccept = autoAccept;
            f.autoTrack = autoTrack;
            f.autoClaim = autoClaim;
            f.sequential = sequential;
            return f;
        }

        public boolean isAutoAccept() {
            return autoAccept != null && autoAccept;
        }

        public boolean isAutoTrack() {
            return autoTrack != null && autoTrack;
        }

        /** Unauthored means true, matching the engine's own default. */
        public boolean isAutoClaim() {
            return autoClaim == null || autoClaim;
        }

        public boolean isSequential() {
            return sequential != null && sequential;
        }
    }

    // ==================== Repeat ====================

    /** Whether the quest comes back around, how long the wait is, and what else it resets. */
    public static final class Repeat {

        @Nullable protected Boolean repeatable;
        @Nullable protected Long cooldownSeconds;
        @Nullable protected Boolean stampOnPark;
        @Nullable protected String[] resetsOnComplete;

        public static final BuilderCodec<Repeat> CODEC = BuilderCodec.builder(Repeat.class, Repeat::new)
                .appendInherited(new KeyedCodec<>("Repeatable", Codec.BOOLEAN, false),
                        (o, v) -> o.repeatable = v, o -> o.repeatable, (o, p) -> o.repeatable = p.repeatable)
                .documentation("Can it be taken again once finished? Unauthored means false, a one-shot.").add()
                .appendInherited(new KeyedCodec<>("CooldownSeconds", Codec.LONG, false),
                        (o, v) -> o.cooldownSeconds = v, o -> o.cooldownSeconds,
                        (o, p) -> o.cooldownSeconds = p.cooldownSeconds)
                .documentation("How long before a repeatable quest can be taken again; 0 or unauthored means "
                        + "straight away. 86400 is a day, 604800 a week.").add()
                .appendInherited(new KeyedCodec<>("StampOnPark", Codec.BOOLEAN, false),
                        (o, v) -> o.stampOnPark = v, o -> o.stampOnPark, (o, p) -> o.stampOnPark = p.stampOnPark)
                .documentation("Start the wait when the steps are DONE rather than when the reward is taken. Set "
                        + "it for a quest belonging to a rotating offer, so collecting late does not burn a slot in "
                        + "the next period. Unauthored means false.").add()
                .appendInherited(new KeyedCodec<>("ResetsOnComplete", Codec.STRING_ARRAY, false),
                        (o, v) -> o.resetsOnComplete = v, o -> o.resetsOnComplete,
                        (o, p) -> o.resetsOnComplete = p.resetsOnComplete)
                .documentation("Quest ids whose progress is wiped when this one finishes, so a chain can come "
                        + "round again. Handy for a weekly that re-arms its dailies.").add()
                .build();

        public Repeat() {
        }

        @Nonnull
        public static Repeat of(@Nullable Boolean repeatable, @Nullable Long cooldownSeconds,
                @Nullable Boolean stampOnPark, @Nullable String[] resetsOnComplete) {
            Repeat r = new Repeat();
            r.repeatable = repeatable;
            r.cooldownSeconds = cooldownSeconds;
            r.stampOnPark = stampOnPark;
            r.resetsOnComplete = resetsOnComplete == null ? null : resetsOnComplete.clone();
            return r;
        }

        public boolean isRepeatable() {
            return repeatable != null && repeatable;
        }

        public long cooldownMs() {
            return cooldownSeconds == null ? 0L : Math.max(0L, cooldownSeconds) * 1000L;
        }

        public boolean isStampOnPark() {
            return stampOnPark != null && stampOnPark;
        }

        @Nullable
        public String[] getResetsOnComplete() {
            return resetsOnComplete == null ? null : resetsOnComplete.clone();
        }

        @Nonnull
        public List<String> resetsOnCompleteList() {
            if (resetsOnComplete == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>(resetsOnComplete.length);
            for (String questId : resetsOnComplete) {
                if (questId != null && !questId.isBlank()) {
                    out.add(questId.trim().toLowerCase(Locale.ROOT));
                }
            }
            return out;
        }

        /** The engine's repeat rule for these knobs. */
        @Nonnull
        public Quest.Repeat toRepeat() {
            if (!isRepeatable()) {
                return Quest.Repeat.ONCE;
            }
            return isStampOnPark()
                    ? Quest.Repeat.everyStampedOnPark(cooldownMs())
                    : Quest.Repeat.every(cooldownMs());
        }
    }

    // ==================== Visibility ====================

    /** Who may SEE the quest before taking it. */
    public static final class Visibility {

        @Nullable protected Boolean hidden;
        @Nullable protected Boolean requirePrerequisites;

        public static final BuilderCodec<Visibility> CODEC =
                BuilderCodec.builder(Visibility.class, Visibility::new)
                        .appendInherited(new KeyedCodec<>("Hidden", Codec.BOOLEAN, false),
                                (o, v) -> o.hidden = v, o -> o.hidden, (o, p) -> o.hidden = p.hidden)
                        .documentation("Keep it off open listings entirely, for a quest handed out some other way "
                                + "(a chain step, an event). Unauthored means listed.").add()
                        .appendInherited(new KeyedCodec<>("RequirePrerequisites", Codec.BOOLEAN, false),
                                (o, v) -> o.requirePrerequisites = v, o -> o.requirePrerequisites,
                                (o, p) -> o.requirePrerequisites = p.requirePrerequisites)
                        .documentation("Hide it until its Requires block passes, instead of showing it locked. "
                                + "Unauthored means shown locked, which is usually kinder: a player can see what "
                                + "to work towards.").add()
                        .build();

        public Visibility() {
        }

        @Nonnull
        public static Visibility of(@Nullable Boolean hidden, @Nullable Boolean requirePrerequisites) {
            Visibility v = new Visibility();
            v.hidden = hidden;
            v.requirePrerequisites = requirePrerequisites;
            return v;
        }

        public boolean isHidden() {
            return hidden != null && hidden;
        }

        public boolean isRequirePrerequisites() {
            return requirePrerequisites != null && requirePrerequisites;
        }

        @Nonnull
        public Quest.Visibility toVisibility() {
            return new Quest.Visibility(isHidden(), isRequirePrerequisites());
        }
    }

    // ==================== Npc ====================

    /**
     * Who offers the quest and where it is handed in. Both are opaque ids this library only ever
     * compares: what an id points at is the reading mod's business.
     */
    public static final class Npc {

        @Nullable protected String viewId;
        @Nullable protected String turnInId;

        public static final BuilderCodec<Npc> CODEC = BuilderCodec.builder(Npc.class, Npc::new)
                .appendInherited(new KeyedCodec<>("ViewId", Codec.STRING, false),
                        (o, v) -> o.viewId = v, o -> o.viewId, (o, p) -> o.viewId = p.viewId)
                .documentation("The id of whoever offers this quest, so a listing can show the right quests at "
                        + "the right character.").add()
                .appendInherited(new KeyedCodec<>("TurnInId", Codec.STRING, false),
                        (o, v) -> o.turnInId = v, o -> o.turnInId, (o, p) -> o.turnInId = p.turnInId)
                .documentation("Where the quest is handed in. The literal 'giver' means ViewId, so moving a quest "
                        + "giver needs one edit rather than two. Unauthored means any hand-in surface will do, and "
                        + "an objective may still name its own.").add()
                .build();

        public Npc() {
        }

        @Nonnull
        public static Npc of(@Nullable String viewId, @Nullable String turnInId) {
            Npc n = new Npc();
            n.viewId = viewId;
            n.turnInId = turnInId;
            return n;
        }

        @Nullable
        public String getViewId() {
            return viewId == null || viewId.isBlank() ? null : viewId.trim();
        }

        /** The authored hand-in id, sentinel unresolved. */
        @Nullable
        public String getTurnInId() {
            return turnInId;
        }

        /** True when the hand-in id is the "wherever this quest came from" sentinel. */
        public boolean turnsInAtGiver() {
            return turnInId != null && turnInId.trim().equalsIgnoreCase(QuestObjectiveAsset.GIVER_SENTINEL);
        }

        /** The hand-in id with the {@code giver} sentinel resolved against {@code giverId}. */
        @Nullable
        public String effectiveTurnInId(@Nullable String giverId) {
            if (turnInId == null || turnInId.isBlank()) {
                return null;
            }
            return turnsInAtGiver() ? giverId : turnInId.trim();
        }
    }
}
