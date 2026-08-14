package com.ziggfreed.common.commerce.asset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.schema.metadata.ui.UIEditor;
import com.ziggfreed.common.codec.InheritMapCodec;

/**
 * A PRICE: what a player hands over. Wallet amounts, inventory items, or both.
 *
 * <pre>{@code
 * "Cost": { "Currencies": { "Bounty_Token": 150, "Life_Essence": 80 },
 *           "Items":      [ { "Item": "Ore_Iron", "Count": 16 } ] }
 * }</pre>
 *
 * <p><b>There is ONE spelling of a price in this library, and this is it.</b> A shop offer's price, a
 * reroll's price, and anything else that charges for something all author this same group, so an
 * author learns it once and a new payment route (a second wallet, an item) reaches every priced
 * thing at the same moment. A terser "one currency and an amount" shorthand is deliberately absent:
 * two spellings of a price is how a multi-currency reroll ends up impossible to author.
 *
 * <p><b>{@code Combine} picks which of two payment routes applies</b>, and it is the union
 * discriminator rather than a bundle of switches: {@code All} (unauthored) charges every component,
 * {@code Any} charges exactly ONE of them, so "300 tokens OR 12 adamantite" is one offer rather than
 * two. Nothing else about the price changes with it.
 *
 * <p>An empty group is FREE, which is a real answer: a starter offer, an unpriced reroll.
 *
 * <p>Every leaf is {@code appendInherited}, so a file with a {@code Parent} can retune the currency
 * amounts and keep the item list it did not mention.
 *
 * <p><b>Seam.</b> This is the AUTHORED shape. The commerce engine builds its own runtime price value
 * from {@link #currencyAmounts()} / {@link #itemCosts()} / {@link #combinesAny()}; that fold is the
 * engine's, and this type deliberately imports nothing from it so the authoring layer stays
 * readable on its own.
 */
public final class CostAsset {

    /** {@code Combine} authored as "charge every component"; the default. */
    public static final String COMBINE_ALL = "All";

    /** {@code Combine} authored as "charge exactly one component". */
    public static final String COMBINE_ANY = "Any";

    @Nullable protected Map<String, Long> currencies;
    @Nullable protected ItemCostAsset[] items;
    @Nullable protected String combine;

    public static final BuilderCodec<CostAsset> CODEC = BuilderCodec.builder(CostAsset.class, CostAsset::new)
            .appendInherited(new KeyedCodec<>("Currencies", new InheritMapCodec<>(Codec.LONG), false),
                    (o, v) -> o.currencies = v, o -> o.currencies, (o, p) -> o.currencies = p.currencies)
            .documentation("Wallet amounts, keyed by currency id. A currency no definition exists for cannot be "
                    + "charged, so the price can never be met and the offer stays unaffordable; the audit says so "
                    + "at load rather than leaving a player wondering.").add()
            .appendInherited(new KeyedCodec<>("Items",
                            new ArrayCodec<>(ItemCostAsset.CODEC, ItemCostAsset[]::new), false),
                    (o, v) -> o.items = v, o -> o.items, (o, p) -> o.items = p.items)
            .documentation("Inventory items taken as payment, each with its own count. This is ONE leaf: author "
                    + "it and an inherited list is replaced whole.").add()
            .appendInherited(new KeyedCodec<>("Combine", Codec.STRING, false),
                    (o, v) -> o.combine = v, o -> o.combine, (o, p) -> o.combine = p.combine)
            .documentation("Which payment route applies: All (unauthored) charges every component together, Any "
                    + "charges exactly one of them, so a price payable in either of two wallets is one offer "
                    + "rather than two.").add()
            .build();

    /** The price that asks for nothing. */
    public static final CostAsset FREE = new CostAsset();

    public CostAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static CostAsset of(@Nullable Map<String, Long> currencies, @Nullable ItemCostAsset[] items,
            @Nullable String combine) {
        CostAsset c = new CostAsset();
        c.currencies = currencies == null ? null : new LinkedHashMap<>(currencies);
        c.items = items == null ? null : items.clone();
        c.combine = combine;
        return c;
    }

