package com.ziggfreed.common.ui.menu;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;

/**
 * One tab of the shared menu: its {@code id} (what a page names as the selected tab; never an element
 * id), the label a player reads, an optional picture, the {@link Destination} a click opens, whether
 * it shows for the player looking, and an optional second line ({@link MenuSubline}) under its label.
 * The rule is asked on every paint and again on every click, since a binding outlives a toggle; a rule
 * that throws hides the entry. The second line is asked on every paint of a shown tab; one that answers
 * null, or throws, draws none, and the tab keeps its one-line row.
 */
public record MenuEntry(@Nonnull String id, @Nonnull Message label, @Nullable IconSpec icon,
                        @Nonnull Destination opens, @Nonnull Predicate<DestinationContext> visible,
                        @Nullable Function<DestinationContext, MenuSubline> subline) {

    public MenuEntry {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a menu entry needs an id");
        }
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(visible, "visible");
    }

    /** A tab with no second line. */
    public MenuEntry(@Nonnull String id, @Nonnull Message label, @Nullable IconSpec icon,
            @Nonnull Destination opens, @Nonnull Predicate<DestinationContext> visible) {
        this(id, label, icon, opens, visible, null);
    }

    /** This tab, asking {@code subline} for its second line on every paint (null for none). */
    @Nonnull
    public MenuEntry withSubline(@Nullable Function<DestinationContext, MenuSubline> subline) {
        return new MenuEntry(id, label, icon, opens, visible, subline);
    }
}
