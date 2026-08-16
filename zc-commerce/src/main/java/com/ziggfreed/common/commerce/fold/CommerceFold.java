package com.ziggfreed.common.commerce.fold;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.commerce.asset.RerollAsset;
import com.ziggfreed.common.commerce.asset.RotationAsset;
import com.ziggfreed.common.commerce.asset.SelectionAsset;
import com.ziggfreed.common.commerce.asset.SlotAsset;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.ItemCost;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.rotation.PoolSlot;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.rotation.RotationSpec;
import com.ziggfreed.common.rotation.SelectionSpec;
import com.ziggfreed.common.shop.PurchaseLimits;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.time.DurationGroup;
import com.ziggfreed.common.util.SafeLog;

/**
 * Turns what an author WROTE into what an engine CHARGES, DRAWS and COUNTS.
 *
 * <p>Every method here is total and fail-soft. A leaf nobody could have meant - two cadences on one
 * rotation, a word that is neither {@code All} nor {@code Any} - degrades that ONE value with a
 * single line naming the file and never throws, because a fold runs over a whole catalogue and one
 * bad file must cost that file rather than every shop on the server. What the degrade actually IS
 * follows the authored leaf's own documentation, so an author reading their file and an engine
 * reading this agree; where the two halves' defaults differ, the AUTHORED one wins and the reason is
 * written on the method.
 *
 * <p>The audit is somebody else's job. A validator reports the same bad leaf as a finding an author
 * can act on; this class only has to keep the server running past it.
 */
public final class CommerceFold {

    /**
     * The cadence of a rotating set nobody gave one: it never turns over, which is exactly what the
     * authored {@code Rotation} leaf documents an unauthored group to mean.
     *
     * <p>A period this long puts every instant a server will ever see in period zero, so the draw is
     * stable forever, {@code samePeriod} is always true, and the period arithmetic saturates rather
     * than wrapping (see {@code util/PeriodMath}, which is deliberate about the far end of the range).
     */
    public static final RotationSpec NEVER = RotationSpec.of(Long.MAX_VALUE, 0L);

    private CommerceFold() {
    }

    // ==================== Cost ====================

    /**
     * The price an offer or a reroll charges. An unauthored or empty group is {@link Cost#FREE},
     * which is a real answer rather than a mistake.
     *
     * <p>A {@code Combine} word that is neither {@code All} nor {@code Any} is charged as
     * {@code All} - the unauthored behaviour - and reported, because refusing to price the offer at
     * all would take it off the page over one misspelled word.
     *
     * <p>The runtime price carries no growth curve: {@code CostAsset} declares no scaling group, so
     * a folded price is the price. Scaling stays reachable from Java ({@code Cost#scaled}) for a
     * consumer that quotes its own.
     */
    @Nonnull
    public static Cost cost(@Nullable CostAsset asset, @Nonnull String sourceId) {
        if (asset == null) {
            return Cost.FREE;
        }
        if (asset.hasUnknownCombine()) {
            SafeLog.warn("[commerce] '" + sourceId + "' prices with Combine '" + asset.getCombine()
                    + "', which is neither All nor Any, so every component is charged");
        }
        Map<String, Long> currencies = asset.currencyAmounts();
        List<ItemCost> items = new ArrayList<>();
        for (CostAsset.ItemCostAsset item : asset.itemCosts()) {
            String id = item.getItem();
            if (id != null) {
                items.add(ItemCost.of(id, item.countOrOne()));
            }
        }
        Cost.Combine combine = asset.combinesAny() ? Cost.Combine.ANY : Cost.Combine.ALL;
        return Cost.of(combine, currencies, items, null);
    }

    // ==================== Rotation ====================

    /**
     * When a rotating set turns over.
     *
     * <p>Unauthored is {@link #NEVER}, matching what the authored leaf tells an author ("unauthored
     * means it never does, so the same set stands for good"). That deliberately differs from the
     * engine seam's own convenience default of daily: the seam defaults for a caller who built a
     * spec by hand, and an authored file's own documentation is what its author is holding in their
     * head.
     *
     * <p>Two cadences on one group is a validator ERROR, so the file is already being reported; here
     * the CALENDAR one wins, because a calendar cadence is the one every player shares and picking
     * the shared answer is the smaller surprise. A {@code Period} word this schema does not know
     * degrades to {@link #NEVER} rather than to daily - a typo silently becoming a working rotation
     * is the failure nobody ever traces back to one misspelled word.
     */
    @Nonnull
    public static RotationSpec rotation(@Nullable RotationAsset asset, @Nonnull String sourceId) {
        if (asset == null || asset.isEmpty()) {
            return NEVER;
        }
        if (asset.hasBothCadences()) {
            SafeLog.warn("[commerce] '" + sourceId + "' authors both Period and Every; the calendar "
                    + "cadence is what runs, and the span is doing nothing");
        }
        return withOffset(cadence(asset, sourceId), asset.offsetMinutes());
    }

