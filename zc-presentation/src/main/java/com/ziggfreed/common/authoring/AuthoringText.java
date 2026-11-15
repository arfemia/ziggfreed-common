package com.ziggfreed.common.authoring;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;

/**
 * Where the authoring kit's own words resolve: {@code ziggfreedcommon.authoring.lang}, so a key here drops the
 * {@code ziggfreedcommon.authoring.} the file name carries. A wizard's own words (its title, steps and field labels)
 * are the wizard's keys, never these.
 */
final class AuthoringText {

    /** The key family every line here resolves under (the shipped file name, minus {@code .lang}). */
    static final String PREFIX = "ziggfreedcommon.authoring.";

    private AuthoringText() {
    }

    /** One of the kit's lines, {@code {0}, {1}...} bound from {@code args}. */
    @Nonnull
    static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, key, args);
    }

    /** The full key of one of the kit's lines, for a field label or a step name the kit's own wizard names. */
    @Nonnull
    static String key(@Nonnull String key) {
        return PREFIX + key;
    }

    /** A command or argument description key, resolved by the engine's own help. */
    @Nonnull
    static String desc(@Nonnull String what) {
        return PREFIX + "admin.desc." + what;
    }
}
