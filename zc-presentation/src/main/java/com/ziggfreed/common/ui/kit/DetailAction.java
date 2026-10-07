package com.ziggfreed.common.ui.kit;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.ui.route.Destination;

/**
 * One action-bar button: its slot, label and look, the page's own id for the verb ({@code actionId}), an optional
 * {@code destination} the host page opens through {@code Destinations} (so a contributed row can open another
 * module's screen with no module edge), whether it can be pressed now, and an optional tooltip.
 */
public record DetailAction(@Nonnull ActionSlot slot, @Nonnull Message label, @Nonnull ActionLook look,
        @Nonnull String actionId, @Nullable Destination destination, boolean enabled, @Nullable Message tooltip) {

    public DetailAction {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(actionId, "actionId");
        look = look == null ? ActionLook.NORMAL : look;
    }
}
