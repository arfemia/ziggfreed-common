package com.ziggfreed.common.objectives.book;

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
 * The way in to {@link ObjectiveBookPage}, and the one place a consumer says what it adds to the book
 * ({@link ObjectiveBookDeps}: board-managed quests, milestones, extra page blocks and the rest). The
 * book sits in the shared menu frame, so its paint and its rail are the menu's: a consumer's palette or
 * frame paint reaches it through {@code ZigMenu.consumer}.
 *
 * <p>World thread.
 */
public final class ObjectiveBookPages {

    private static final AtomicReference<Supplier<ObjectiveBookDeps>> DEPS =
            new AtomicReference<>();

    private ObjectiveBookPages() {
    }

    /**
     * Say everything a consumer may about the book ({@link ObjectiveBookDeps}: board-managed quests,
     * milestones, page blocks, ...). Call once from a consumer's setup; pass null to go
     * back to the library defaults. Resolved lazily on each open.
     */
    public static void deps(@Nullable Supplier<ObjectiveBookDeps> supplier) {
        DEPS.set(supplier);
    }

    /**
     * The deps in force right now: the registered consumer's, else the library defaults. Guarded. Public so a page
     * outside the book (the NPC quest page) reads a quest through the same consumer seams the book does.
     */
    @Nonnull
    public static ObjectiveBookDeps resolvedDeps() {
        Supplier<ObjectiveBookDeps> supplier = DEPS.get();
        if (supplier == null) {
            return ObjectiveBookDeps.DEFAULTS;
        }
        try {
            ObjectiveBookDeps deps = supplier.get();
            return deps != null ? deps : ObjectiveBookDeps.DEFAULTS;
        } catch (Throwable t) {
            SafeLog.warn("[progression] objective book deps failed to resolve: " + t.getMessage());
            return ObjectiveBookDeps.DEFAULTS;
        }
    }

    /**
     * Open the book for {@code player} on {@code tab} ({@link ObjectiveBookPage#TAB_QUESTS} /
     * {@link ObjectiveBookPage#TAB_ACHIEVEMENTS}; null means quests). True when the screen was
     * taken.
     */
    public static boolean open(@Nullable String tab, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        return open(tab, null, store, ref, player);
    }

    /**
     * Open the book for {@code player} on {@code tab} with the row {@code selectedId} chosen (a quest id on
     * Quests, an achievement id on Achievements; null opens on the tab's landing), in the view that shows it
     * ({@link BookState#opening}). True when the screen was taken.
     */
    public static boolean open(@Nullable String tab, @Nullable String selectedId, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[progression] the objective book was asked for by an entity that is not a player");
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store,
                    new ObjectiveBookPage(playerRef, BookState.opening(tab, selectedId)));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[progression] the objective book failed to open", t);
            return false;
        }
    }
}
