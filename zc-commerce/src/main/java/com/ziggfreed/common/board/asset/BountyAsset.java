package com.ziggfreed.common.board.asset;

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
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.commerce.asset.CommerceEditorDataSets;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.ContentListingAsset;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestTurnInSite;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.quest.asset.QuestObjectiveAsset;

/**
 * One CONTRACT a board can post, at {@code Server/ZiggfreedCommon/Bounties/<ns>/<Id>.json}. The FILE
 * NAME is the contract id.
 *
 * <pre>{@code
 * { "Parent": "Bounty_Kill",
 *   "Text": { "TitleKey": "quest.bounty_hunt_trork.title" },
 *   "Boards": [ { "Board": "Daily", "Difficulty": "Hard", "Weight": 1 } ],
 *   "Objectives": { "main": { "Target": "Trork", "Amount": 8 } },
 *   "Rewards": [ { "Kind": "Mmo_Currency", "Params": { "Currency": "Bounty_Token", "Amount": "300" } },
 *                { "Kind": "Mmo_Xp",       "Params": { "Skill": "AXES", "Amount": "2500" } } ] }
 * }</pre>
 *
 * <p><b>A contract IS a quest</b> - the same steps, the same rewards, the same requirement block, the
 * same inheritance - with ONE thing a quest does not have: the boards it can be posted on, and at
 * what difficulty. So everything an author already knows about writing a quest applies here, and a
 * surface that can show a quest can show a contract.
 *
 * <p><b>What a contract behaves like is decided by the TYPE, not by the file.</b> Being posted rather
 * than listed in a quest log, waiting to be collected at the board it was taken from rather than
 * paying out in the field, coming round again when the board turns over rather than on a private
 * timer: none of that is authorable, because a contract that got any of it wrong would quietly lose
 * a player their reward when the board rotated. Write the work and the pay; the rest is the same for
 * every contract on the server.
 *
 * <p><b>{@code Boards} is a LIST</b>, so one contract can hang on several boards, at a different
 * difficulty and weight on each - the same piece of work being routine on a weekly board and a real
 * ask on a daily one. A contract naming no board is never posted anywhere, which the audit says at
 * load.
 *
 * <p><b>{@code Difficulty} is a free word the content invents</b> - training, easy, normal, hard -
 * matched against a board's slots however it is capitalized. A board gates a whole band through its
 * own {@code AcceptRequires}, so nothing about who may take this contract is written here.
 *
 * <p>A family of near-identical contracts is better written as one {@code Abstract} contract plus
 * children carrying a {@code Parent} than as twenty files to keep in step.
 */
