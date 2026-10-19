package com.ziggfreed.common.asset;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.ModGates;

/**
 * The {@code Requires} shape for a store below zc-progression (where {@code GateSpec} lives): the same
 * JSON a quest's block writes, {@code { "Factors": [ { "Factor", "Param", "Min", "Max" } ] }}, read
 * through the same condition codec, so a pack author writes one shape everywhere.
 *
 * <p>Such a store reads it two ways. A plain top-level {@code hytale:mod_installed} condition with
 * {@code Min: 1} decides whether the file loads on this server at all: it keeps the file out where that
 * mod is missing ({@link ModGates}), and nothing asks it again. Every other condition in {@code Factors}
 * ({@link Block#liveFactors}) is asked where the file pays or applies, through the factor vocabulary that
 * store already reads: a loot table before any of its rolls or pool entries, a bonus row before its chance,
 * a gear set at each recompute of its wearer, a reward row before it pays. A condition about a player fails
 * closed where the question has no player in it (an offline one, or a pay site that asks about none).
 *
 * <p>The block is {@code Factors} alone: the shorthand leaves a quest's block also takes
 * ({@code Permission}, {@code Quests}, {@code Custom}, {@code AllOf}, {@code AnyOf}, {@code Not}) are not
 * read here. Write a permission as the {@code hytale:permission} factor and a finished quest as
 * {@code ziggfreedcommon:quest_completed}.
 *
 * <p>A reward row (zc-progression's {@code RewardEntryAsset}) carries the same block for itself: where its
 * mod is missing the row is absent while the rest of its file loads, its other conditions decide whom it
 * pays, and the editor shows {@link #ROW_DOCUMENTATION} beside it.
 */
public final class PresenceRequiresCodec {

    /** The sentence the Asset Editor shows beside every store's {@code Requires} leaf of this shape. */
    public static final String DOCUMENTATION = "Whether this file loads on this server at all, and whom it pays. "
            + "A condition { \"Factor\": \"hytale:mod_installed\", \"Param\": \"Group:Name\", \"Min\": 1 } keeps "
            + "the file out entirely where that mod is not installed, so nothing reads it or reports it. Write "
            + "Min 1: a condition with no bound is satisfied by a missing mod too. Every other condition here is "
            + "asked of the player each time the file pays or applies (a table's rolls, a bonus row's find, a "
            + "gear set's bonuses), and one about the player fails where no player is there to ask.";

    /** The sentence the Asset Editor shows beside a reward row's {@code Requires} leaf of this shape. */
    public static final String ROW_DOCUMENTATION = "Whether this reward row exists on this server at all, and "
            + "whom it pays. A condition { \"Factor\": \"hytale:mod_installed\", \"Param\": \"Group:Name\", "
            + "\"Min\": 1 } leaves the row out where that mod is not installed: it pays nothing, shows nowhere and "
            + "is not reported, while the rest of the list pays as written. Write Min 1: a condition with no "
            + "bound is satisfied by a missing mod too. Every other condition here is asked of the player as the "
            + "row pays: a player who fails one gets nothing for this row while the rest of the list pays, and a "
            + "shop refuses the purchase rather than take the price for it.";

    /** What a block authoring nothing beyond mod presence leaves to ask. */
    private static final FactorCondition[] NONE = new FactorCondition[0];

    /** The authored block: one inherited {@code Factors} leaf. */
    public static final class Block {

        @Nullable FactorCondition[] factors;

        /** {@link #liveFactors()}, worked out on its first read: a decoded block never changes after. */
        @Nullable private volatile FactorCondition[] live;

        public Block() {
        }

        /** Java-side factory; sets the same field the codec fills. */
        @Nonnull
        public static Block of(@Nullable FactorCondition... factors) {
            Block block = new Block();
            block.factors = factors == null ? null : factors.clone();
            return block;
        }

        /** The authored conditions without copying, for the fold-time gate. */
        @Nonnull
        public FactorCondition[] factorsOrEmpty() {
            return factors == null ? new FactorCondition[0] : factors;
        }

        /**
         * The authored conditions less every plain mod-presence gate ({@link ModGates#isPresenceGate}), which
         * the fold already decided: what a store asks where the file pays or applies. A null or blank entry is
         * dropped, and the answer is empty when nothing is left to ask. Never the authored array itself; worked
         * out once and shared by every reader (a bonus row asks it at each kill or break), so never modify it.
         */
        @Nonnull
        public FactorCondition[] liveFactors() {
            FactorCondition[] known = live;
            if (known == null) {
                known = computeLive();
                live = known;
            }
            return known;
        }

        @Nonnull
        private FactorCondition[] computeLive() {
            if (factors == null || factors.length == 0) {
                return NONE;
            }
            List<FactorCondition> kept = new ArrayList<>(factors.length);
            for (FactorCondition condition : factors) {
                if (condition != null && !condition.isBlank() && !ModGates.isPresenceGate(condition)) {
                    kept.add(condition);
                }
            }
            return kept.isEmpty() ? NONE : kept.toArray(new FactorCondition[0]);
        }
    }

    public static final BuilderCodec<Block> CODEC = BuilderCodec.builder(Block.class, Block::new)
            .appendInherited(new KeyedCodec<>("Factors",
                            new ArrayCodec<>(FactorCondition.codec(EditorDataSets.FACTORS), FactorCondition[]::new),
                            false),
                    (o, v) -> o.factors = v, o -> o.factors, (o, p) -> o.factors = p.factors)
            .documentation("The conditions. A plain hytale:mod_installed condition with Min 1 decides whether "
                    + "the file or reward row carrying this block loads; every other condition is asked of the "
                    + "player where it pays or applies. Authoring this list replaces an inherited one whole.")
            .add()
            .build();

    private PresenceRequiresCodec() {
    }

    /** {@link Block#liveFactors()} of {@code requires}, empty when it authors no block. */
    @Nonnull
    public static FactorCondition[] liveFactors(@Nullable Block requires) {
        return requires == null ? NONE : requires.liveFactors();
    }

    /** Does a file carrying {@code requires} load on this server? True when it authors no block. */
    public static boolean passesModGate(@Nullable Block requires) {
        return missingMod(requires) == null;
    }

    /**
     * The mod ({@code Group:Name}) that keeps a file carrying {@code requires} off this server, or null
     * when it loads here: the read a gated store's load handler and owner file pass on.
     */
    @Nullable
    public static String missingMod(@Nullable Block requires) {
        return requires == null ? null : ModGates.missingMod(requires.factorsOrEmpty());
    }
}
