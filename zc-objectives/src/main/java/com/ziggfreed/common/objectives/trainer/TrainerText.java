package com.ziggfreed.common.objectives.trainer;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;

/**
 * Every word the trainer page says itself: keys in {@code ziggfreedcommon.trainer.lang}, its arguments bound as
 * given (a number a typed param, a name a nested {@link Message}).
 */
public final class TrainerText {

    /** The key family every line here resolves under. */
    public static final String PREFIX = "ziggfreedcommon.trainer.";

    private TrainerText() {
    }

    /** One of the trainer page's lines. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }
}
