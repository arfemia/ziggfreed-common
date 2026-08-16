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
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.commerce.asset.CommerceEditorDataSets;
import com.ziggfreed.common.commerce.asset.RerollAsset;
import com.ziggfreed.common.commerce.asset.RotationAsset;
import com.ziggfreed.common.commerce.asset.SelectionAsset;
import com.ziggfreed.common.progress.asset.ContentMeta;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.world.WorldSelector;

/**
 * One BOARD of rotating contracts, at {@code Server/ZiggfreedCommon/Boards/<ns>/<Id>.json}. The FILE
 * NAME is the board id.
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "ui.bounty.board.daily", "FlavorKey": "ui.bounty.board.daily.desc" },
 *   "Order": 0,
 *   "Rotation": { "Period": "Daily" },
 *   "Selection": { "Type": "Weighted_Random" },
 *   "Slots": [ { "Difficulty": "Training", "Count": 2 },
 *              { "Difficulty": "Skirmish" },
 *              { "Difficulty": "Normal", "Count": 2 },
 *              { "Difficulty": "Hard", "Optional": true } ],
 *   "Currencies": ["Bounty_Token", "Life_Essence"],
 *   "Reroll": { "Cost": { "Currencies": { "Bounty_Token": 25 } }, "MaxPerPeriod": 3 },
 *   "Grades": { "Skirmish": { "TitleKey": "board.grade.skirmish" } },
 *   "AcceptRequires": {
 *     "Normal": { "Factors": [ { "Factor": "hytale:stat", "Param": "MMO_CombatLevel", "Min": 25 } ] },
 *     "Hard":   { "Factors": [ { "Factor": "hytale:stat", "Param": "MMO_CombatLevel", "Min": 60 } ] } } }
 * }</pre>
 *
 * <p>A board is the NOTICE: what it is called, how often the postings change, what shape one posting
 * takes, and what a player has to be before they may take the heavier work. Which contracts are
 * eligible is decided by each contract's own {@code Boards} entry, so pinning something up never
 * means editing the board.
 *
 * <p><b>The posting is worked out from the clock, not remembered.</b> Every player sees the same
 * board for the same period, a restart changes nothing, and nothing has to be stored anywhere. A
 * player's own rerolls layer on top of that shared draw.
 *
 * <p><b>{@code Grades} is what each band is CALLED</b>, keyed by the band's own word. The common
 * bands (training/easy/normal/hard/elite) already read in every language with no entry here. Author
 * one for a band of your own invention and point its {@code TitleKey} at a line in your own lang
 * file; without one, the band reads as the word you typed, which is honest but is only in one
 * language. The map belongs to the BOARD, so an UNSLOTTED board (one with no {@code Slots} block at
 * all, posting whatever it holds) names its bands the same way. Under {@code Parent} this merges per
 * BAND, so a child board can rename one and keep the rest.
 *
 * <p><b>{@code AcceptRequires} gates a whole difficulty band</b>, keyed by the band's own word, and
 * each value is the ordinary {@code Requires} block every gated thing on this server uses. So a band
 * can be gated on anything a factor can read, the board itself learns nothing about levels or
 * classes, and the check happens at ACCEPT: a contract a player cannot take yet is still POSTED, and
 * still shown, locked, so they can see what to work towards.
 *
 * <p><b>{@code Currencies} is the balance strip in the header.</b> List the wallets a player earns
 * or spends AT THIS BOARD - the one its rewards pay out in, the one its reroll charges - so what
 * they need is visible while they choose. An unlisted wallet simply does not appear.
 *
 * <p><b>{@code Where} decides which worlds this board exists in at all</b>, in the one
 * world-targeting grammar every file on this server uses. Leave it out and it exists everywhere.
 */
