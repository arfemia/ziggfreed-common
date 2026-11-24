package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.proc.ProcAsset;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.track.asset.PrestigeReset;

/**
 * One respec of a kind of pick, at {@code Server/ZiggfreedCommon/TrackPickRespecs/<Owner>/<Id>.json}: it clears every
 * pick of one kind (the {@code ResetsAs} word a pick names or reads, {@link PrestigeReset}) on every track a player
 * climbs, gives back what each take of them cost at its price today, a share of each wallet kept, frees the faces of a
 * stance those picks held, and keeps what was only ever taught ({@code TaughtOnly}) and every pick its {@code Keep}
 * names. The FILE NAME is the id; the folders are the author's own. Every number is data here, under the owner file
 * {@code mods/ziggfreedcommon/track-pick-respecs.json}:
 *
 * <ul>
 *   <li>{@code ResetsAs}: the kind it clears (Picks, Mastery, TrainerPicks, Abilities, Techniques);</li>
 *   <li>{@code Keep}: pick ids of that kind it leaves be, {@code *} matching any run of characters
 *       ({@code Your_*_Rank});</li>
 *   <li>{@code Refund}: the share of each take's price given back, per currency ({@code Currencies}, 0 to 1, a currency
 *       it does not name coming back in full) and for items ({@code Items}, 1 unauthored); the points a cleared pick
 *       spent always come back whole;</li>
 *   <li>{@code Cost}: the fee, the library's one price shape, free unwritten;</li>
 *   <li>{@code Where}: the trainers whose page offers it.</li>
 * </ul>
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "yourmod.respec.name", "FlavorKey": "yourmod.respec.desc" },
 *   "ResetsAs": "Mastery",
 *   "Keep": [ "Your_*_Rank" ],
 *   "Refund": { "Currencies": { "Your_Coin": 0.8 } },
 *   "Where": { "Placements": [ "Your_Trainer" ] } }
 * }</pre>
 *
 * <p>{@code PickService.respecKind} is the one writer; a player's own is refused in a fight or an encounter and can be
 * undone like a pick while its page stays open.
 */
