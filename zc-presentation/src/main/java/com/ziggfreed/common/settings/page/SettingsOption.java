package com.ziggfreed.common.settings.page;

import java.util.Objects;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.ui.LocalizableString;

/**
 * One entry of a choice row's dropdown: the value the row keeps and the words the client resolves
 * (a message id, or plain data such as a number).
 */
public record SettingsOption(@Nonnull String value, @Nonnull LocalizableString label) {

    public SettingsOption {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(label, "label");
    }
}
