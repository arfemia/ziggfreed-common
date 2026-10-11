package com.ziggfreed.common.instance.claims;

import java.util.Objects;

import javax.annotation.Nonnull;

import com.ziggfreed.common.instance.reward.InstanceRewardGranter;
import com.ziggfreed.common.loot.claim.ClaimStore;

/**
 * What the Claims page is built from, fixed for its life: the store it reads and pays from, the sink a Claim's
 * non-item rewards run through, and the locale-free words it paints. {@link #library()} is the library's own,
 * what the Claims tab opens.
 */
public record ClaimsPageDeps(@Nonnull ClaimStore claims, @Nonnull InstanceRewardGranter.Sink sink,
                             @Nonnull ClaimsText text) {

    public ClaimsPageDeps {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(text, "text");
    }

    /** The library's store, its sink and its words. */
    @Nonnull
    public static ClaimsPageDeps library() {
        return new ClaimsPageDeps(ClaimStore.shared(), ZcRewardSink.INSTANCE, ClaimsText.LIBRARY);
    }
}