    /** A single-wallet price, the commonest shape. */
    @Nonnull
    public static CostAsset ofCurrency(@Nonnull String currencyId, long amount) {
        Map<String, Long> one = new LinkedHashMap<>();
        one.put(currencyId, amount);
        return of(one, null, null);
    }

    /** The authored wallet amounts, ids lower-cased, blanks and non-positive amounts dropped. */
    @Nonnull
    public Map<String, Long> currencyAmounts() {
        if (currencies == null) {
            return Map.of();
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : currencies.entrySet()) {
            String id = entry.getKey();
            Long amount = entry.getValue();
            if (id != null && !id.isBlank() && amount != null && amount > 0L) {
                out.put(id.trim().toLowerCase(Locale.ROOT), amount);
            }
        }
        return out;
    }

    /** The authored wallet amounts EXACTLY as written, for an audit that must see a bad one. */
    @Nullable
    public Map<String, Long> getCurrencies() {
        return currencies == null ? null : new LinkedHashMap<>(currencies);
    }

    /** The authored item payments, blanks dropped, in authored order. */
    @Nonnull
    public List<ItemCostAsset> itemCosts() {
        if (items == null) {
            return List.of();
        }
        List<ItemCostAsset> out = new ArrayList<>(items.length);
        for (ItemCostAsset item : items) {
            if (item != null && item.getItem() != null) {
                out.add(item);
            }
        }
        return out;
    }

    /** The authored combine word exactly as written, unparsed; null when unauthored. */
    @Nullable
    public String getCombine() {
        return combine;
    }

    /** True when this price charges exactly ONE component rather than all of them. */
    public boolean combinesAny() {
        return combine != null && COMBINE_ANY.equalsIgnoreCase(combine.trim());
    }

    /** True when a value was authored under {@code Combine} that is neither word. */
    public boolean hasUnknownCombine() {
        if (combine == null || combine.isBlank()) {
            return false;
        }
        String value = combine.trim();
        return !COMBINE_ALL.equalsIgnoreCase(value) && !COMBINE_ANY.equalsIgnoreCase(value);
    }

    /** True when nothing is charged at all. */
    public boolean isFree() {
        return currencyAmounts().isEmpty() && itemCosts().isEmpty();
    }

    // ==================== ItemCostAsset ====================

    /** One inventory item taken as payment, and how many of it. */
    public static final class ItemCostAsset {

        @Nullable protected String item;
        @Nullable protected Integer count;

        public static final BuilderCodec<ItemCostAsset> CODEC =
                BuilderCodec.builder(ItemCostAsset.class, ItemCostAsset::new)
                        .appendInherited(new KeyedCodec<>("Item", Codec.STRING, false),
                                (o, v) -> o.item = v, o -> o.item, (o, p) -> o.item = p.item)
                        .metadata(new UIEditor(new UIEditor.Dropdown("hytale:item")))
                        .documentation("The item id taken as payment.").add()
                        .appendInherited(new KeyedCodec<>("Count", Codec.INTEGER, false),
                                (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                        .documentation("How many. Unauthored means 1.").add()
                        .build();

        public ItemCostAsset() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static ItemCostAsset of(@Nullable String item, @Nullable Integer count) {
            ItemCostAsset i = new ItemCostAsset();
            i.item = item;
            i.count = count;
            return i;
        }

        /** The item id, or null when the entry names none. */
        @Nullable
        public String getItem() {
            return item == null || item.isBlank() ? null : item.trim();
        }

        /** How many, at least 1. */
        public int countOrOne() {
            return count == null || count < 1 ? 1 : count;
        }

        /** The authored count exactly as written, for an audit that must see a bad one. */
        @Nullable
        public Integer getCount() {
            return count;
        }
    }
}
