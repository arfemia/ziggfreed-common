package com.ziggfreed.common.asset;

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
 * <p>Such a store reads it for one thing only, whether the file loads on this server: a plain top-level
 * {@code hytale:mod_installed} condition with {@code Min: 1} keeps the file out where that mod is
 * missing ({@link ModGates}). Nothing else in the block is evaluated by these stores.
 */
public final class PresenceRequiresCodec {

    /** The sentence the Asset Editor shows beside every store's {@code Requires} leaf of this shape. */
    public static final String DOCUMENTATION = "Whether this file loads on this server at all. A condition "
            + "{ \"Factor\": \"hytale:mod_installed\", \"Param\": \"Group:Name\", \"Min\": 1 } keeps the file "
            + "out entirely where that mod is not installed, so nothing reads it or reports it. Write Min 1: "
            + "a condition with no bound is satisfied by a missing mod too. Other conditions here are not read.";

    /** The authored block: one inherited {@code Factors} leaf. */
    public static final class Block {

        @Nullable FactorCondition[] factors;

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
    }

    public static final BuilderCodec<Block> CODEC = BuilderCodec.builder(Block.class, Block::new)
            .appendInherited(new KeyedCodec<>("Factors",
                            new ArrayCodec<>(FactorCondition.codec(EditorDataSets.FACTORS), FactorCondition[]::new),
                            false),
                    (o, v) -> o.factors = v, o -> o.factors, (o, p) -> o.factors = p.factors)
            .documentation("The conditions. A plain hytale:mod_installed condition with Min 1 decides whether "
                    + "the file loads; authoring this list replaces an inherited one whole.").add()
            .build();

    private PresenceRequiresCodec() {
    }

    /** Does a file carrying {@code requires} load on this server? True when it authors no block. */
    public static boolean passesModGate(@Nullable Block requires) {
        return requires == null || ModGates.keep(requires.factorsOrEmpty());
    }
}
