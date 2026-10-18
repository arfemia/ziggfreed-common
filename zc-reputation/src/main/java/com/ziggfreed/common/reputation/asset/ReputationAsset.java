package com.ziggfreed.common.reputation.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.asset.SeasonLeaf;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.text.ContentTextAsset;

/**
 * What zc adds to one native reputation, at {@code Server/ZiggfreedCommon/Reputations/<Owner>/<Id>.json}.
 * The FILE NAME is the id of the native {@code ReputationGroup} it describes
 * ({@code Server/NPC/Reputation/Groups/<Id>.json}), matched without regard to case; the folders are the
 * author's own grouping.
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "yourmod.reputation.traders.name", "FlavorKey": "yourmod.reputation.traders.desc" },
 *   "Icon": "Your_Trader_Badge",
 *   "Order": 0,
 *   "Cap": 100000,
 *   "Ranks": { "Friendly": { "Name": "yourmod.reputation.traders.rank.friendly" },
 *              "Your_Trusted_Partner": { "Name": "yourmod.reputation.traders.rank.partner", "From": 60000 } },
 *   "Kills": [ { "NPCGroups": [ "Your_Bandits" ], "Amount": 5 },
 *              { "NPCGroups": [ "Undead" ], "Amount": 1, "Season": "Your_Event" } ],
 *   "Beyond": { "Every": 5000, "Rewards": [ { "Kind": "Lootable", "Params": { "Lootable": "Your_Cache" } } ] },
 *   "Earn": { "Lines": [ "yourmod.reputation.traders.earn.contracts" ] } }
 * }</pre>
 *
 * <p>A native group with no file here still works: it reads by its id, with no cap and no kills. Gear that
 * speeds a reputation needs no leaf here: an item raises the stat {@code ReputationGain_<Id>} (or the global
 * {@code ReputationGain}), which the library reads by that name ({@code ReputationGain}).
 * A {@code Kills} row with a {@code Season} (a calendar event's id) counts kills only while that event runs,
 * read on each kill ({@link Kill#live}); the other rows count all year as before, and an id no calendar event
 * defines is a warning in the audit, its row never counting.
 * {@code Enabled: false} makes the reputation ABSENT (its readings answer nothing, rewards naming it
 * are refused, the page hides it); the standing players hold is never touched. Every leaf is nullable and
 * inherited through {@code Parent}: {@code Ranks} merges by rank id, {@code Kills} replaces whole, every
 * group merges leaf by leaf. A {@code Ranks} entry with {@code From} is a rank of this reputation's own
 * above the server's top rank; every reading of this reputation, its bar, its page and its Beyond read it,
 * while the shared ranks keep their floors. The server owner retunes any of it in
 * {@code mods/ziggfreedcommon/reputation.json}.
 */