public final class TrackPickRespecAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TrackPickRespecAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackPickRespecs";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean enabled;
    @Nullable protected ContentTextAsset text;
    @Nullable protected String icon;
    @Nullable protected Integer order;
    @Nullable protected String resetsAs;
    @Nullable protected String[] keep;
    @Nullable protected Refund refund;
    @Nullable protected CostAsset cost;
    @Nullable protected TrackPickAsset.Where where;

    public static final AssetBuilderCodec<String, TrackPickRespecAsset> CODEC = AssetBuilderCodec.builder(
                    TrackPickRespecAsset.class,
                    TrackPickRespecAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether this respec exists on this server; unauthored means true. False offers it "
                    + "nowhere.").add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the respec is called (TitleKey) and a line on what it does (FlavorKey), as "
                    + "localization keys in your own lang file. Without a TitleKey it is named by its id.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture stands for the respec.").add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("Where the respec sorts among the others on a page: lower first, then by id.").add()
            .appendInherited(new KeyedCodec<>("ResetsAs", Codec.STRING, false),
                    (a, v) -> a.resetsAs = v, a -> a.resetsAs, (a, p) -> a.resetsAs = p.resetsAs)
            .metadata(PrestigeReset.editorHint())
            .documentation("The kind of pick it clears, on every track: a pick's own ResetsAs, else TrainerPicks for "
                    + "one whose Where names a trainer and Picks for any other. A respec naming no kind, or one that "
                    + "is none of the five words, clears nothing.").add()
            .appendInherited(new KeyedCodec<>("Keep", Codec.STRING_ARRAY, false),
                    (a, v) -> a.keep = v, a -> a.keep, (a, p) -> a.keep = p.keep)
            .documentation("Pick ids of that kind it leaves be, any case, * matching any run of characters "
                    + "(Your_*_Rank keeps every rank pick). What was only ever taught (TaughtOnly) is always kept. "
                    + "Leave it out to clear every pick of the kind.").add()
            .appendInherited(new KeyedCodec<>("Refund", Refund.CODEC, false),
                    (a, v) -> a.refund = v, a -> a.refund, (a, p) -> a.refund = p.refund)
            .documentation("How much of what each cleared take cost comes back, worked out from its price today (a "
                    + "price that grows with each take, at the price each take had). The points a cleared pick spent "
                    + "always come back whole. Unauthored, everything comes back.").add()
            .appendInherited(new KeyedCodec<>("Cost", CostAsset.CODEC, false),
                    (a, v) -> a.cost = v, a -> a.cost, (a, p) -> a.cost = p.cost)
            .documentation("What the respec costs, in any wallet or item (the one price shape shops use), charged "
                    + "before anything is cleared and given back if it is undone. Free when unauthored; an admin's "
                    + "respec is always free.").add()
            .appendInherited(new KeyedCodec<>("Where", TrackPickAsset.Where.CODEC, false),
                    (a, v) -> a.where = v, a -> a.where, (a, p) -> a.where = p.where)
            .documentation("Where a player buys it: the NPC placements (trainers) whose page offers it. While none "
                    + "of them stands it may be bought anywhere it is offered; with none written no trainer's page "
                    + "lists it, and a mod's own page or an admin's command offers it.").add()
            .build();

    public TrackPickRespecAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Is the respec switched on? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    /** The localization key the respec is named by, or null. */
    @Nullable
    public String titleKey() {
        String key = text == null ? null : text.getTitleKey();
        return key == null || key.isBlank() ? null : key.trim();
    }

    /** The localization key of the respec's description, or null. */
    @Nullable
    public String flavorKey() {
        String key = text == null ? null : text.getFlavorKey();
        return key == null || key.isBlank() ? null : key.trim();
    }

    /** The item standing for the respec, or null. */
    @Nullable
    public String icon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    public int order() {
        return order == null ? 0 : order;
    }

    /** The kind it clears, or null when it names none or a word that is no kind (it clears nothing). */
    @Nullable
    public PrestigeReset kind() {
        return PrestigeReset.of(resetsAs);
    }

    /** The {@code ResetsAs} word as written, trimmed, or null. */
    @Nullable
    public String getResetsAs() {
        return resetsAs == null || resetsAs.isBlank() ? null : resetsAs.trim();
    }

    /** The {@code Keep} patterns, lower-cased, blanks and repeats left out; empty for none. */
    @Nonnull
    public List<String> keep() {
        return TrackPickAsset.lowered(keep);
    }

    /**
     * Does this respec clear {@code pick}: its kind is this respec's ({@link TrackPickAsset#resetsAs}), it is not only
     * ever taught, and no {@code Keep} pattern names it? False for a respec of no kind. Pure.
     */
    public boolean clears(@Nonnull TrackPickAsset pick) {
        PrestigeReset kind = kind();
        if (kind == null || pick.taughtOnly() || pick.resetsAs() != kind) {
            return false;
        }
        List<String> kept = keep();
        return kept.isEmpty() || !ProcAsset.namesId(kept, pick.getId());
    }

    /**
     * The share of a take's price in the currency {@code currencyId} (any case) that comes back: its
     * {@code Refund.Currencies} entry held between 0 and 1, else all of it.
     */
    public double currencyShare(@Nullable String currencyId) {
        Double share = refund == null ? null : refund.currencyShares().get(normalized(currencyId));
        return share == null ? 1.0 : clamp(share);
    }

    /** The share of a take's items that comes back: {@code Refund.Items} held between 0 and 1, else all of them. */
    public double itemShare() {
        Double share = refund == null ? null : refund.items;
        return share == null ? 1.0 : clamp(share);
    }

    /** The {@code Refund} group as written, or null. */
    @Nullable
    public Refund refund() {
        return refund;
    }

    /** The fee ({@code Cost}), or null for free. */
    @Nullable
    public CostAsset fee() {
        return cost == null || cost.isFree() ? null : cost;
    }

    /** The placements whose page offers it, lower-cased; empty for none. */
    @Nonnull
    public List<String> placements() {
        return where == null ? List.of() : where.placements();
    }

    private static double clamp(double share) {
        return Double.isFinite(share) ? Math.max(0.0, Math.min(1.0, share)) : 1.0;
    }

    @Nonnull
    private static String normalized(@Nullable String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** The Refund group: the share of each cleared take's price that comes back, per currency and for items. */
    public static final class Refund {

        @Nullable protected Map<String, Double> currencies;
        @Nullable protected Double items;

        public static final BuilderCodec<Refund> CODEC = BuilderCodec.builder(Refund.class, Refund::new)
                .appendInherited(new KeyedCodec<>("Currencies", new InheritMapCodec<>(Codec.DOUBLE).documented(
                        "Per currency id, the share of its amount given back, 0 to 1.",
                        "The share given back: 1 is all of it, 0.8 keeps a fifth, 0 keeps it all."), false),
                        (o, v) -> o.currencies = v, o -> o.currencies, (o, p) -> o.currencies = p.currencies)
                .documentation("Per currency id (the ids a pick's Cost.Price writes), the share of what each cleared "
                        + "take cost in it that comes back, 0 to 1, rounded down: 0.8 keeps a fifth. A currency it "
                        + "does not name comes back in full. Under Parent, or in the owner file, one currency written "
                        + "merges into the map below it.").add()
                .appendInherited(new KeyedCodec<>("Items", Codec.DOUBLE, false),
                        (o, v) -> o.items = v, o -> o.items, (o, p) -> o.items = p.items)
                .metadata(EditorSchema.defaultValue(1.0))
                .documentation("The share of every item a cleared take cost that comes back, 0 to 1, rounded down. 1 "
                        + "when unauthored: every item back.").add()
                .build();

        public Refund() {
        }

        /** The currency shares by lower-cased id as written (blank ids and non-numbers left out), in authored order. */
        @Nonnull
        public Map<String, Double> currencyShares() {
            if (currencies == null || currencies.isEmpty()) {
                return Map.of();
            }
            Map<String, Double> out = new LinkedHashMap<>();
            currencies.forEach((currency, share) -> {
                if (currency != null && !currency.isBlank() && share != null) {
                    out.putIfAbsent(normalized(currency), share);
                }
            });
            return Collections.unmodifiableMap(out);
        }

        /** The {@code Items} share as written, or null. */
        @Nullable
        public Double itemShareAsWritten() {
            return items;
        }

        /** Every share written that lies outside 0 to 1, as {@code <where>=<share>}, for an audit. */
        @Nonnull
        public List<String> outOfRange() {
            List<String> out = new ArrayList<>();
            currencyShares().forEach((currency, share) -> {
                if (!Double.isFinite(share) || share < 0.0 || share > 1.0) {
                    out.add(currency + "=" + share);
                }
            });
            if (items != null && (!Double.isFinite(items) || items < 0.0 || items > 1.0)) {
                out.add("Items=" + items);
            }
            return out;
        }
    }
}
