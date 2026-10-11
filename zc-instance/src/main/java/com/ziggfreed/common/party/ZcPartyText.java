package com.ziggfreed.common.party;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;

/**
 * The library party's words: every line is a key in {@code ziggfreedcommon.party.lang}, resolved on the reader's
 * own client. A player's name is a plain string argument (a proper noun), a count a typed number.
 */
public final class ZcPartyText {

    /** The key family every line here resolves under. */
    public static final String PREFIX = "ziggfreedcommon.party.";

    private ZcPartyText() {
    }

    /** The line {@code key} with {@code args} bound as {@code {0}}, {@code {1}}, ... */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }
}
