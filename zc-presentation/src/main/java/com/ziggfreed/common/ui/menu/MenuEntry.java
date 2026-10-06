package com.ziggfreed.common.ui.menu;

import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;

/**
 * One tab of the shared menu: its {@code id} (what a page names as the selected tab; never an element
 * id), the label a player reads, an optional picture, the {@link Destination} a click opens, and whether
 * it shows for the player looking. The rule is asked on every paint and again on every click, since a
 * binding outlives a toggle; a rule that throws hides the entry.
 */
public record MenuEntry(@Nonnull String id, @Nonnull Message label, @Nullable IconSpec icon,
                        @Nonnull Destination opens, @Nonnull Predicate<DestinationContext> visible) {

    public MenuEntry {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a menu entry needs an id");
        }
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(visible, "visible");
    }
}
