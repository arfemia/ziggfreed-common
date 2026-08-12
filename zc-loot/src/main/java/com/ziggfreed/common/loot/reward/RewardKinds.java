package com.ziggfreed.common.loot.reward;

import javax.annotation.Nonnull;

/**
 * The ONE reward vocabulary a server has, for the paths that cannot be handed one.
 *
 * <p>Most engines take their {@link RewardKindRegistry} as a parameter, which is the right shape:
 * it is explicit, and a test can hand over a throwaway registry. But some reward text is parsed from
 * a static context with no engine anywhere near it - an asset decoding a list of compact strings,
 * say - and those paths need a table they can simply reach for. This is it.
 *
 * <p>Registering here is what makes a kind available EVERYWHERE, including those paths. A consumer
 * registers its kinds and authoring tokens once at plugin setup, before any asset is decoded.
 */
public final class RewardKinds {

    private static final RewardKindRegistry SHARED = new RewardKindRegistry("reward-kind");

    private RewardKinds() {
    }

    /** The server-wide vocabulary: what a compact spec parses against and what pays it out. */
    @Nonnull
    public static RewardKindRegistry shared() {
        return SHARED;
    }

    /** Drop every shared registration (test reset, and the shutdown path). */
    public static void clear() {
        SHARED.clear();
    }
}
