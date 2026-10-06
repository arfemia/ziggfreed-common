package com.ziggfreed.common.ui.menu;

import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;

/**
 * The library's four tabs, in the order the rail lists them under a consumer's section. Each is filled
 * at setup by the module that owns its screen, through {@link ZigMenu#fill}, so this module never sees
 * theirs; an unfilled slot draws nothing.
 */
public enum MenuSlot {

    QUESTS("quests"),
    ACHIEVEMENTS("achievements"),
    ALMANAC("almanac"),
    RECORDS("records");

    private final String id;

    MenuSlot(@Nonnull String id) {
        this.id = id;
    }

    /** The slot's entry id: what a page on that screen names as its selected tab. */
    @Nonnull
    public String id() {
        return id;
    }

    /** This slot's entry, carrying the slot's own id. */
    @Nonnull
    public MenuEntry entry(@Nonnull Message label, @Nullable IconSpec icon, @Nonnull Destination opens,
            @Nonnull Predicate<DestinationContext> visible) {
        return new MenuEntry(id, label, icon, opens, visible);
    }
}
