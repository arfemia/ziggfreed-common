package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/** The labelled toggle in a detail page's header (Pin / Unpin, Track / Untrack): its label, state and verb. */
public record DetailToggle(@Nonnull Message label, boolean on, @Nonnull String actionId, @Nullable Message tooltip) {

    public DetailToggle {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(actionId, "actionId");
    }
}
