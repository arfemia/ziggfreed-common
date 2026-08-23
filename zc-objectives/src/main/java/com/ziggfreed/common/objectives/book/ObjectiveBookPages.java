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
import com.ziggfreed.common.objectives.questlist.NpcQuestPageDeps;
import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to {@link ObjectiveBookPage}, and the one place a consumer says how the book should
 * be painted.
 *
 * <p>The book is fully working with nothing registered - the plain append is the default - and a
 * consumer shipping a theme registers a supplier once at setup, resolved lazily on each open, the
 * same contract {@link com.ziggfreed.common.objectives.questlist.NpcQuestPages} keeps for the NPC
 * quest list. The seam TYPE is that page's own {@link NpcQuestPageDeps.PageTheme}, shared
 * deliberately: one theme signature, however many shared pages a consumer paints.
 *
 * <p>World thread.
 */
public final class ObjectiveBookPages {

    private static final AtomicReference<Supplier<NpcQuestPageDeps.PageTheme>> THEME =
            new AtomicReference<>();

    private static final AtomicReference<Supplier<ObjectiveBookDeps>> DEPS =
            new AtomicReference<>();

    private ObjectiveBookPages() {
    }

    /**
     * Say how this book's frame is painted. Call once from a consumer's setup; pass null to go
     * back to the plain append. The narrow, theme-only registration - a consumer with more to say
     * registers {@link #deps(Supplier)} instead, whose default theme falls through to this one.
     */
    public static void theme(@Nullable Supplier<NpcQuestPageDeps.PageTheme> supplier) {
        THEME.set(supplier);
    }

    /**
     * Say everything a consumer may about the book ({@link ObjectiveBookDeps}: rail, side panel,
     * board-managed quests, milestones, ...). Call once from a consumer's setup; pass null to go
     * back to the library defaults. Resolved lazily on each open.
     */
    public static void deps(@Nullable Supplier<ObjectiveBookDeps> supplier) {
        DEPS.set(supplier);
    }

    /** The deps in force right now: the registered consumer's, else the library defaults. Guarded. */
    @Nonnull
    static ObjectiveBookDeps resolvedDeps() {
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

    /** The theme in force right now: the registered consumer's, else the plain append. Guarded. */
    @Nonnull
    static NpcQuestPageDeps.PageTheme resolvedTheme() {
        Supplier<NpcQuestPageDeps.PageTheme> supplier = THEME.get();
        if (supplier == null) {
            return NpcQuestPageDeps.PLAIN_THEME;
        }
        try {
            NpcQuestPageDeps.PageTheme theme = supplier.get();
            return theme != null ? theme : NpcQuestPageDeps.PLAIN_THEME;
        } catch (Throwable t) {
            SafeLog.warn("[progression] objective book theme failed to resolve: " + t.getMessage());
            return NpcQuestPageDeps.PLAIN_THEME;
        }
    }

    /**
     * Open the book for {@code player} on {@code tab} ({@link ObjectiveBookPage#TAB_QUESTS} /
     * {@link ObjectiveBookPage#TAB_ACHIEVEMENTS}; null means quests). True when the screen was
     * taken.
     */
    public static boolean open(@Nullable String tab, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[progression] the objective book was asked for by an entity that is not a player");
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new ObjectiveBookPage(playerRef, tab));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[progression] the objective book failed to open", t);
            return false;
        }
    }
}
