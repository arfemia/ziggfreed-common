package com.ziggfreed.common.quest.asset;

import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonValue;

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
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.NestedAssetId;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.ContentListingAsset;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestTurnInSite;
import com.ziggfreed.common.time.DurationGroup;

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
 *   "Repeat":     { "Cooldown": { "Hours": 24 } },
 *   "Visibility": { "Hidden": false, "RequirePrerequisites": true },
 *   "Npc":        { "ViewId": "guide", "TurnInId": "giver" },
 *   "TurnInAt":   true,
 *   "CompletionDialogue": "guide_thanks",
 *   "Requires":   { "Factors": [ {"Factor": "yourmod:trade_rank", "Min": 5} ] },
 *   "Objectives": { "collect": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "Amount": 10 },
 *                   "hand_in": { "Kind": "TURN_IN", "Target": "Copper_Ore", "Amount": 10, "Order": 2 } },
 *   "Rewards":    [ { "Kind": "yourmod:currency", "Params": { "Id": "coin", "Amount": "50" } } ],
 *   "Meta":       { "yourmod": { "Faction": "wardens" } } }
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

    /**
     * The {@code TurnInAt} value meaning "back to wherever this player took it from", whatever that
     * turned out to be. It starts with {@code @} so it can never collide with a character id.
     */
    public static final String ACCEPT_SITE_SENTINEL = "@accept";

    private String id;
    private AssetExtraInfo.Data data;
    /** Where the file was read from, for a finding that has to name it. Never authored. */
    @Nullable private String sourcePath;

    @Nullable private Boolean enabled;
    @Nullable private Boolean isAbstract;
    @Nullable private ContentTextAsset text;
    @Nullable private Listing listing;
    @Nullable private Flow flow;
    @Nullable private Repeat repeat;
    @Nullable private Visibility visibility;
    @Nullable private Npc npc;
    @Nullable private String turnInAt;
    @Nullable private String completionDialogue;
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
            .appendInherited(new KeyedCodec<>("TurnInAt", BooleanOrStringCodec.INSTANCE, false),
                    (a, v) -> a.turnInAt = v, a -> a.turnInAt, (a, p) -> a.turnInAt = p.turnInAt)
            .documentation("Where the finished quest may be collected. Leave it out and it may be collected "
                    + "anywhere the game offers - a quest log, a book, a menu - which is what most quests want. "
                    + "Write true (or the word 'giver') to send the player back to whoever offers it, an npc id "
                    + "to send them to that character instead (who may be somebody they have never met, and "
                    + "need not be the giver), or '" + ACCEPT_SITE_SENTINEL + "' to send them back to whatever "
                    + "place they took it from, which is the one to reach for when the same quest is handed out "
                    + "at many identical fixtures. Author an empty string or false to collect anywhere again "
                    + "after a Parent set one. This is about collecting the REWARD; a single delivery step names "
                    + "its own place with Objectives.TurnInNpcId.")
            .add()
            .appendInherited(new KeyedCodec<>("CompletionDialogue", Codec.STRING, false),
                    (a, v) -> a.completionDialogue = v, a -> a.completionDialogue,
                    (a, p) -> a.completionDialogue = p.completionDialogue)
            .documentation("The conversation that follows this quest settling at a character, by dialogue id. "
                    + "Leave it out and finishing simply pays out. It only ever plays where there is somebody "
                    + "to play it: a quest log or a book has nobody in front of the player, so the beat is "
                    + "skipped there. Author an empty string to drop a conversation inherited from a Parent.")
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

    /**
     * The authored {@code TurnInAt} exactly as written, sentinels unresolved, or null when the quest
     * may be collected anywhere. {@code true} has already become the {@code giver} sentinel by the
     * time it lands here, so there is one spelling to read rather than two.
     */
    @Nullable
    public String getTurnInAt() {
        return turnInAt;
    }

    /**
     * The authored {@code TurnInAt} as the site the engine enforces, with both sentinels resolved:
     * {@code giver} against {@code giverId}, {@code @accept} into the accepted-at form. Null when
     * the quest names no place, so anywhere will do.
     */
    @Nullable
    public QuestTurnInSite turnInSite(@Nullable String giverId) {
        if (turnInAt == null) {
            return null;
        }
        String authored = turnInAt.trim();
        if (authored.isEmpty()) {
            return null;
        }
        if (ACCEPT_SITE_SENTINEL.equalsIgnoreCase(authored)) {
            return QuestTurnInSite.ACCEPT_SITE;
        }
        if (QuestObjectiveAsset.GIVER_SENTINEL.equalsIgnoreCase(authored)) {
            // A quest nobody gives out cannot be returned to its giver: the site stays unsatisfiable
            // rather than falling back to anywhere, so the audit is what tells the author.
            return QuestTurnInSite.character(giverId);
        }
        return QuestTurnInSite.character(authored);
    }

    /** The conversation that follows this quest settling, lower-cased, or null when it names none. */
    @Nullable
    public String getCompletionDialogue() {
        return completionDialogue == null || completionDialogue.isBlank()
                ? null : completionDialogue.trim().toLowerCase(Locale.ROOT);
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
                .repeat(repeat == null ? null : repeat.toRepeat())
                .visibility(visibility == null ? Quest.Visibility.OPEN : visibility.toVisibility())
                .turnInAt(turnInSite(giverId))
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
                giverId, questTurnIn, getCompletionDialogue(),
                requires == null ? GateSpec.OPEN : requires,
                objectiveText,
                repeat == null ? List.of() : repeat.resetsOnCompleteList(),
                generatedBy, metaOrEmpty());
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

    /**
     * Whether the quest comes back around, what holds it back, and what else it resets.
     *
     * <p><b>Authoring this block AT ALL is what makes a quest repeatable</b>, so the smallest
     * repeatable quest is {@code "Repeat": {}} - nothing holds it back, and whatever offers it
     * decides when it comes round. Everything inside is an independent constraint and they all have
     * to pass: a rolling {@code Cooldown} wait, a {@code Reset} calendar allowance, and a lifetime
     * {@code MaxCompletions} cap.
     */
    public static final class Repeat {

        /** {@code CooldownFrom} authored as the instant the reward is taken; the default. */
        public static final String FROM_CLAIM = "Claim";

        /** {@code CooldownFrom} authored as the instant the steps were finished. */
        public static final String FROM_COMPLETE = "Complete";

        @Nullable protected DurationGroup cooldown;
        @Nullable protected String cooldownFrom;
        @Nullable protected Reset reset;
        @Nullable protected Integer maxCompletions;
        @Nullable protected String[] resetsOnComplete;

        public static final BuilderCodec<Repeat> CODEC = BuilderCodec.builder(Repeat.class, Repeat::new)
                .appendInherited(new KeyedCodec<>("Cooldown", DurationGroup.CODEC, false),
                        (o, v) -> o.cooldown = v, o -> o.cooldown, (o, p) -> o.cooldown = p.cooldown)
                .documentation("A rolling wait before the quest can be taken again, authored in whole units "
                        + "that add up: {\"Hours\": 24} for a day, {\"Days\": 7} for a week. Unauthored means no "
                        + "rolling wait at all.").add()
                .appendInherited(new KeyedCodec<>("CooldownFrom", Codec.STRING, false),
                        (o, v) -> o.cooldownFrom = v, o -> o.cooldownFrom,
                        (o, p) -> o.cooldownFrom = p.cooldownFrom)
                .documentation("Which instant the rolling wait counts from: Claim (the reward being taken, the "
                        + "default) or Complete (the steps being finished). Choose Complete for a quest belonging "
                        + "to a rotating offer, so collecting late does not burn a slot in the next period.").add()
                .appendInherited(new KeyedCodec<>("Reset", Reset.CODEC, false),
                        (o, v) -> o.reset = v, o -> o.reset, (o, p) -> o.reset = p.reset)
                .documentation("A calendar allowance: how many times the quest may be finished inside one day "
                        + "or one week, counted from a fixed boundary rather than from the player's last go. "
                        + "Unauthored means no calendar limit.").add()
                .appendInherited(new KeyedCodec<>("MaxCompletions", Codec.INTEGER, false),
                        (o, v) -> o.maxCompletions = v, o -> o.maxCompletions,
                        (o, p) -> o.maxCompletions = p.maxCompletions)
                .documentation("A lifetime cap: how many times one player may ever finish it. 0 or unauthored "
                        + "means uncapped. A player who has spent the cap sees the quest as finished for good.").add()
                .appendInherited(new KeyedCodec<>("ResetsOnComplete", Codec.STRING_ARRAY, false),
                        (o, v) -> o.resetsOnComplete = v, o -> o.resetsOnComplete,
                        (o, p) -> o.resetsOnComplete = p.resetsOnComplete)
                .documentation("Quest ids whose progress is wiped when this one finishes, so a chain can come "
                        + "round again. Handy for a weekly that re-arms its dailies.").add()
                .build();

        public Repeat() {
        }

        @Nonnull
        public static Repeat of(@Nullable DurationGroup cooldown, @Nullable String cooldownFrom,
                @Nullable Reset reset, @Nullable Integer maxCompletions,
                @Nullable String[] resetsOnComplete) {
            Repeat r = new Repeat();
            r.cooldown = cooldown;
            r.cooldownFrom = cooldownFrom;
            r.reset = reset;
            r.maxCompletions = maxCompletions;
            r.resetsOnComplete = resetsOnComplete == null ? null : resetsOnComplete.clone();
            return r;
        }

        /** The authored rolling wait, or null when the quest authors none. */
        @Nullable
        public DurationGroup getCooldown() {
            return cooldown;
        }

        /** The rolling wait in milliseconds; 0 when unauthored. */
        public long cooldownMs() {
            return cooldown == null ? 0L : cooldown.totalMs();
        }

        /** The authored anchor exactly as written, unparsed; null when unauthored. */
        @Nullable
        public String getCooldownFrom() {
            return cooldownFrom;
        }

        /** The parsed anchor, or null when a value was authored that is not one of the two. */
        @Nullable
        public Quest.Repeat.CooldownFrom parsedCooldownFrom() {
            if (cooldownFrom == null || cooldownFrom.isBlank()) {
                return Quest.Repeat.CooldownFrom.CLAIM;
            }
            String value = cooldownFrom.trim();
            if (FROM_CLAIM.equalsIgnoreCase(value)) {
                return Quest.Repeat.CooldownFrom.CLAIM;
            }
            return FROM_COMPLETE.equalsIgnoreCase(value) ? Quest.Repeat.CooldownFrom.COMPLETE : null;
        }

        /** The anchor, falling back to the documented default when the authored value is unknown. */
        @Nonnull
        public Quest.Repeat.CooldownFrom effectiveCooldownFrom() {
            Quest.Repeat.CooldownFrom parsed = parsedCooldownFrom();
            return parsed == null ? Quest.Repeat.CooldownFrom.CLAIM : parsed;
        }

        /** The authored calendar allowance, or null when the quest authors none. */
        @Nullable
        public Reset getReset() {
            return reset;
        }

        /** The lifetime cap; 0 (uncapped) when unauthored or authored negative. */
        public int maxCompletions() {
            return maxCompletions == null ? 0 : Math.max(0, maxCompletions.intValue());
        }

        /** The authored lifetime cap exactly as written, for a validator that must see a bad one. */
        @Nullable
        public Integer getMaxCompletions() {
            return maxCompletions;
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

        /**
         * The engine's repeat rule for these knobs. Never null: the block existing at all IS the
         * repeatable flag, so an empty block folds to the externally governed rule.
         */
        @Nonnull
        public Quest.Repeat toRepeat() {
            return new Quest.Repeat(cooldownMs(), effectiveCooldownFrom(),
                    reset == null ? null : reset.toReset(), maxCompletions());
        }

        // ==================== Reset ====================

        /**
         * The calendar allowance: how many times the quest may be finished inside one fixed window.
         * Counted from a boundary on the server's own clock (UTC), not from the player's last go, so
         * everybody's daily rolls over at the same instant.
         */
        public static final class Reset {

            /** {@code Period} authored as a day; the default. */
            public static final String PERIOD_DAILY = "Daily";

            /** {@code Period} authored as a week. */
            public static final String PERIOD_WEEKLY = "Weekly";

            @Nullable protected String period;
            @Nullable protected Integer atMinutes;
            @Nullable protected String weekday;
            @Nullable protected Integer times;

            public static final BuilderCodec<Reset> CODEC = BuilderCodec.builder(Reset.class, Reset::new)
                    .appendInherited(new KeyedCodec<>("Period", Codec.STRING, false),
                            (o, v) -> o.period = v, o -> o.period, (o, p) -> o.period = p.period)
                    .documentation("Daily or Weekly. Unauthored means Daily.").add()
                    .appendInherited(new KeyedCodec<>("AtMinutes", Codec.INTEGER, false),
                            (o, v) -> o.atMinutes = v, o -> o.atMinutes, (o, p) -> o.atMinutes = p.atMinutes)
                    .documentation("How many minutes past the boundary the window rolls over, on the server "
                            + "clock in UTC. Unauthored means midnight UTC; 240 moves it to 04:00, which is how "
                            + "a server whose players are all in one part of the world stops a daily flipping "
                            + "over in the middle of their evening.").add()
                    .appendInherited(new KeyedCodec<>("Weekday", Codec.STRING, false),
                            (o, v) -> o.weekday = v, o -> o.weekday, (o, p) -> o.weekday = p.weekday)
                    .documentation("Which day a Weekly window starts on (Monday, Tuesday, ...). Unauthored "
                            + "means Monday. It does nothing on a Daily window.").add()
                    .appendInherited(new KeyedCodec<>("Times", Codec.INTEGER, false),
                            (o, v) -> o.times = v, o -> o.times, (o, p) -> o.times = p.times)
                    .documentation("How many completions fit inside one window. Unauthored means 1.").add()
                    .build();

            public Reset() {
            }

            @Nonnull
            public static Reset of(@Nullable String period, @Nullable Integer atMinutes,
                    @Nullable String weekday, @Nullable Integer times) {
                Reset r = new Reset();
                r.period = period;
                r.atMinutes = atMinutes;
                r.weekday = weekday;
                r.times = times;
                return r;
            }

            /** The authored period exactly as written, unparsed; null when unauthored. */
            @Nullable
            public String getPeriod() {
                return period;
            }

            /** The parsed window length, or null when a value was authored that is neither. */
            @Nullable
            public Quest.Repeat.Reset.Period parsedPeriod() {
                if (period == null || period.isBlank()) {
                    return Quest.Repeat.Reset.Period.DAILY;
                }
                String value = period.trim();
                if (PERIOD_DAILY.equalsIgnoreCase(value)) {
                    return Quest.Repeat.Reset.Period.DAILY;
                }
                return PERIOD_WEEKLY.equalsIgnoreCase(value)
                        ? Quest.Repeat.Reset.Period.WEEKLY : null;
            }

            /** The authored weekday exactly as written, unparsed; null when unauthored. */
            @Nullable
            public String getWeekday() {
                return weekday;
            }

            /** The parsed weekday, or null when a value was authored that is not a day name. */
            @Nullable
            public DayOfWeek parsedWeekday() {
                if (weekday == null || weekday.isBlank()) {
                    return DayOfWeek.MONDAY;
                }
                try {
                    return DayOfWeek.valueOf(weekday.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    return null;
                }
            }

            /** The authored rollover offset exactly as written, for a validator. */
            @Nullable
            public Integer getAtMinutes() {
                return atMinutes;
            }

            /** The authored allowance exactly as written, for a validator. */
            @Nullable
            public Integer getTimes() {
                return times;
            }

            /**
             * The engine's calendar rule for these knobs, each unparseable value falling back to its
             * documented default. A validator is what tells the author about one; falling back
             * silently here keeps a typo from taking a whole quest out of circulation.
             */
            @Nonnull
            public Quest.Repeat.Reset toReset() {
                Quest.Repeat.Reset.Period parsedPeriod = parsedPeriod();
                DayOfWeek parsedWeekday = parsedWeekday();
                return new Quest.Repeat.Reset(
                        parsedPeriod == null ? Quest.Repeat.Reset.Period.DAILY : parsedPeriod,
                        atMinutes == null ? 0 : atMinutes.intValue(),
                        parsedWeekday == null ? DayOfWeek.MONDAY : parsedWeekday,
                        times == null ? 1 : times.intValue());
            }
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

    // ==================== The dual-form leaf ====================

    /**
     * A leaf an author may write as a plain {@code true} or as a word: {@code true} is shorthand for
     * the {@code giver} sentinel, {@code false} reads as nothing authored, and any string is taken as
     * written. It exists so the commonest answer ("back to whoever gave it to me") is one word rather
     * than a spelling to remember, without turning the field into an object.
     *
     * <p>It always ENCODES as the string, so a file round-trips to one canonical spelling and the
     * in-game asset editor has a single shape to offer.
     */
    private static final class BooleanOrStringCodec implements Codec<String> {

        static final BooleanOrStringCodec INSTANCE = new BooleanOrStringCodec();

        @Override
        @Nullable
        public String decode(BsonValue value, ExtraInfo extraInfo) {
            if (value.isBoolean()) {
                return value.asBoolean().getValue() ? QuestObjectiveAsset.GIVER_SENTINEL : null;
            }
            return Codec.STRING.decode(value, extraInfo);
        }

        @Override
        @Nonnull
        public BsonValue encode(String value, ExtraInfo extraInfo) {
            return Codec.STRING.encode(value, extraInfo);
        }

        @Override
        @Nullable
        public String decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            int next = reader.peek();
            if (next == 't' || next == 'T' || next == 'f' || next == 'F') {
                return reader.readBooleanValue() ? QuestObjectiveAsset.GIVER_SENTINEL : null;
            }
            return Codec.STRING.decodeJson(reader, extraInfo);
        }

        @Override
        @Nonnull
        public Schema toSchema(@Nonnull SchemaContext context) {
            return Codec.STRING.toSchema(context);
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
