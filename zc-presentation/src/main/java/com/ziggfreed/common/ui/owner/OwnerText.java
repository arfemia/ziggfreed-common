package com.ziggfreed.common.ui.owner;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;

/**
 * Every line the owner menu and the owner form say themselves, from the library's {@code ziggfreedcommon.ui.lang}
 * under {@code owner.}: the menu's head, its library section and tiles, the form's buttons, state words and what a
 * save or a reset answers. A form's own words are its file's keys, never these.
 */
public final class OwnerText {

    /** The registered prefix of every key here. */
    public static final String PREFIX = "ziggfreedcommon.ui.owner.";

    private OwnerText() {
    }

    /** The full registered key of {@code key}. */
    @Nonnull
    public static String key(@Nonnull String key) {
        return PREFIX + key;
    }

    /** The line {@code key} names, its {@code {0},{1},...} bound from {@code args} (a Message nests). */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }
}
