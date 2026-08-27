package com.ziggfreed.common.loot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;

import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.ScalarStringCodec;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * What a {@link Roll} hands over: four independent, composable leaves, every one of them optional.
 * A grants group with nothing authored is a no-op, exactly like an absent one.
 *
 * <pre>{@code
 * "Grants": {
 *   "Items":     [ { "Item": "Ingot_Iron", "Count": 3 } ],
 *   "DropLists": [ "SawmillFinds_T2" ],
 *   "Commands":  [ "give {player} Coin_Gold --quantity=5" ],
 *   "Rewards":   [ { "Kind": "currency", "Params": { "id": "bounty_token", "amount": "25" } } ]
 * }
 * }</pre>
 *
 * <h2>Why four leaves and not one</h2>
 *
 * <p>They answer genuinely different questions, and collapsing them would cost an author either
 * clarity or reach:
 *
 * <ul>
 *   <li><b>Items</b> is the direct, readable form: this exact item, this many (or a quantity that
 *       varies within a range). Author it whenever the payout is known at authoring time - it needs
 *       no other asset to exist and no mod to be installed.</li>
 *   <li><b>DropLists</b> defers the choice to a native Hytale {@code ItemDropList}, whose own
 *       weighted container decides what (and whether) anything comes out. Author it for a real random
 *       table, and reuse the same table from several rolls. Each id rolls INDEPENDENTLY in authored
 *       order, so "a guaranteed common table plus a rare one" is two entries rather than one merged
 *       asset.</li>
 *   <li><b>Commands</b> is the zero-code escape hatch: anything a server console can do.</li>
 *   <li><b>Rewards</b> reaches whatever the server has REGISTERED - a currency payout, an XP grant, a
 *       native effect, a stamped weapon. Each entry names a reward kind and hands it a bag of
 *       parameters; the kind's owner documents what it reads. A kind nobody registered simply pays
 *       nothing, so content authored for a mod that is not installed loses only that line.</li>
 * </ul>
 *
 * <p>Nothing here multiplies anything: every leaf ADDS what it names. Two rolls that both grant three
 * iron hand over six iron, and no file can silently scale a quantity authored in another.
 */
public final class LootGrants {

    /** {@link Item#getCount()} when a count is absent or not a positive number. */
    public static final int DEFAULT_ITEM_COUNT = 1;

    // ==================== Item ====================

    /**
     * ONE direct item payout: {@code {Item, Count, CountMax}}. The item id is the asset filename of
     * the item (case as the asset writes it); {@code Count} omitted means one.
     *
     * <p>{@code CountMax} makes the quantity VARY: the count handed over is drawn evenly from
     * {@code Count} up to {@code CountMax} inclusive, decided once when the payout is decided. It is
     * a separate leaf rather than a nested range because {@code Count} on its own is by far the
     * common case and had to keep reading the way it always has.
     */
    public static final class Item {

        @Nullable protected String item;
        @Nullable protected Integer count;
        @Nullable protected Integer countMax;

