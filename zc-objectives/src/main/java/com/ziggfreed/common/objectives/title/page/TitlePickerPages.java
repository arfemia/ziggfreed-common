package com.ziggfreed.common.objectives.title.page;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to the title picker, and the one place a consumer themes it and routes its Back button.
 * A setting about a shared surface is the library's page, so a consumer's own menu LAUNCHES it
 * ({@link #open}) and a server with no such menu has {@code /zigtitle open}. World thread.
 */
public final class TitlePickerPages {

    private static final AtomicReference<Supplier<TitlePickerDeps>> DEPS = new AtomicReference<>();

    private TitlePickerPages() {
    }

    /** Say how the picker is painted and where Back goes; null restores the defaults. Resolved per open. */
    public static void deps(@Nullable Supplier<TitlePickerDeps> supplier) {
        DEPS.set(supplier);
    }

    @Nonnull
    static TitlePickerDeps resolvedDeps() {
        Supplier<TitlePickerDeps> supplier = DEPS.get();
        if (supplier == null) {
            return TitlePickerDeps.DEFAULTS;
        }
        try {
            TitlePickerDeps deps = supplier.get();
            return deps != null ? deps : TitlePickerDeps.DEFAULTS;
        } catch (Throwable t) {
            SafeLog.warn("[title] the picker deps failed to resolve: " + t.getMessage());
            return TitlePickerDeps.DEFAULTS;
        }
    }

    /** Open the picker for {@code player}. True when the screen was taken. */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[title] the picker was asked for by an entity that is not a player");
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new TitlePickerPage(playerRef));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[title] the picker failed to open", t);
            return false;
        }
    }
}
