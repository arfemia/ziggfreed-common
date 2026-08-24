package com.ziggfreed.common.objectives.admin;

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
 * The way in to {@link ProgressionAdminPage}, and the one place a consumer says who may open it
 * and how it is painted.
 *
 * <p>Deliberately NOT a registered destination: an admin screen must not be pack-addressable, so
 * the only route here is this direct static call from a consumer's own gated admin surface. And
 * unlike every other shared page, the unfilled page is a page NOBODY can open: the deps' audience
 * seam defaults to DENY, and {@link #open} refuses unless the registered audience passes - the
 * library cannot know what "is an admin" means on a given server, so it refuses rather than
 * guessing. A throwing audience denies too (fail-closed).
 *
 * <p>World thread.
 */
public final class ProgressionAdminPages {

    private static final AtomicReference<Supplier<ProgressionAdminDeps>> DEPS =
            new AtomicReference<>();

    private ProgressionAdminPages() {
    }

    /**
     * Say who may open the page, how Back routes, and how the frame is painted
     * ({@link ProgressionAdminDeps}). Call once from a consumer's setup; pass null to go back to
     * the library defaults (which deny every open). Resolved lazily on each open.
     */
    public static void deps(@Nullable Supplier<ProgressionAdminDeps> supplier) {
        DEPS.set(supplier);
    }

    /** The deps in force right now: the registered consumer's, else the deny-all defaults. Guarded. */
    @Nonnull
    static ProgressionAdminDeps resolvedDeps() {
        Supplier<ProgressionAdminDeps> supplier = DEPS.get();
        if (supplier == null) {
            return ProgressionAdminDeps.DEFAULTS;
        }
        try {
            ProgressionAdminDeps deps = supplier.get();
            return deps != null ? deps : ProgressionAdminDeps.DEFAULTS;
        } catch (Throwable t) {
            SafeLog.warn("[progression-admin] the admin page deps failed to resolve: "
                    + t.getMessage());
            return ProgressionAdminDeps.DEFAULTS;
        }
    }

    /**
     * Open the admin page for {@code player}, audience-checked. True when the screen was taken;
     * false when the audience refused (the default), the entity is not a player, or the open threw.
     */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        ProgressionAdminDeps deps = resolvedDeps();
        if (!deps.mayOpenGuarded(store, ref, player)) {
            SafeLog.fine("[progression-admin] the admin page was refused by the audience seam");
            return false;
        }
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[progression-admin] the admin page was asked for by an entity that is not a player");
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new ProgressionAdminPage(playerRef));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[progression-admin] the admin page failed to open", t);
            return false;
        }
    }
}
