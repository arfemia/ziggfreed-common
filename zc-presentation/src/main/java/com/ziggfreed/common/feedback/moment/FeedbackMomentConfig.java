package com.ziggfreed.common.feedback.moment;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold of every {@link FeedbackMomentAsset}, keyed by moment id.
 *
 * <p>Process-wide, because the defining ASSETS are: one store, one fold, one answer to "what does
 * this server do when a quest is completed", however many mods produce that moment. A moment nobody
 * authored a file for resolves to null and the engine does nothing with it.
 *
 * <p><b>This library ships a neutral default file for every moment its own engines announce</b>
 * (in its jar's own asset pack, at {@code Server/ZiggfreedCommon/FeedbackMoments/}), so a bare
 * server gets a toast and a jingle out of the box. Those files ride the PACK layer like any other
 * pack's, and a consumer's same-id file wins because packs load in dependency order and a
 * later-loaded pack's file replaces an earlier one's by id: a mod that lists this library as a
 * dependency loads after it, so its {@code Quest_Completed.json} is the one that answers. The owner
 * layer, when a server writes one, wins over both.
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
