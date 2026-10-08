package com.ziggfreed.common.ui.menu;

import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.icon.IconSpec;

/**
 * A tab's second line on the rail: a smaller label under the tab's own, read on {@code .TextSpans}, with an
 * optional picture before it (the Almanac's names the season on now, with the season's picture). A tab asks
 * for its line on every paint ({@link MenuEntry#subline()}), so a line that follows the world is current each
 * time the rail is drawn; no line draws nothing, and the tab keeps its one-line row.
 */
public record MenuSubline(@Nonnull Message label, @Nullable IconSpec icon) {

    public MenuSubline {
        Objects.requireNonNull(label, "label");
    }
}
