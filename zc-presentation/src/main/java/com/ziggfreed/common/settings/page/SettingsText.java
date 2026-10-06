package com.ziggfreed.common.settings.page;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;

/**
 * Every line the Settings tab says itself, from the library's {@code ziggfreedcommon.ui.lang} under
 * {@code settings.}: a sentence is a key resolved on the reader's own client.
 */
public final class SettingsText {

    public static final String PREFIX = "ziggfreedcommon.ui.settings.";

    private SettingsText() {
    }

    /** The full registered key of {@code key}, for an entry the client resolves by message id. */
    @Nonnull
    public static String key(@Nonnull String key) {
        return PREFIX + key;
    }

    /** A line of the Settings tab as a client-resolved message. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }
}