        public static final BuilderCodec<Item> CODEC = BuilderCodec.builder(Item.class, Item::new)
                .appendInherited(new KeyedCodec<>("Item", Codec.STRING, false),
                        (o, v) -> o.item = v, o -> o.item, (o, p) -> o.item = p.item)
                .documentation("The item asset id to hand over (the item file's name).").add()
                .appendInherited(new KeyedCodec<>("Count", Codec.INTEGER, false),
                        (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                .metadata(EditorSchema.defaultValue(1))
                .documentation("How many. Omit for 1. A stack that does not fit goes wherever the granting "
                        + "site sends overflow, so a full inventory never silently eats the find.").add()
                .appendInherited(new KeyedCodec<>("CountMax", Codec.INTEGER, false),
                        (o, v) -> o.countMax = v, o -> o.countMax, (o, p) -> o.countMax = p.countMax)
                .documentation("The top of a varying quantity, inclusive: the payout is drawn evenly between "
                        + "Count and this. Omit for exactly Count. A value below Count is ignored rather than "
                        + "inverting the range.").add()
                .build();

        public Item() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Item of(@Nullable String item, @Nullable Integer count) {
            return of(item, count, null);
        }

        /** Java-side factory for a varying quantity; sets the same fields the codec fills. */
        @Nonnull
        public static Item of(@Nullable String item, @Nullable Integer count, @Nullable Integer countMax) {
            Item i = new Item();
            i.item = item;
            i.count = count;
            i.countMax = countMax;
            return i;
        }

        @Nullable
        public String getItem() {
            return item;
        }

        @Nullable
        public Integer getCount() {
            return count;
        }

        @Nullable
        public Integer getCountMax() {
            return countMax;
        }

        /** The authored count, or {@link #DEFAULT_ITEM_COUNT} when absent or not positive. */
        public int effectiveCount() {
            return count != null && count > 0 ? count : DEFAULT_ITEM_COUNT;
        }

        /** The top of the range, which is {@link #effectiveCount()} itself when none is authored. */
        public int effectiveCountMax() {
            int low = effectiveCount();
            return countMax != null && countMax > low ? countMax : low;
        }

        /** True when the quantity varies, so a payout has to draw one. */
        public boolean varies() {
            return effectiveCountMax() > effectiveCount();
        }

        /**
         * The concrete count this payout hands over: the fixed one, or a draw from the range using
         * {@code sample} (a {@code [0,1)} number, consumed only when the quantity actually varies).
         */
        public int drawCount(@Nonnull DoubleSupplier sample) {
            int low = effectiveCount();
            int high = effectiveCountMax();
            if (high <= low) {
                return low;
            }
            double roll = sample.getAsDouble();
            int span = high - low + 1;
            int offset = (int) (roll * span);
            if (offset < 0) {
                offset = 0;
            }
            if (offset >= span) {
                offset = span - 1;
            }
            return low + offset;
        }

        /** True when no item id is authored, so this entry can never hand anything over. */
        public boolean isBlank() {
            return item == null || item.isBlank();
        }
    }

    // ==================== Reward ====================

    /**
     * ONE registered-kind payout: {@code {Kind, Params}}. Everything this layer knows about a reward
     * is its kind id and its parameters; the mod that registered the kind interprets both.
     */
    public static final class Reward {

        @Nullable protected String kind;
        @Nullable protected Map<String, String> params;

        public static final BuilderCodec<Reward> CODEC = BuilderCodec.builder(Reward.class, Reward::new)
                .appendInherited(new KeyedCodec<>("Kind", Codec.STRING, false),
                        (o, v) -> o.kind = v, o -> o.kind, (o, p) -> o.kind = p.kind)
                .documentation("The registered reward kind that pays this out. A kind nobody registered pays "
                        + "nothing, so a line written for an absent mod costs only that line.").add()
                .appendInherited(new KeyedCodec<>("Params", new MapCodec<>(ScalarStringCodec.INSTANCE, LinkedHashMap::new), false),
                        (o, v) -> o.params = v, o -> o.params, (o, p) -> o.params = p.params)
                .documentation("The arguments handed to that kind. Which keys mean what is documented "
                        + "by whoever owns the kind (the built-in item kind reads Item and Count). A number or "
                        + "true/false may be written bare (Count: 3); other values take quotes.").add()
                .build();

        public Reward() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Reward of(@Nullable String kind, @Nullable Map<String, String> params) {
            Reward r = new Reward();
            r.kind = kind;
            r.params = params;
            return r;
        }

        @Nullable
        public String getKind() {
            return kind;
        }

        @Nullable
        public Map<String, String> getParams() {
            return params;
        }

        /** True when no kind is authored, so nothing can ever be looked up for this entry. */
        public boolean isBlank() {
            return kind == null || kind.isBlank();
        }

        /** This entry as the engine-facing {@link RewardSpec}, or null when it names no kind. */
        @Nullable
        public RewardSpec toSpec() {
            if (isBlank()) {
                return null;
            }
            return params == null ? RewardSpec.of(kind) : RewardSpec.of(kind, params);
        }
    }

    // ==================== LootGrants ====================

    @Nullable protected Item[] items;
    @Nullable protected String[] dropLists;
    @Nullable protected String[] commands;
    @Nullable protected Reward[] rewards;

    public static final BuilderCodec<LootGrants> CODEC = BuilderCodec.builder(LootGrants.class, LootGrants::new)
            .appendInherited(new KeyedCodec<>("Items", new ArrayCodec<>(Item.CODEC, Item[]::new), false),
                    (o, v) -> o.items = v, o -> o.items, (o, p) -> o.items = p.items)
            .documentation("Exact items to hand over, each {Item, Count}. The direct form: it needs no other "
                    + "asset and no other mod. Use DropLists instead when the outcome should be random.").add()
            .appendInherited(new KeyedCodec<>("DropLists", new ArrayCodec<>(Codec.STRING, String[]::new), false),
                    (o, v) -> o.dropLists = v, o -> o.dropLists, (o, p) -> o.dropLists = p.dropLists)
            .documentation("Native ItemDropList asset ids. Each rolls INDEPENDENTLY, in authored order, so a "
                    + "guaranteed table plus a rare one is two entries. The table's own weights decide what "
                    + "comes out, including nothing at all.").add()
            .appendInherited(new KeyedCodec<>("Commands", new ArrayCodec<>(Codec.STRING, String[]::new), false),
                    (o, v) -> o.commands = v, o -> o.commands, (o, p) -> o.commands = p.commands)
            .documentation("Console commands run when this grants, with the granting site's placeholders "
                    + "substituted (commonly {player} and {uuid}). A positional /give count is rewritten to "
                    + "--quantity= for you, since the engine ignores the positional form.").add()
            .appendInherited(new KeyedCodec<>("Rewards", new ArrayCodec<>(Reward.CODEC, Reward[]::new), false),
                    (o, v) -> o.rewards = v, o -> o.rewards, (o, p) -> o.rewards = p.rewards)
            .documentation("Registered reward kinds to pay out, each {Kind, Params}. This is how a roll reaches "
                    + "currency, experience, an effect, or anything else a mod on this server registered.").add()
            .build();

    public LootGrants() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static LootGrants of(@Nullable Item[] items, @Nullable String[] dropLists,
            @Nullable String[] commands, @Nullable Reward[] rewards) {
        LootGrants g = new LootGrants();
        g.items = items;
        g.dropLists = dropLists;
        g.commands = commands;
        g.rewards = rewards;
        return g;
    }

    /** Convenience for the single-item authoring shape (fixtures / Java-side construction). */
    @Nonnull
    public static LootGrants ofItem(@Nonnull String itemId, int count) {
        return of(new Item[] {Item.of(itemId, count)}, null, null, null);
    }

    /** Convenience for the single-table authoring shape (fixtures / Java-side construction). */
    @Nonnull
    public static LootGrants ofDropList(@Nonnull String dropListId) {
        return of(null, new String[] {dropListId}, null, null);
    }

    @Nullable
    public Item[] getItems() {
        return items;
    }

    @Nullable
    public String[] getDropLists() {
        return dropLists;
    }

    @Nullable
    public String[] getCommands() {
        return commands;
    }

    @Nullable
    public Reward[] getRewards() {
        return rewards;
    }

    /** Every authored item entry that names an item, in authored order. */
    @Nonnull
    public List<Item> itemsOrEmpty() {
        List<Item> out = new ArrayList<>();
        if (items != null) {
            for (Item item : items) {
                if (item != null && !item.isBlank()) {
                    out.add(item);
                }
            }
        }
        return out;
    }

    /** Every authored reward entry that names a kind, as engine-facing specs, in authored order. */
    @Nonnull
    public List<RewardSpec> rewardSpecs() {
        List<RewardSpec> out = new ArrayList<>();
        if (rewards != null) {
            for (Reward reward : rewards) {
                RewardSpec spec = reward == null ? null : reward.toSpec();
                if (spec != null) {
                    out.add(spec);
                }
            }
        }
        return out;
    }

    /**
     * This group with every varying item quantity DRAWN, so what it names is now exactly what will be
     * handed over. Answers {@code this} unchanged when no quantity varies, which is the usual case.
     *
     * <p>The draw happens when the payout is DECIDED rather than when it lands, so a site that shows
     * a player their spoils before granting them cannot show one number and hand over another.
     *
     * @param sample a {@code [0,1)} number per varying quantity, in authored order
     */
    @Nonnull
    public LootGrants drawQuantities(@Nonnull DoubleSupplier sample) {
        if (items == null) {
            return this;
        }
        boolean anyVaries = false;
        for (Item item : items) {
            if (item != null && item.varies()) {
                anyVaries = true;
                break;
            }
        }
        if (!anyVaries) {
            return this;
        }
        Item[] drawn = new Item[items.length];
        for (int i = 0; i < items.length; i++) {
            Item item = items[i];
            drawn[i] = item == null ? null : Item.of(item.getItem(), item.drawCount(sample), null);
        }
        return of(drawn, dropLists, commands, rewards);
    }

    /** True when no leaf is authored - an empty group grants nothing, same as an absent one. */
    public boolean isEmpty() {
        return itemsOrEmpty().isEmpty()
                && isBlank(dropLists)
                && isBlank(commands)
                && rewardSpecs().isEmpty();
    }

    private static boolean isBlank(@Nullable String[] values) {
        if (values == null) {
            return true;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
