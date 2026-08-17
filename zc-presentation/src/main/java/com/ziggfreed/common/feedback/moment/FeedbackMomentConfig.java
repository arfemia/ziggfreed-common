package com.ziggfreed.common.feedback.moment;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link FeedbackMomentAsset}, keyed by moment id.
 *
 * <p>Process-wide, because the defining ASSETS are: one store, one fold, one answer to "what does
 * this server do when a quest is completed", however many mods produce that moment. A moment nobody
 * authored a file for resolves to null and the engine does nothing with it, which is what lets this
 * library ship the capability with no content of its own.
 *
 * <p>No cache to invalidate on a reload: {@link FeedbackEngine} resolves through this config on
 * every moment, so a re-imported file lands on the next one and a deleted file goes quiet at once.
 */
public final class FeedbackMomentConfig extends AbstractKeyedAssetConfig<FeedbackMomentAsset> {

    private static final FeedbackMomentConfig INSTANCE = new FeedbackMomentConfig();

    private FeedbackMomentConfig() {
    }

    @Nonnull
    public static FeedbackMomentConfig getInstance() {
        return INSTANCE;
    }
}
