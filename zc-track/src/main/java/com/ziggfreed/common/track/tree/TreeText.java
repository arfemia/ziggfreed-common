package com.ziggfreed.common.track.tree;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;

/**
 * Every word the tree page says: its lines are keys in {@code ziggfreedcommon.tree.lang}, the arguments bound as given
 * (a number as a typed param, a name as a nested message).
 */
public final class TreeText {

    /** The key family every line here resolves under. */
    public static final String PREFIX = "ziggfreedcommon.tree.";

    private TreeText() {
    }

    /** One of the tree page's lines. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }
}
