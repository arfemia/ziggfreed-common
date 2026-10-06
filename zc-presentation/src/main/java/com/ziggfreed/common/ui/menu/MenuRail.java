package com.ziggfreed.common.ui.menu;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The rail one build painted, held by the page that painted it, so a click (which carries only the
 * row's index under {@link ZigMenu#EVENT_KEY}) opens exactly what was drawn. A page calls
 * {@link #handle} first in {@code handleDataEvent}, before it reads its own action: a rail click carries
 * none.
 */
public final class MenuRail {

    /** Nothing painted (a build with no player): every token is refused and answered. */
    public static final MenuRail EMPTY = new MenuRail(List.of());

    @Nonnull private final List<MenuRow> rows;

    MenuRail(@Nonnull List<MenuRow> rows) {
        this.rows = List.copyOf(rows);
    }

    @Nonnull
    public List<MenuRow> rows() {
        return rows;
    }

    /**
     * Answer a rail click. False when {@code token} is null (the event is the page's own). Otherwise true,
     * having opened the row's screen, or run {@code answer} because nothing took over (a header, a stale
     * index, an entry hidden since the build, a screen that declined), so the client is never left
     * waiting.
     */
    public boolean handle(@Nullable String token, @Nullable Store<EntityStore> store,
            @Nullable Ref<EntityStore> ref, @Nullable Player player, @Nonnull Runnable answer) {
        if (token == null) {
            return false;
        }
        if (!open(token, store, ref, player)) {
            answer.run();
        }
        return true;
    }

    private boolean open(@Nonnull String token, @Nullable Store<EntityStore> store,
            @Nullable Ref<EntityStore> ref, @Nullable Player player) {
        MenuEntry entry = entryAt(token);
        if (entry == null) {
            return false;
        }
        DestinationContext viewer = new DestinationContext(store, ref, player, null, null, null);
        return ZigMenu.visibleGuarded(entry, viewer) && Destinations.open(entry.opens(), viewer);
    }

    @Nullable
    MenuEntry entryAt(@Nonnull String token) {
        int index;
        try {
            index = Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (index < 0 || index >= rows.size()) {
            return null;
        }
        return rows.get(index).entry();
    }
}
