package com.ziggfreed.common.npc.placement.admin;

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
 * The way in to {@link NpcPlacementAdminPage}, and the one place a consumer says who may open it
 * and how it is painted.
 *
 * <p>Deliberately NOT a registered destination: an admin screen must not be pack-addressable, so a
 * placement could never point press-F at this page. The only route here is this direct static call
 * from a consumer's own gated admin surface, and the deps' audience seam defaults to DENY - the
 * library cannot know what "is an admin" means on a given server, so it refuses rather than
 * guessing. A throwing audience denies too.
 *
 * <p>World thread.
 */
public final class NpcPlacementAdminPages {

    private static final AtomicReference<Supplier<NpcPlacementAdminDeps>> DEPS =
            new AtomicReference<>();

    private NpcPlacementAdminPages() {
    }

    /**
     * Say who may open the page, how Back routes, and how the frame is painted
     * ({@link NpcPlacementAdminDeps}). Call once from a consumer's setup; pass null to go back to
     * the library defaults (which deny every open). Resolved lazily on each open.
     */
    public static void deps(@Nullable Supplier<NpcPlacementAdminDeps> supplier) {
        DEPS.set(supplier);
    }

    /** The deps in force right now: the registered consumer's, else the deny-all defaults. Guarded. */
    @Nonnull
    static NpcPlacementAdminDeps resolvedDeps() {
        Supplier<NpcPlacementAdminDeps> supplier = DEPS.get();
        if (supplier == null) {
            return NpcPlacementAdminDeps.DEFAULTS;
        }
        try {
            NpcPlacementAdminDeps deps = supplier.get();
            return deps != null ? deps : NpcPlacementAdminDeps.DEFAULTS;
        } catch (Throwable t) {
            SafeLog.warn("[placement-admin] the admin page deps failed to resolve: " + t.getMessage());
            return NpcPlacementAdminDeps.DEFAULTS;
        }
    }

    /**
     * Open the admin page for {@code player}, audience-checked. True when the screen was taken;
     * false when the audience refused (the default), the entity is not a player, or the open threw.
     */
    public static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        NpcPlacementAdminDeps deps = resolvedDeps();
        if (!deps.mayOpenGuarded(store, ref, player)) {
            SafeLog.fine("[placement-admin] the admin page was refused by the audience seam");
            return false;
        }
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[placement-admin] the admin page was asked for by an entity that is not a player");
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store,
                    new NpcPlacementAdminPage(playerRef, ""));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[placement-admin] the admin page failed to open", t);
            return false;
        }
    }
}
