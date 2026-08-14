package com.ziggfreed.common.rotation;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;

/**
 * The open table of {@link SelectionStrategy} implementations a {@link SelectionSpec} {@code Type}
 * names.
 *
 * <p>Process-wide and pre-seeded with the two the library ships, because a pool's selection type is
 * an authored word in a content file rather than something one engine instance owns: a shop pool
 * and a bounty board on the same server author out of one vocabulary or the word stops meaning one
 * thing. A consumer registers its own algorithm once at setup and content names it anywhere.
 *
 * <p><b>An unknown type resolves to null and nothing quietly happens instead.</b> A pool naming a
 * strategy nobody registered draws NOTHING rather than falling back to the default: falling back is
 * how a typo becomes a rotation that looks like it works and shows the wrong thing forever, which
 * is exactly the class of bug the registered table exists to make reportable.
 *
 * <p>Ids match case-insensitively, so an authored {@code weighted_random} and a registered
 * {@code Weighted_Random} are the same strategy.
 */
public final class SelectionStrategies {

    /** The owner recorded for the strategies the library itself ships. */
    public static final String OWNER = "ziggfreedcommon";

    private static final RegistryLedger<SelectionStrategy> LEDGER =
            new RegistryLedger<>("commerce-selection");

    static {
        LEDGER.put(SelectionSpec.TYPE_WEIGHTED_RANDOM, OWNER, new SelectionStrategy.WeightedRandom());
        LEDGER.put(SelectionSpec.TYPE_ALL, OWNER, new SelectionStrategy.All());
    }

    private SelectionStrategies() {
    }

    /**
     * Register {@code strategy} under {@code type}, replacing whatever answered to it before. Call
     * once at setup; the ledger reports an id two owners both wanted.
     */
    public static void register(@Nonnull String type, @Nonnull String owner,
            @Nonnull SelectionStrategy strategy) {
        LEDGER.put(type, owner, strategy);
    }

    /** The strategy registered under {@code type}, or null when nobody registered one. */
    @Nullable
    public static SelectionStrategy get(@Nullable String type) {
        return LEDGER.get(type);
    }

    /** The strategy a spec names, or null when nobody registered it. */
    @Nullable
    public static SelectionStrategy forSpec(@Nullable SelectionSpec spec) {
        return get(spec == null ? SelectionSpec.TYPE_WEIGHTED_RANDOM : spec.type());
    }

    /** True when something answers to {@code type}, which is what a validator asks. */
    public static boolean isRegistered(@Nullable String type) {
        return LEDGER.isRegistered(type);
    }

    /** Every registered type, for a validator, an admin listing, or an editor pick list. */
    @Nonnull
    public static Set<String> types() {
        return LEDGER.ids();
    }
}
