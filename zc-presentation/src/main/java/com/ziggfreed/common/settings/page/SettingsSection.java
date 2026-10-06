package com.ziggfreed.common.settings.page;

import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

/** One card of the Settings tab: an id, the heading over the card, and its rows in order. */
public record SettingsSection(@Nonnull String id, @Nonnull Message heading, @Nonnull List<SettingsRow> rows) {

    public SettingsSection {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a settings section needs an id");
        }
        Objects.requireNonNull(heading, "heading");
        rows = List.copyOf(rows);
    }
}