    @Nonnull
    private static RotationSpec cadence(@Nonnull RotationAsset asset, @Nonnull String sourceId) {
        if (asset.hasUnknownPeriod()) {
            SafeLog.warn("[commerce] '" + sourceId + "' names the cadence '" + asset.getPeriod()
                    + "', which is neither Daily nor Weekly, so the set never turns over");
            return NEVER;
        }
        if (asset.isWeekly()) {
            return RotationSpec.weeklyFrom(weekday(asset, sourceId));
        }
        if (asset.isCalendar()) {
            return RotationSpec.daily();
        }
        DurationGroup every = asset.getEvery();
        if (every == null) {
            return NEVER;
        }
        long spanMs = every.totalMs();
        if (spanMs <= 0L) {
            SafeLog.warn("[commerce] '" + sourceId + "' authors an Every span of no length, so the set "
                    + "never turns over");
            return NEVER;
        }
        return RotationSpec.every(spanMs);
    }

    @Nonnull
    private static DayOfWeek weekday(@Nonnull RotationAsset asset, @Nonnull String sourceId) {
        DayOfWeek parsed = asset.parsedWeekday();
        if (parsed != null) {
            return parsed;
        }
        SafeLog.warn("[commerce] '" + sourceId + "' starts its week on '" + asset.getWeekday()
                + "', which is not a day name, so it starts on Monday");
        return DayOfWeek.MONDAY;
    }

    @Nonnull
    private static RotationSpec withOffset(@Nonnull RotationSpec spec, int offsetMinutes) {
        return offsetMinutes == 0 ? spec : spec.withOffsetMinutes(offsetMinutes);
    }

    // ==================== Selection ====================

    /**
     * Which candidates a rotation draws. An unauthored group is the seeded, weight-honouring draw
     * keyed on the period, which is what the authored leaf documents.
     *
     * <p>A {@code Type} nothing registered is NOT resolved here: the spec carries the authored word
     * through and the draw itself answers with nothing, naming the pool. That is the rotation
     * engine's own standing rule, and folding a fallback in here would defeat it.
     */
    @Nonnull
    public static SelectionSpec selection(@Nullable SelectionAsset asset) {
        return asset == null ? SelectionSpec.DEFAULT : SelectionSpec.of(asset.getType(), asset.getSeed());
    }

    // ==================== Slots ====================

    /**
     * One position of a rotating set. Each domain spells its grade in its own word (a shelf's
     * {@code Tier}, a board's {@code Difficulty}) and both answer through the slot's own
     * {@code label()}, so nothing here branches on which domain wrote it.
     *
     * <p>The second filter axis ({@link PoolSlot#tag()}) has no authored leaf on either domain, so a
     * folded slot never carries one. It stays reachable from Java for a consumer drawing its own.
     */
    @Nonnull
    public static PoolSlot slot(@Nullable SlotAsset asset) {
        if (asset == null) {
            return PoolSlot.ANY;
        }
        return PoolSlot.of(asset.label(), null, asset.countOrOne(), asset.isOptional());
    }

    /** Every position, in authored order. An unslotted set answers an empty list, never null. */
    @Nonnull
    public static List<PoolSlot> slots(@Nullable SlotAsset[] assets) {
        if (assets == null || assets.length == 0) {
            return List.of();
        }
        List<PoolSlot> out = new ArrayList<>(assets.length);
        for (SlotAsset asset : assets) {
            if (asset != null) {
                out.add(slot(asset));
            }
        }
        return List.copyOf(out);
    }

    // ==================== Reroll ====================

    /**
     * What a reroll costs and how many a period allows, or null when the set offers none.
     *
     * <p>Authoring the block AT ALL is what makes a set rerollable, so an authored block carrying no
     * price is a FREE reroll rather than no reroll - which is why the null answer means only "no
     * block was written".
     */
    @Nullable
    public static RerollSpec reroll(@Nullable RerollAsset asset, @Nonnull String sourceId) {
        if (asset == null) {
            return null;
        }
        return RerollSpec.of(cost(asset.getCost(), sourceId + " reroll"), asset.maxPerPeriod());
    }

    // ==================== Purchase limits ====================

