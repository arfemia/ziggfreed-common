package com.ziggfreed.common.loot;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;

/**
 * The DRAW half of a loot table: a bag of weighted entries plus how many picks the moment earns.
 *
 * <pre>{@code
 * "Pool": {
 *   "Rolls": { "Base": 2,
 *              "Factors": [ { "Factor": "ziggfreedcommon:instance_score", "Weight": 0.000556 } ],
 *              "Clamp": { "Max": 5 } },
 *   "Entries": [
 *     { "Weight": 10, "Grants": { "Items": [ { "Item": "Gustbloom", "Count": 2 } ] } },
 *     { "Weight": 3,
 *       "Conditions": [ { "Factor": "ziggfreedcommon:instance_score", "Min": 4000 } ],
 *       "Grants": { "Items": [ { "Item": "Life_Essence_Concentrated" } ] } }
 *   ]
 * }
 * }</pre>
 *
 * <h2>Rolls and Entries answer different questions</h2>
 *
 * <p>A table's {@code Rolls} LIST is the set of conditional payouts that each stand on their own -
 * every one of them is read, and every one that passes hands over what it names. A {@code Pool} is
 * the opposite shape: its entries COMPETE, and only as many of them as {@code Pool.Rolls} works out
 * to are drawn. Author the list for "everybody gets this", the pool for "and one of these".
 *
 * <p>{@code Pool.Rolls} is the ordinary {@code {Base, Factors, Clamp}} formula, so how many picks a
 * moment earns is written in the same vocabulary as everything else and can rise with any reading a
 * mod on this server offers: a run score, a tool's quality, a player's luck. Omit it for a single
 * pick. The result is taken down to a whole number of picks, held at or above zero, and held under
 * {@link #MAX_PICKS} whatever the terms say - author {@code Clamp.Max} for the ceiling you actually
 * want, since that hard limit exists to stop a runaway formula rather than to balance anything.
 *
 * <h2>Entries draw WITH replacement</h2>
 *
 * <p>Three picks over a pool can hand over the same entry three times, which is what a loot pool
 * means by three rolls. An entry whose {@code Conditions} do not pass is not in the bag at all, so a
 * premium entry gated on a high score simply does not compete until it is earned. A weight of zero
 * is never picked; a pool whose every eligible weight is zero falls back to an even chance, so a
 * table whose author forgot the weights still hands something over rather than going dark.
 *
 * <h2>The pool fires on the site's plain moment</h2>
 *
 * <p>A {@code Roll} can name the {@code Trigger} it answers to; a pool cannot, and is drawn on
 * whatever the granting site calls its default moment. A site that offers several moments and wants
 * a competing draw at a particular one authors that draw as an inline roll there instead.
 */
public final class LootPool {

    /** How many picks a pool authoring no {@code Rolls} formula makes. */
    public static final int DEFAULT_PICKS = 1;

    /**
     * The most picks one pool can ever make in one pass. It is a runaway guard, not a balance knob:
     * a formula whose terms climb without a {@code Clamp} would otherwise draw until the moment
     * stopped being a moment. Author {@code Clamp.Max} for the real ceiling.
     */
    public static final int MAX_PICKS = 64;

    /**
     * How far under a whole number a pick count may land and still count as that whole number.
     *
     * <p>A bonus pick per N points is written as a term weighted {@code 1/N}, and no binary double
     * holds a value like one twelve-hundredth exactly, so a player sitting on exactly the threshold
     * would otherwise be told they had 0.9999999 of a pick and handed nothing. The tolerance is far
     * smaller than any authored difference and far larger than the drift, so it can only ever
     * recover the pick that was meant.
     */
    public static final double PICK_ROUNDING_TOLERANCE = 1e-6;

    // ==================== Entry ====================

    /**
     * ONE competing outcome: {@code {Weight, Conditions, Grants}}. Its grants are handed over exactly
     * as a roll's are, so everything a roll can pay out an entry can pay out too.
     */
    public static final class Entry {

        /** The weight an entry authoring none competes with. */
        public static final double DEFAULT_WEIGHT = 1.0;

        @Nullable protected Double weight;
        @Nullable protected FactorCondition[] conditions;
        @Nullable protected LootGrants grants;

        /** The plain codec: a factor id stays a free text field. */
        public static final BuilderCodec<Entry> CODEC = codec(null);

        /** The factory form, so every level of the group is built with the same dropdown dataset. */
        @Nonnull
        public static BuilderCodec<Entry> codec(@Nullable String editorDropdownDataSetId) {
            return BuilderCodec.builder(Entry.class, Entry::new)
                    .appendInherited(new KeyedCodec<>("Weight", Codec.DOUBLE, false),
                            (o, v) -> o.weight = v, o -> o.weight, (o, p) -> o.weight = p.weight)
                    .documentation("How often this entry comes up relative to its neighbours. Omit for 1. A "
                            + "weight of 0 is never picked, which is how an entry is parked without deleting "
                            + "it.").add()
                    .appendInherited(new KeyedCodec<>("Conditions",
                                    new ArrayCodec<>(FactorCondition.codec(editorDropdownDataSetId),
                                            FactorCondition[]::new), false),
                            (o, v) -> o.conditions = v, o -> o.conditions, (o, p) -> o.conditions = p.conditions)
                    .documentation("Every entry must pass before this outcome competes at all. This is where a "
                            + "premium entry says what earns it, and where an outcome says it only happens on a "
                            + "win. A factor nobody can answer keeps the entry out of the bag.").add()
                    .appendInherited(new KeyedCodec<>("Grants", LootGrants.CODEC, false),
                            (o, v) -> o.grants = v, o -> o.grants, (o, p) -> o.grants = p.grants)
                    .documentation("What being picked hands over. The same four leaves a roll grants through, so "
                            + "an entry can pay items, a native drop list, commands, or any registered reward "
                            + "kind.").add()
                    .build();
        }