public final class BountyAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, BountyAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Bounties";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private Boolean isAbstract;
    @Nullable private ContentTextAsset text;
    @Nullable private Listing listing;
    @Nullable private BoardMembership[] boards;
    @Nullable private GateSpec requires;
    @Nullable private Map<String, QuestObjectiveAsset> objectives;
    @Nullable private RewardEntryAsset[] rewards;
    @Nullable private Map<String, JsonElement> meta;

    public static final AssetBuilderCodec<String, BountyAsset> CODEC = AssetBuilderCodec.builder(
                    BountyAsset.class,
                    BountyAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op: the id comes from the filename */ },
                    a -> a.id)
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .documentation("Whether the contract is in circulation; unauthored means true. Setting false stops "
                    + "it being posted while leaving a player who already took it able to finish.")
            .add()
            // The ONE field that deliberately does NOT inherit: a child of a skeleton is a real
            // contract, so inheriting this would make every child of a base unpostable too.
            .append(new KeyedCodec<>("Abstract", Codec.BOOLEAN, false),
                    (a, v) -> a.isAbstract = v, a -> a.isAbstract)
            .documentation("Mark a file that exists only to be inherited from. It stays available as a Parent "
                    + "target and is never posted, so a shared skeleton needs no board of its own. It never "
                    + "carries down to a child: inheriting from a skeleton makes a real contract.")
            .add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the player reads, as localization keys. TextArgs is how one written line - "
                    + "'Bring down {0} of them' - serves a whole family of contracts.")
            .add()
            .appendInherited(new KeyedCodec<>("Listing", Listing.CODEC, false),
                    (a, v) -> a.listing = v, a -> a.listing, (a, p) -> a.listing = p.listing)
            .documentation("How the contract is grouped and ordered wherever contracts are listed, and which "
                    + "ladders it is a rung of.")
            .add()
            .appendInherited(new KeyedCodec<>("Boards",
                            new ArrayCodec<>(BoardMembership.CODEC, BoardMembership[]::new), false),
                    (a, v) -> a.boards = v, a -> a.boards, (a, p) -> a.boards = p.boards)
            .documentation("The boards this contract can be posted on, at what difficulty and how strongly it "
                    + "is drawn. Several entries put one piece of work on several boards, each with its own "
                    + "band. This is ONE leaf: authoring it replaces an inherited list whole, which is how a "
                    + "child of a shared skeleton moves to a different board.")
            .add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation("What a player must already have or have done before they may take THIS contract "
                    + "specifically. An unauthored block asks for nothing. A whole difficulty band is gated on "
                    + "the board instead, so reach for this only when one contract is special.")
            .add()
            .appendInherited(new KeyedCodec<>("Objectives",
                            new InheritMapCodec<>(QuestObjectiveAsset.CODEC), false),
                    (a, v) -> a.objectives = v, a -> a.objectives, (a, p) -> a.objectives = p.objectives)
            .documentation("The work, keyed by step id. The key is also what progress is stored under, so "
                    + "renaming one starts that step over. A child contract may retune one step by id and keeps "
                    + "every step it did not mention, which is what makes 'the same hunt, but eight of them' a "
                    + "four-line file.")
            .add()
            .appendInherited(new KeyedCodec<>("Rewards",
                            new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new), false),
                    (a, v) -> a.rewards = v, a -> a.rewards, (a, p) -> a.rewards = p.rewards)
            .documentation("What the player is paid. This is ONE leaf: author it and an inherited list is "
                    + "replaced whole, omit it and the inherited list carries over.")
            .add()
            .appendInherited(new KeyedCodec<>(ContentMeta.KEY, ContentMeta.CODEC, false),
                    (a, v) -> a.meta = v, a -> a.meta, (a, p) -> a.meta = p.meta)
            .documentation(ContentMeta.DOCUMENTATION)
            .add()
            .build();

    public BountyAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** In circulation? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** A skeleton that exists only to be inherited from, never posted. */
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

    /** The boards this contract can be posted on, blanks dropped, in authored order. */
    @Nonnull
    public List<BoardMembership> boardMemberships() {
        if (boards == null) {
            return List.of();
        }
        List<BoardMembership> out = new ArrayList<>(boards.length);
        for (BoardMembership membership : boards) {
            if (membership != null && membership.getBoard() != null) {
                out.add(membership);
            }
        }
        return out;
    }

    /** The membership naming {@code boardId}, or null when this contract never hangs there. */
    @Nullable
    public BoardMembership membershipOn(@Nullable String boardId) {
        if (boardId == null || boardId.isBlank()) {
            return null;
        }
        String wanted = boardId.trim().toLowerCase(Locale.ROOT);
        for (BoardMembership membership : boardMemberships()) {
            if (wanted.equals(membership.getBoard())) {
                return membership;
            }
        }
        return null;
    }

    /** What must be true before this contract may be taken, or null when anybody may. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /** The authored steps in authored order, keyed by step id. */
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

    // ==================== the fold ====================

    /**
     * Fold this contract into the runtime definition, with the contract POLICY stamped on: posted
     * rather than listed, never paid out in the field, collected wherever it was taken from, and
     * governed by whatever posts it rather than by a private timer.
     *
     * <p>Stamping the policy here rather than asking every file to author it is what makes the
     * dangerous combinations unwritable. A contract that paid out the moment its last step finished
     * would hand a player their reward in the field and then vanish from the board, and a contract
     * whose own cooldown outlived the posting would burn the next period's slot. Neither can be
     * authored, because neither is a leaf.
     *
     * @param generatedBy the generator that produced this contract, or null when authored by hand
     */
    @Nonnull
    public QuestDefinition toDefinition(@Nullable String generatedBy) {
        String contractId = id == null ? "" : id;

        Quest.Builder quest = Quest.builder(contractId)
                .available(isEnabled())
                // Steps run in whatever order the player meets them unless a step authors its own.
                .sequential(false)
                // Never handed out on its own: a contract exists because a board posted it.
                .autoAccept(false)
                .autoTrack(false)
                // PARK, never auto-claim. A finished contract waits to be collected, so a reward
                // cannot be lost to the board turning over between finishing and coming back.
                .autoClaim(false)
                // Externally governed: whatever posts it decides when it comes round again, and the
                // clock that matters runs from FINISHING rather than from collecting, so a late
                // collection never eats into the next posting.
                .repeat(new Quest.Repeat(0L, Quest.Repeat.CooldownFrom.COMPLETE, null, 0))
                // Out of the quest log until it is taken: a board is where contracts are read.
                .visibility(new Quest.Visibility(true, false))
                // Collected at whatever posted it, so any board of that id answers.
                .turnInAt(QuestTurnInSite.ACCEPT_SITE)
                .tags(listing == null ? List.of() : listing.tagList());

        Map<String, String> objectiveText = new LinkedHashMap<>();
        for (Map.Entry<String, QuestObjectiveAsset> entry : objectivesOrEmpty().entrySet()) {
            QuestObjectiveAsset authored = entry.getValue();
            if (authored == null) {
                continue;
            }
            quest.objective(authored.toDef(entry.getKey(), null, null));
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

        return new QuestDefinition(contractId, quest.build(),
                text == null ? null : text.getTitleKey(),
                text == null ? null : text.getFlavorKey(),
                text == null ? null : text.getDisplayName(),
                text == null ? List.of() : text.titleArgs(),
                text == null ? List.of() : text.flavorArgs(),
                listing == null ? null : listing.getCategory(),
                listing == null ? 0 : listing.sortOrderOrZero(),
                listing == null ? List.of() : listing.chainList(),
                null, null, null,
                requires == null ? GateSpec.OPEN : requires,
                objectiveText,
                List.of(),
                generatedBy, metaOrEmpty());
    }

    // ==================== Listing ====================

    /** How the contract is grouped and ordered, in the library's shared listing group. */
    public static final class Listing extends ContentListingAsset {

        public static final BuilderCodec<Listing> CODEC =
                appendLeaves(BuilderCodec.builder(Listing.class, Listing::new)).build();

        public Listing() {
        }

        @Nonnull
        public static Listing of(@Nullable String category, @Nullable Integer sortOrder,
                @Nullable String[] tags) {
            Listing l = new Listing();
            l.category = category;
            l.sortOrder = sortOrder;
            l.tags = tags == null ? null : tags.clone();
            return l;
        }
    }

    // ==================== BoardMembership ====================

    /**
     * One board this contract can hang on, at what difficulty and how strongly it is drawn.
     *
     * <p>It is one group rather than three loose keys because the three only mean anything together:
     * a difficulty with no board names a band on nothing, and a weight with no board biases a draw
     * that never happens. It replaces a packed label list, where a mistyped separator silently took
     * a contract off every board without anything saying so.
     */
    public static final class BoardMembership {

        @Nullable protected String board;
        @Nullable protected String difficulty;
        @Nullable protected Double weight;

        public static final BuilderCodec<BoardMembership> CODEC =
                BuilderCodec.builder(BoardMembership.class, BoardMembership::new)
                        .appendInherited(new KeyedCodec<>("Board", Codec.STRING, false),
                                (o, v) -> o.board = v, o -> o.board, (o, p) -> o.board = p.board)
                        .metadata(new UIEditor(new UIEditor.Dropdown(CommerceEditorDataSets.BOARDS)))
                        .documentation("The board this contract can be posted on, by id.").add()
                        .appendInherited(new KeyedCodec<>("Difficulty", Codec.STRING, false),
                                (o, v) -> o.difficulty = v, o -> o.difficulty,
                                (o, p) -> o.difficulty = p.difficulty)
                        .documentation("Which of that board's slots this contract can fill, matched against the "
                                + "slot's own Difficulty. It is a free word the content invents - training, easy, "
                                + "normal, hard - and it is also the band the board gates through its "
                                + "AcceptRequires. Unauthored fits an unshaped board but no filtered slot.").add()
                        .appendInherited(new KeyedCodec<>("Weight", Codec.DOUBLE, false),
                                (o, v) -> o.weight = v, o -> o.weight, (o, p) -> o.weight = p.weight)
                        .documentation("How strongly this contract is drawn against its rivals for one slot. "
                                + "Unauthored means 1; 2 is twice as likely as a 1. Zero or less would make it "
                                + "unpostable, so it is read as 1 and reported.").add()
                        .build();

        public BoardMembership() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static BoardMembership of(@Nullable String board, @Nullable String difficulty,
                @Nullable Double weight) {
            BoardMembership m = new BoardMembership();
            m.board = board;
            m.difficulty = difficulty;
            m.weight = weight;
            return m;
        }

        /** The board id, lower-cased, or null when the entry names none. */
        @Nullable
        public String getBoard() {
            return board == null || board.isBlank() ? null : board.trim().toLowerCase(Locale.ROOT);
        }

        /** The band, lower-cased for matching, or null for "any unshaped draw". */
        @Nullable
        public String getDifficulty() {
            return difficulty == null || difficulty.isBlank()
                    ? null : difficulty.trim().toLowerCase(Locale.ROOT);
        }

        /** The band exactly as written, for a message an author has to recognize. */
        @Nullable
        public String getAuthoredDifficulty() {
            return difficulty == null || difficulty.isBlank() ? null : difficulty.trim();
        }

        /** The draw weight; 1 when unauthored or authored non-positive. */
        public double weightOrOne() {
            return weight == null || weight <= 0.0 ? 1.0 : weight;
        }

        /** The authored weight exactly as written, for an audit that must see a bad one. */
        @Nullable
        public Double getWeight() {
            return weight;
        }
    }
}