    /**
     * How often one buyer may take an offer. Both ceilings are independently optional and an
     * unauthored group limits nothing, so a folded {@link PurchaseLimits#isOpen()} says the same
     * thing an absent block does.
     */
    @Nonnull
    public static PurchaseLimits limits(@Nullable ShopEntryAsset.Limits asset) {
        if (asset == null) {
            return PurchaseLimits.NONE;
        }
        return PurchaseLimits.of(asset.daily(), asset.total());
    }

    // ==================== Rewards ====================

    /**
     * What a payout hands over, in the library's one reward vocabulary. An entry naming no kind is
     * dropped rather than paid out as nothing, since a blank kind reaches no handler either way.
     */
    @Nonnull
    public static List<RewardSpec> rewards(@Nullable RewardEntryAsset[] authored) {
        if (authored == null || authored.length == 0) {
            return List.of();
        }
        List<RewardSpec> out = new ArrayList<>(authored.length);
        for (RewardEntryAsset entry : authored) {
            RewardSpec spec = entry == null ? null : entry.toSpec();
            if (spec != null) {
                out.add(spec);
            }
        }
        return List.copyOf(out);
    }

    // ==================== Currency ====================

    /**
     * One wallet as the currency engine sees it.
     *
     * <p><b>The name is a KEY or nothing.</b> An authored {@code Text.TitleKey} is used as written;
     * with none, a counter-backed wallet falls back to the convention key {@code currency.<id>.name}
     * and an ITEM-backed one falls back to no key at all, so its name comes off the backing item's
     * own native key. That is the authored leaf's documented ladder, expressed in the one field the
     * engine keeps.
     *
     * <p>{@code Requires} does NOT cross: it gates whether a player is SHOWN a balance, which is a
     * question for whatever renders the wallet strip, and a balance a player cannot see still has to
     * be correct. A render site reads it off the asset.
     */
    @Nonnull
    public static CurrencyDef currencyDef(@Nonnull CurrencyAsset asset) {
        String id = asset.getId() == null ? "" : asset.getId();
        CurrencyDef.Builder builder = CurrencyDef.builder(id)
                .nameKey(nameKey(asset, id))
                .backingItem(asset.backingItemId())
                .iconItem(asset.getIcon())
                .color(asset.getColor())
                .cap(asset.cap())
                .lossOnDeathPercent(asset.lossOnDeath())
                .decayPerDayPercent(asset.decayPerDay());
        asset.metaOrEmpty().forEach((namespace, block) -> {
            if (namespace != null && !namespace.isBlank()) {
                builder.meta(namespace.trim().toLowerCase(Locale.ROOT), knobs(block, id, namespace));
            }
        });
        return builder.build();
    }

    /**
     * The key a counter-backed wallet that authored none is GIVEN, so a mod can name one by shipping
     * a line rather than by editing somebody's currency file.
     *
     * <p>Public because a render site has to be able to tell this key apart from an authored one: a
     * key nobody ships is a key the player would read, and only a synthesized one may be dropped in
     * favour of the wallet's own id.
     */
    @Nonnull
    public static String currencyNameKey(@Nonnull String currencyId) {
        return "currency." + currencyId + ".name";
    }

    @Nullable
    private static String nameKey(@Nonnull CurrencyAsset asset, @Nonnull String id) {
        ContentTextAsset text = asset.getText();
        String authored = text == null ? null : text.getTitleKey();
        if (authored != null && !authored.isBlank()) {
            return authored.trim();
        }
        return asset.isItemBacked() ? null : currencyNameKey(id);
    }

    /**
     * One namespace's knobs, flattened to the string pairs the engine's {@code meta} bag holds.
     *
     * <p>A namespace is a mod id, so it is matched lower-cased; a KEY is that mod's own word and is
     * kept exactly as written, because the mod reading it back wrote both ends. A knob that is not a
     * single value (a nested object, a list) has no string form the bag could carry and is skipped
     * with one line, rather than being stringified into something nobody can parse.
     */
    @Nonnull
    private static Map<String, String> knobs(@Nullable JsonElement block, @Nonnull String currencyId,
            @Nonnull String namespace) {
        Map<String, String> out = new LinkedHashMap<>();
        if (block == null || !block.isJsonObject()) {
            return out;
        }
        JsonObject object = block.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (key == null || key.isBlank() || key.startsWith("$")) {
                continue;
            }
            if (value == null || !value.isJsonPrimitive()) {
                SafeLog.fine("[commerce] wallet '" + currencyId + "' carries a Meta." + namespace + "."
                        + key + " that is not a single value, so it is not offered to that mod");
                continue;
            }
            out.put(key, value.getAsString());
        }
        return out;
    }
}