public final class ReputationAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, ReputationAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Reputations";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean enabled;
    @Nullable protected ContentTextAsset text;
    @Nullable protected String icon;
    @Nullable protected Integer order;
    @Nullable protected Integer cap;
    @Nullable protected Map<String, RankName> ranks;
    @Nullable protected Kill[] kills;
    @Nullable protected Beyond beyond;
    @Nullable protected Earn earn;

    public static final AssetBuilderCodec<String, ReputationAsset> CODEC = AssetBuilderCodec.builder(
                    ReputationAsset.class,
                    ReputationAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether this reputation exists on this server; unauthored means true. False makes it "
                    + "absent: its readings answer nothing, rewards naming it are refused and the page hides it. "
                    + "The standing players already hold is kept.").add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the reputation is called (TitleKey) and its description (FlavorKey), as "
                    + "localization keys in your own lang file. Without a TitleKey it is named by its id.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture stands for the reputation on its page row, its bar and its "
                    + "rank notice.").add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue(0))
            .documentation("Where the reputation sorts on the page and among the bars: lower first, then by id.")
            .add()
            .appendInherited(new KeyedCodec<>("Cap", Codec.INTEGER, false),
                    (a, v) -> a.cap = v, a -> a.cap, (a, p) -> a.cap = p.cap)
            .documentation("The most standing a player can earn here through rewards and kills: a gain past it "
                    + "is cut to it (after any reputation gain the player wears), and a loss is never cut. Leave "
                    + "it out to let standing climb the whole ladder.").add()
            .appendInherited(new KeyedCodec<>("Ranks", new InheritMapCodec<>(RankName.CODEC), false),
                    (a, v) -> a.ranks = v, a -> a.ranks, (a, p) -> a.ranks = p.ranks)
            .documentation("What this reputation calls each rank, by rank id: the server's shared ranks (Neutral, "
                    + "Friendly, ...) and any ranks of its own above the top one, which carry a From floor. A rank "
                    + "it does not name reads with the library's own word. Under Parent this merges by rank id.")
            .add()
            .appendInherited(new KeyedCodec<>("Kills", new ArrayCodec<>(Kill.CODEC, Kill[]::new), false),
                    (a, v) -> a.kills = v, a -> a.kills, (a, p) -> a.kills = p.kills)
            .documentation("Standing a player gains, or loses with a negative Amount, for each kill of an NPC "
                    + "whose role is in one of the listed NPC groups. A row with a Season counts only while that "
                    + "calendar event runs, so a reputation can pay a little more during an event. Under Parent "
                    + "this list replaces the parent's whole.").add()
            .appendInherited(new KeyedCodec<>("Beyond", Beyond.CODEC, false),
                    (a, v) -> a.beyond = v, a -> a.beyond, (a, p) -> a.beyond = p.beyond)
            .documentation("Rewards for standing earned past the floor of this reputation's top rank (its own "
                    + "highest rank when Ranks adds some above the server's top): each time earned standing "
                    + "crosses another multiple of Every, one payout waits for the player to collect it on the "
                    + "Reputation page (or pays on the spot with Collect false).").add()
            .appendInherited(new KeyedCodec<>("Earn", Earn.CODEC, false),
                    (a, v) -> a.earn = v, a -> a.earn, (a, p) -> a.earn = p.earn)
            .documentation("How a player earns this reputation, in your own words, for the Reputation page's "
                    + "How to earn block. Leave it out and the page says only what the library can see "
                    + "(kills that count).").add()
            .build();

    public ReputationAsset() {
    }

    /** The native group id this file describes, exactly as the filename spells it. */
    @Override
    public String getId() {
        return id;
    }

    /** Is the reputation switched on in its own file? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    /** The localization key the reputation is named by, or null when the file names none. */
    @Nullable
    public String titleKey() {
        return text == null ? null : blankToNull(text.getTitleKey());
    }

    /** The localization key for its description, or null. */
    @Nullable
    public String flavorKey() {
        return text == null ? null : blankToNull(text.getFlavorKey());
    }

    /** The item standing for it, or null. */
    @Nullable
    public String icon() {
        return blankToNull(icon);
    }

    /** Its place on the page and among the bars; 0 unless authored. */
    public int order() {
        return order == null ? 0 : order;
    }

    /** The most standing zc writes, or null for the ladder's own top. */
    @Nullable
    public Integer cap() {
        return cap;
    }

    /** Every rank this file names: the rank id as authored to its name key, blank keys dropped. */
    @Nonnull
    public Map<String, String> rankNames() {
        Map<String, String> out = new LinkedHashMap<>();
        if (ranks == null) {
            return out;
        }
        for (Map.Entry<String, RankName> entry : ranks.entrySet()) {
            String key = entry.getValue() == null ? null : blankToNull(entry.getValue().name);
            if (entry.getKey() != null && key != null) {
                out.put(entry.getKey().trim(), key);
            }
        }
        return out;
    }

    /** The name key this file gives {@code rankId}, matched without regard to case, or null. */
    @Nullable
    public String rankNameKey(@Nonnull String rankId) {
        String wanted = rankId.trim();
        for (Map.Entry<String, String> entry : rankNames().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Every rank this file gives a floor ({@code From}): the rank id as authored to its floor; empty when none. */
    @Nonnull
    public Map<String, Integer> tierFloors() {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (ranks == null) {
            return out;
        }
        for (Map.Entry<String, RankName> entry : ranks.entrySet()) {
            Integer from = entry.getValue() == null ? null : entry.getValue().from;
            if (entry.getKey() != null && !entry.getKey().isBlank() && from != null) {
                out.put(entry.getKey().trim(), from);
            }
        }
        return out;
    }

    /** The kill rows, in authored order; empty when none. */
    @Nonnull
    public List<Kill> kills() {
        List<Kill> out = new ArrayList<>();
        if (kills != null) {
            for (Kill kill : kills) {
                if (kill != null) {
                    out.add(kill);
                }
            }
        }
        return List.copyOf(out);
    }

    /** The standing each Beyond payout takes; 0 (pay nothing) when unauthored, zero or negative. */
    public int beyondEvery() {
        Integer every = beyond == null ? null : beyond.every;
        return every == null || every <= 0 ? 0 : every;
    }

    /**
     * Does a Beyond payout wait for the player to collect it on the Reputation page? Unauthored means true;
     * false pays each crossing on the spot.
     */
    public boolean beyondCollects() {
        return beyond == null || beyond.collect == null || beyond.collect;
    }

    /** What one Beyond crossing pays, as reward specs; empty when none. */
    @Nonnull
    public List<RewardSpec> beyondRewards() {
        List<RewardSpec> out = new ArrayList<>();
        if (beyond == null || beyond.rewards == null) {
            return out;
        }
        for (RewardEntryAsset entry : beyond.rewards) {
            RewardSpec spec = entry == null ? null : entry.toSpec();
            if (spec != null) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    /** The localization keys of the How to earn lines, in authored order, blanks dropped; empty when none. */
    @Nonnull
    public List<String> earnKeys() {
        List<String> out = new ArrayList<>();
        if (earn != null && earn.lines != null) {
            for (String line : earn.lines) {
                String key = blankToNull(line);
                if (key != null) {
                    out.add(key);
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * Add to {@code into} the missing mod of each Beyond reward row whose own {@code Requires} keeps it off
     * this server ({@code RewardEntryAsset.collectMissingMods}): what the store's fold counts in its line.
     */
    public void collectGatedRows(@Nonnull Collection<String> into) {
        RewardEntryAsset.collectMissingMods(beyond == null ? null : beyond.rewards, into);
    }

    /** Does a Beyond reward row gate on more than mod presence (a live {@code Requires})? */
    public boolean widensRows() {
        return RewardEntryAsset.anyWidened(beyond == null ? null : beyond.rewards);
    }

    @Nullable
    static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** One rank's name under this reputation, and for a rank of its own above the server's top, its floor. */
    public static final class RankName {

        @Nullable protected String name;
        @Nullable protected Integer from;

        public static final BuilderCodec<RankName> CODEC = BuilderCodec.builder(RankName.class, RankName::new)
                .appendInherited(new KeyedCodec<>("Name", Codec.STRING, false),
                        (o, v) -> o.name = v, o -> o.name, (o, p) -> o.name = p.name)
                .documentation("Localization key for this rank's name with this reputation, in your own lang "
                        + "file.").add()
                .appendInherited(new KeyedCodec<>("From", Codec.INTEGER, false),
                        (o, v) -> o.from = v, o -> o.from, (o, p) -> o.from = p.from)
                .documentation("Only on a rank of this reputation's own above the server's top rank (Exalted): "
                        + "the standing it starts at, above the top rank's floor. Each such rank lasts until the "
                        + "next one's floor, and Beyond pays past the highest. Leave it out on the server's shared "
                        + "ranks (Neutral, Friendly, ...): their floors are the same for every reputation, so a "
                        + "From there is reported and ignored.").add()
                .build();

        public RankName() {
        }
    }

    /**
     * One kill row: the NPC groups it counts, the standing each kill moves, and the calendar event it counts
     * during ({@code Season}; none means all year).
     */
    public static final class Kill {

        /** The Asset Editor's sentence for a kill row's {@code Season}. */
        static final String SEASON_DOCUMENTATION = "The calendar event this row counts during, by its id, such as "
                + "Hallows_Eve. While that event is not running the row counts no kill, as if it were not written, "
                + "and the reputation's other rows still count; while it runs, it counts like any other row. Leave "
                + "it out for a row that counts all year. An id no calendar event defines is reported, and the row "
                + "never counts.";

        @Nullable protected String[] npcGroups;
        @Nullable protected Integer amount;
        @Nullable protected String season;

        public static final BuilderCodec<Kill> CODEC = SeasonLeaf.append(BuilderCodec.builder(Kill.class, Kill::new)
                .appendInherited(new KeyedCodec<>("NPCGroups", Codec.STRING_ARRAY, false),
                        (o, v) -> o.npcGroups = v, o -> o.npcGroups, (o, p) -> o.npcGroups = p.npcGroups)
                .metadata(EditorSchema.assetRef(NPCGroup.class))
                .documentation("Native NPC group ids (Server/NPC/Groups). A kill counts when the dead NPC's role "
                        + "is in any of them.").add()
                .appendInherited(new KeyedCodec<>("Amount", Codec.INTEGER, false),
                        (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
                .metadata(EditorSchema.defaultValue(0))
                .documentation("Standing per kill: positive to gain, negative to lose.").add(),
                        (o, v) -> o.season = v, o -> o.season, SEASON_DOCUMENTATION)
                .build();

        public Kill() {
        }

        /** The calendar event this row counts during, trimmed, or null when it counts all year. */
        @Nullable
        public String season() {
            return SeasonGate.normalize(season);
        }

        /**
         * Does this row count kills right now? Always for a row with no {@code Season}; otherwise only while
         * that calendar event runs, asked afresh on every call ({@link SeasonGate#live}), so an event starting
         * or ending lands on the next kill with nothing rebuilt.
         */
        public boolean live() {
            return SeasonGate.live(season());
        }

        /** The group ids, trimmed, blanks dropped. */
        @Nonnull
        public List<String> npcGroups() {
            List<String> out = new ArrayList<>();
            if (npcGroups != null) {
                for (String group : npcGroups) {
                    String trimmed = blankToNull(group);
                    if (trimmed != null) {
                        out.add(trimmed);
                    }
                }
            }
            return List.copyOf(out);
        }

        /** The standing one kill moves; 0 when unauthored. */
        public int amount() {
            return amount == null ? 0 : amount;
        }
    }

    /** The Earn group: how a player earns this reputation, as lines the page reads out. */
    public static final class Earn {

        @Nullable protected String[] lines;

        public static final BuilderCodec<Earn> CODEC = BuilderCodec.builder(Earn.class, Earn::new)
                .appendInherited(new KeyedCodec<>("Lines", Codec.STRING_ARRAY, false),
                        (o, v) -> o.lines = v, o -> o.lines, (o, p) -> o.lines = p.lines)
                .documentation("Localization keys in your own lang file, one line each, in the order the page "
                        + "lists them: what earns this reputation (\"Finish the guild's quests\", \"Take the "
                        + "guild's board contracts\"). Under Parent this list replaces the parent's whole.").add()
                .build();

        public Earn() {
        }
    }

    /** The Beyond group: the payout past the top rank's floor. */
    public static final class Beyond {

        @Nullable protected Integer every;
        @Nullable protected RewardEntryAsset[] rewards;
        @Nullable protected Boolean collect;

        public static final BuilderCodec<Beyond> CODEC = BuilderCodec.builder(Beyond.class, Beyond::new)
                .appendInherited(new KeyedCodec<>("Every", Codec.INTEGER, false),
                        (o, v) -> o.every = v, o -> o.every, (o, p) -> o.every = p.every)
                .metadata(EditorSchema.defaultValue(0))
                .documentation("How much earned standing past the top rank's floor each payout takes. Zero or "
                        + "less, or unauthored, pays nothing.").add()
                .appendInherited(new KeyedCodec<>("Rewards",
                                new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new), false),
                        (o, v) -> o.rewards = v, o -> o.rewards, (o, p) -> o.rewards = p.rewards)
                .documentation("What each crossing pays: the same reward entries a quest pays.").add()
                .appendInherited(new KeyedCodec<>("Collect", Codec.BOOLEAN, false),
                        (o, v) -> o.collect = v, o -> o.collect, (o, p) -> o.collect = p.collect)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether each payout waits for the player to collect it; unauthored means true. "
                        + "A waiting payout raises a notice, and the Reputation page shows a Collect button that "
                        + "pays the Rewards as they are written at that moment, up to 20 payouts a press, and "
                        + "only when the inventory has room for them. The highest standing a player has earned is "
                        + "remembered, so losing standing and earning it back pays nothing twice, and a later "
                        + "change to Every pays nothing back. False pays each crossing the "
                        + "moment it happens instead, with no room check: an item that does not fit lands at the "
                        + "player's feet.").add()
                .build();

        public Beyond() {
        }
    }
}