        public Entry() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Entry of(@Nullable Double weight, @Nullable FactorCondition[] conditions,
                @Nullable LootGrants grants) {
            Entry e = new Entry();
            e.weight = weight;
            e.conditions = conditions;
            e.grants = grants;
            return e;
        }

        @Nullable
        public Double getWeight() {
            return weight;
        }

        @Nullable
        public FactorCondition[] getConditions() {
            return conditions;
        }

        @Nullable
        public LootGrants getGrants() {
            return grants;
        }

        /** The authored weight when it is a finite non-negative number, else {@link #DEFAULT_WEIGHT}. */
        public double effectiveWeight() {
            if (weight == null || !Double.isFinite(weight)) {
                return DEFAULT_WEIGHT;
            }
            return Math.max(0.0, weight);
        }

        /** True when this entry's conditions admit it under {@code lookup}. */
        public boolean isEligible(@Nonnull FactorLookup lookup) {
            return FactorGate.pass(conditions, lookup);
        }

        /** True when nothing would be handed over even after a pick. */
        public boolean isEmpty() {
            return grants == null || grants.isEmpty();
        }
    }

    // ==================== LootPool ====================

    @Nullable protected FactorFormula rolls;
    @Nullable protected Entry[] entries;

    /** The plain codec: a factor id stays a free text field. */
    public static final BuilderCodec<LootPool> CODEC = codec(null);

    /** The factory form, so every level of the group is built with the same dropdown dataset. */
    @Nonnull
    public static BuilderCodec<LootPool> codec(@Nullable String editorDropdownDataSetId) {
        return BuilderCodec.builder(LootPool.class, LootPool::new)
                .appendInherited(new KeyedCodec<>("Rolls",
                                FactorFormula.codec(editorDropdownDataSetId), false),
                        (o, v) -> o.rolls = v, o -> o.rolls, (o, p) -> o.rolls = p.rolls)
                .documentation("How many picks this pool makes, as an ordinary formula. Base is the picks the "
                        + "moment earns with no bonuses; a term weighted one over N adds a pick per N points of "
                        + "that reading; Clamp.Max is the ceiling. Omit the whole group for a single pick. The "
                        + "answer is taken down to a whole number of picks.").add()
                .appendInherited(new KeyedCodec<>("Entries",
                                new ArrayCodec<>(Entry.codec(editorDropdownDataSetId), Entry[]::new), false),
                        (o, v) -> o.entries = v, o -> o.entries, (o, p) -> o.entries = p.entries)
                .documentation("The competing outcomes. Picks are drawn WITH replacement, so one entry can come "
                        + "up twice in a three-pick draw. Authoring this in a file with a Parent REPLACES the "
                        + "parent's entries rather than adding to them.").add()
                .build();
    }

    public LootPool() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static LootPool of(@Nullable FactorFormula rolls, @Nullable Entry[] entries) {
        LootPool p = new LootPool();
        p.rolls = rolls;
        p.entries = entries;
        return p;
    }

    @Nullable
    public FactorFormula getRolls() {
        return rolls;
    }

    @Nullable
    public Entry[] getEntries() {
        return entries;
    }

    /** True when the pool holds no entry at all, so drawing from it can never produce anything. */
    public boolean isEmpty() {
        if (entries == null) {
            return true;
        }
        for (Entry entry : entries) {
            if (entry != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * How many picks this pool makes under {@code lookup}: the evaluated {@code Rolls} formula taken
     * DOWN to a whole number (within {@link #PICK_ROUNDING_TOLERANCE}), held at or above zero and
     * under {@link #MAX_PICKS}. A pool authoring no formula makes {@link #DEFAULT_PICKS} pick; a
     * formula that works out to something that is not a finite number makes none.
     */
    public int pickCount(@Nonnull FactorLookup lookup) {
        if (rolls == null) {
            return DEFAULT_PICKS;
        }
        double value = rolls.evaluate(lookup.asFunction());
        if (!Double.isFinite(value)) {
            return 0;
        }
        double whole = Math.floor(value + PICK_ROUNDING_TOLERANCE);
        if (whole <= 0.0) {
            return 0;
        }
        return (int) Math.min(MAX_PICKS, whole);
    }

    /** The entries actually competing under {@code lookup}, in authored order. */
    @Nonnull
    public List<Entry> eligible(@Nonnull FactorLookup lookup) {
        List<Entry> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (Entry entry : entries) {
            if (entry != null && entry.isEligible(lookup)) {
                out.add(entry);
            }
        }
        return out;
    }
}