public final class BoardAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, BoardAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Boards";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private ContentTextAsset text;
    @Nullable private String icon;
    @Nullable private Integer order;
    @Nullable private RotationAsset rotation;
    @Nullable private SelectionAsset selection;
    @Nullable private BoardSlotAsset[] slots;
    @Nullable private String[] currencies;
    @Nullable private RerollAsset reroll;
    @Nullable private Map<String, ContentTextAsset> grades;
    @Nullable private Map<String, GateSpec> acceptRequires;
    @Nullable private GateSpec requires;
    @Nullable private WorldSelector where;
    @Nullable private Map<String, JsonElement> meta;

    public static final AssetBuilderCodec<String, BoardAsset> CODEC = AssetBuilderCodec.builder(
                    BoardAsset.class,
                    BoardAsset::new,
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
            .documentation("Whether the board can be opened at all; unauthored means true. Setting false takes it "
                    + "down without deleting the contracts, and a player already carrying one can still finish.")
            .add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the player reads at the top of the board, as localization keys.")
            .add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(new UIEditor(new UIEditor.Dropdown("hytale:item")))
            .documentation("The item whose picture stands for this board wherever boards are listed side by side.")
            .add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .documentation("Lower sorts first where several boards are listed; unauthored means 0. Leave gaps "
                    + "(10, 20, 30) so a later one can be slotted between two without renumbering.")
            .add()
            .appendInherited(new KeyedCodec<>("Rotation", RotationAsset.CODEC, false),
                    (a, v) -> a.rotation = v, a -> a.rotation, (a, p) -> a.rotation = p.rotation)
            .documentation("How often the postings change. Unauthored means they never do, so the same set "
                    + "stands for good; author a cadence for anything meant to bring players back.")
            .add()
            .appendInherited(new KeyedCodec<>("Selection", SelectionAsset.CODEC, false),
                    (a, v) -> a.selection = v, a -> a.selection, (a, p) -> a.selection = p.selection)
            .documentation("Which of the eligible contracts get posted. Unauthored draws seeded picks that "
                    + "honour each contract's weight.")
            .add()
            .appendInherited(new KeyedCodec<>("Slots",
                            new ArrayCodec<>(BoardSlotAsset.CODEC, BoardSlotAsset[]::new), false),
                    (a, v) -> a.slots = v, a -> a.slots, (a, p) -> a.slots = p.slots)
            .documentation("The shape of one posting, slot by slot - how many contracts and in which difficulty "
                    + "bands. Unauthored draws from everything the board holds without shaping it, which can post "
                    + "four of the hardest band at once. This is ONE leaf: authoring it replaces an inherited "
                    + "list whole.")
            .add()
            .appendInherited(new KeyedCodec<>("Currencies", Codec.STRING_ARRAY, false),
                    (a, v) -> a.currencies = v, a -> a.currencies, (a, p) -> a.currencies = p.currencies)
            .metadata(new UIEditor(new UIEditor.Dropdown(CommerceEditorDataSets.CURRENCIES)))
            .documentation("The wallets whose balances the header shows, in the order they read. List what a "
                    + "player earns or spends at THIS board and nothing else; a balance they need for a reroll "
                    + "and cannot see is the one thing a board must never hide.")
            .add()
            .appendInherited(new KeyedCodec<>("Reroll", RerollAsset.CODEC, false),
                    (a, v) -> a.reroll = v, a -> a.reroll, (a, p) -> a.reroll = p.reroll)
            .documentation("What it costs a player to swap one posting for another, and how often they may. "
                    + "Unauthored means the board stands as posted until it turns over.")
            .add()
            .appendInherited(new KeyedCodec<>("Grades", new InheritMapCodec<>(ContentTextAsset.CODEC), false),
                    (a, v) -> a.grades = v, a -> a.grades, (a, p) -> a.grades = p.grades)
            .documentation("What each difficulty band is CALLED, keyed by the band's own word, as a localization "
                    + "key in your own lang file. The common bands (training/easy/normal/hard/elite) already read "
                    + "in words with no entry here; author one for a band you invent and point its TitleKey at a "
                    + "line of yours, and until you do that band reads as the word you typed it as. The map belongs "
                    + "to the board, so a board with no Slots block names its bands the same way. Under Parent this "
                    + "merges per BAND, so a child board can rename one band and keep the rest.")
            .add()
            .appendInherited(new KeyedCodec<>("AcceptRequires", new InheritMapCodec<>(GateSpec.CODEC), false),
                    (a, v) -> a.acceptRequires = v, a -> a.acceptRequires,
                    (a, p) -> a.acceptRequires = p.acceptRequires)
            .documentation("What a player must be before they may TAKE a contract of a given difficulty band, "
                    + "keyed by the band's own word. Each value is the ordinary Requires block, so a band can be "
                    + "gated on anything a requirement can ask. A band left out is open to everybody. Checked "
                    + "when the contract is taken, never when it is posted, so a player still sees the work they "
                    + "are not ready for. Under Parent this merges per BAND, so a child board can raise one "
                    + "band's bar and keep the rest.")
            .add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation("What a player must already have or have done before this board opens for them at "
                    + "all. An unauthored block asks for nothing. Gate a whole BAND with AcceptRequires instead "
                    + "whenever the board itself should still be readable.")
            .add()
            .appendInherited(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                    (a, v) -> a.where = v, a -> a.where, (a, p) -> a.where = p.where)
            .documentation("Which worlds this board exists in. Unauthored means every world. A world is named by "
                    + "what it is CALLED or by the gameplay config it runs, the same grammar every world-targeted "
                    + "file here uses.")
            .add()
            .appendInherited(new KeyedCodec<>(ContentMeta.KEY, ContentMeta.CODEC, false),
                    (a, v) -> a.meta = v, a -> a.meta, (a, p) -> a.meta = p.meta)
            .documentation(ContentMeta.DOCUMENTATION)
            .add()
            .build();

    public BoardAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Can the board be opened? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    @Nullable
    public String getIcon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    /** Where this board sorts among others; 0 when unauthored. */
    public int order() {
        return order == null ? 0 : order;
    }

    @Nullable
    public RotationAsset getRotation() {
        return rotation;
    }

    @Nullable
    public SelectionAsset getSelection() {
        return selection;
    }

    /** The shape of one posting, in authored order; empty when the board is unslotted. */
    @Nonnull
    public BoardSlotAsset[] slotsOrEmpty() {
        return slots == null ? new BoardSlotAsset[0] : slots;
    }

    /** The header's wallets, ids lower-cased, blanks dropped, in authored order. */
    @Nonnull
    public List<String> currencyIds() {
        if (currencies == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(currencies.length);
        for (String value : currencies) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    @Nullable
    public RerollAsset getReroll() {
        return reroll;
    }

    /** What each band is CALLED, band words lower-cased; empty when the board names none of them. */
    @Nonnull
    public Map<String, ContentTextAsset> grades() {
        if (grades == null) {
            return Map.of();
        }
        Map<String, ContentTextAsset> out = new LinkedHashMap<>();
        for (Map.Entry<String, ContentTextAsset> entry : grades.entrySet()) {
            String band = entry.getKey();
            if (band != null && !band.isBlank() && entry.getValue() != null) {
                out.put(band.trim().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        return out;
    }

    /**
     * What this board calls the {@code gradeId} band, or null when it names none. Matched however
     * either was capitalized, the same way every other id here compares.
     */
    @Nullable
    public ContentTextAsset gradeText(@Nullable String gradeId) {
        if (gradeId == null || gradeId.isBlank()) {
            return null;
        }
        return grades().get(gradeId.trim().toLowerCase(Locale.ROOT));
    }

    /** The per-band accept gates, band words lower-cased; empty when every band is open. */
    @Nonnull
    public Map<String, GateSpec> acceptRequires() {
        if (acceptRequires == null) {
            return Map.of();
        }
        Map<String, GateSpec> out = new LinkedHashMap<>();
        for (Map.Entry<String, GateSpec> entry : acceptRequires.entrySet()) {
            String band = entry.getKey();
            if (band != null && !band.isBlank() && entry.getValue() != null) {
                out.put(band.trim().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        return out;
    }

    /**
     * What a player must be to take a contract of {@code difficulty}, or null when that band is open
     * to everybody. Bands are matched however they are capitalized, so a contract written
     * {@code "Hard"} and a gate keyed {@code "hard"} are the same band.
     */
    @Nullable
    public GateSpec acceptRequiresFor(@Nullable String difficulty) {
        if (difficulty == null || difficulty.isBlank()) {
            return null;
        }
        return acceptRequires().get(difficulty.trim().toLowerCase(Locale.ROOT));
    }

    /** What must be true before the board opens at all, or null when it is open to everybody. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /** Which worlds this board exists in, or null for every world. */
    @Nullable
    public WorldSelector getWhere() {
        return where;
    }

    /** The per-namespace extra facts, exactly as authored; empty when the file carried none. */
    @Nonnull
    public Map<String, JsonElement> metaOrEmpty() {
        return ContentMeta.orEmpty(meta);
    }
}
