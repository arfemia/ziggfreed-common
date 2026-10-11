package com.ziggfreed.common.instance.claims;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;

/**
 * The Claims tab's own words, so the tab reads on a server running nothing but this library: every line is a
 * key in {@code ziggfreedcommon.claims.lang}, resolved on the reader's client, so this provider never knows a
 * locale. Immutable; the page takes it in its deps ({@link ClaimsPageDeps}), and the notices and the command
 * read the library's own ({@link #LIBRARY}).
 */
public final class ClaimsText {

    /** The key family every line resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.claims.";

    /** The library's own words. */
    public static final ClaimsText LIBRARY = new ClaimsText();

    private ClaimsText() {
    }

    /** A line of the family as a client-resolved message, {@code {0}, {1}, ...} bound from {@code args}. */
    @Nonnull
    public Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }
}
