package com.ziggfreed.common.reputation.asset;

import java.util.ArrayList;
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
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
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
 *   "Gear": { "Stat": "Reputation_Your_Traders" },
 *   "Cap": 21000,
 *   "Ranks": { "Friendly": { "Name": "yourmod.reputation.traders.rank.friendly" } },
 *   "Kills": [ { "NPCGroups": [ "Your_Bandits" ], "Amount": 5 } ],
 *   "Beyond": { "Every": 5000, "Rewards": [ { "Kind": "Lootable", "Params": { "Lootable": "Your_Cache" } } ] } }
 * }</pre>
 *
 * <p>A native group with no file here still works: it reads by its id, with no gear, no cap and no
 * kills. {@code Enabled: false} makes the reputation ABSENT (its readings answer nothing, rewards naming it
 * are refused, the page hides it); the standing players hold is never touched. Every leaf is nullable and
 * inherited through {@code Parent}: {@code Ranks} merges by rank id, {@code Kills} replaces whole, every
 * group merges leaf by leaf. The server owner retunes any of it in {@code mods/ziggfreedcommon/reputation.json}.
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
    @Nullable protected Gear gear;
    @Nullable protected Integer cap;
    @Nullable protected Map<String, RankName> ranks;
    @Nullable protected Kill[] kills;
    @Nullable protected Beyond beyond;

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
            .appendInherited(new KeyedCodec<>("Gear", Gear.CODEC, false),
                    (a, v) -> a.gear = v, a -> a.gear, (a, p) -> a.gear = p.gear)
            .documentation("The stat equipped gear moves this reputation by. Leave it out for a reputation no "
                    + "gear touches.").add()
            .appendInherited(new KeyedCodec<>("Cap", Codec.INTEGER, false),
                    (a, v) -> a.cap = v, a -> a.cap, (a, p) -> a.cap = p.cap)
            .documentation("The most standing a player can earn here through rewards and kills: a gain past it "
                    + "is cut to it, and a loss is never cut. Leave it out to let standing climb the whole "
                    + "ladder.").add()
            .appendInherited(new KeyedCodec<>("Ranks", new InheritMapCodec<>(RankName.CODEC), false),
                    (a, v) -> a.ranks = v, a -> a.ranks, (a, p) -> a.ranks = p.ranks)
            .documentation("What this reputation calls each rank of the server's ladder, by rank id (Neutral, "
                    + "Friendly, ...). A rank it does not name reads with the library's own word.").add()
            .appendInherited(new KeyedCodec<>("Kills", new ArrayCodec<>(Kill.CODEC, Kill[]::new), false),
                    (a, v) -> a.kills = v, a -> a.kills, (a, p) -> a.kills = p.kills)
            .documentation("Standing a player gains, or loses with a negative Amount, for each kill of an NPC "
                    + "whose role is in one of the listed NPC groups. Under Parent this list replaces the "
                    + "parent's whole.").add()
            .appendInherited(new KeyedCodec<>("Beyond", Beyond.CODEC, false),
                    (a, v) -> a.beyond = v, a -> a.beyond, (a, p) -> a.beyond = p.beyond)
            .documentation("Rewards for standing earned past the top rank's floor, paid each time earned "
                    + "standing crosses another multiple of Every.").add()
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

    /** The stat gear moves it by, or null. */
    @Nullable
    public String gearStat() {
        return gear == null ? null : blankToNull(gear.stat);
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

    @Nullable
    static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The Gear group: the stat equipped items move this reputation by. */
    public static final class Gear {

        @Nullable protected String stat;

        public static final BuilderCodec<Gear> CODEC = BuilderCodec.builder(Gear.class, Gear::new)
                .appendInherited(new KeyedCodec<>("Stat", Codec.STRING, false),
                        (o, v) -> o.stat = v, o -> o.stat, (o, p) -> o.stat = p.stat)
                .metadata(EditorSchema.assetRef(EntityStatType.class))
                .documentation("An EntityStatType id your pack ships for this reputation, with InitialValue 0, "
                        + "a low Min and Max 0. Items name it in their StatModifiers, and its folded maximum is "
                        + "the standing gear adds while worn.").add()
                .build();

        public Gear() {
        }
    }

    /** One rank's name under this reputation. */
    public static final class RankName {

        @Nullable protected String name;

        public static final BuilderCodec<RankName> CODEC = BuilderCodec.builder(RankName.class, RankName::new)
                .appendInherited(new KeyedCodec<>("Name", Codec.STRING, false),
                        (o, v) -> o.name = v, o -> o.name, (o, p) -> o.name = p.name)
                .documentation("Localization key for this rank's name with this reputation, in your own lang "
                        + "file.").add()
                .build();

        public RankName() {
        }
    }

    /** One kill row: the NPC groups it counts and the standing each kill moves. */
    public static final class Kill {

        @Nullable protected String[] npcGroups;
        @Nullable protected Integer amount;

        public static final BuilderCodec<Kill> CODEC = BuilderCodec.builder(Kill.class, Kill::new)
                .appendInherited(new KeyedCodec<>("NPCGroups", Codec.STRING_ARRAY, false),
                        (o, v) -> o.npcGroups = v, o -> o.npcGroups, (o, p) -> o.npcGroups = p.npcGroups)
                .metadata(EditorSchema.assetRef(NPCGroup.class))
                .documentation("Native NPC group ids (Server/NPC/Groups). A kill counts when the dead NPC's role "
                        + "is in any of them.").add()
                .appendInherited(new KeyedCodec<>("Amount", Codec.INTEGER, false),
                        (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
                .documentation("Standing per kill: positive to gain, negative to lose.").add()
                .build();

        public Kill() {
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

    /** The Beyond group: the payout past the top rank's floor. */
    public static final class Beyond {

        @Nullable protected Integer every;
        @Nullable protected RewardEntryAsset[] rewards;

        public static final BuilderCodec<Beyond> CODEC = BuilderCodec.builder(Beyond.class, Beyond::new)
                .appendInherited(new KeyedCodec<>("Every", Codec.INTEGER, false),
                        (o, v) -> o.every = v, o -> o.every, (o, p) -> o.every = p.every)
                .documentation("How much earned standing past the top rank's floor each payout takes. Zero or "
                        + "less, or unauthored, pays nothing.").add()
                .appendInherited(new KeyedCodec<>("Rewards",
                                new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new), false),
                        (o, v) -> o.rewards = v, o -> o.rewards, (o, p) -> o.rewards = p.rewards)
                .documentation("What each crossing pays: the same reward entries a quest pays.").add()
                .build();

        public Beyond() {
        }
    }
}
