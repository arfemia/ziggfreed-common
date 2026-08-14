package com.ziggfreed.common.objectives.questlist;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.util.SafeLog;

/**
 * The way in to {@link ZigNpcQuestPage}: one call that opens a character's quest list, and one place
 * a consumer says what it wants that page to know.
 *
 * <p><b>This class is the whole of the wiring root's job.</b> {@link #open(String, Store, Ref,
 * PlayerRef, Player)} is written to the exact shape the quest-list host seam expects, so registering
 * this page as the default screen the shared {@code Quests} destination opens is a method reference
 * and nothing else:
 *
 * <pre>{@code
 * NpcQuestListHosts.register("ziggfreedcommon", "ziggfreedcommon", NpcQuestPages::open);
 * }</pre>
 *
 * <p>That indirection is deliberate rather than incidental: the host interface lives in the dialogue
 * module, which sits BESIDE this one in the graph rather than under it, so implementing it here would
 * be an edge this module may not have. The root is the one place both are visible, and a method
 * reference carries no logic for it to hold.
 *
 * <p><b>Deps are resolved LAZILY, at open time.</b> A consumer's naming, theme and routing are built
 * long after this module's setup runs, so a supplier is registered once and asked on each open; a
 * consumer that registers nothing gets {@link NpcQuestPageDeps#DEFAULTS}, which is a fully working
 * page.
 *
 * <p>World thread.
 */
public final class NpcQuestPages {

    /** Who this page's registrations are attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    private static final AtomicReference<Supplier<NpcQuestPageDeps>> DEPS = new AtomicReference<>();

    private NpcQuestPages() {
    }

    /**
     * Say what this page should know about a consumer's world. Call once from that consumer's setup;
     * pass null to go back to the library defaults.
     */
    public static void deps(@Nullable Supplier<NpcQuestPageDeps> supplier) {
        DEPS.set(supplier);
    }

    /**
     * The deps in force right now: the registered consumer's, else the library defaults. Guarded, so
     * a supplier that throws or answers null costs the consumer's own contributions rather than the
     * screen.
     */
    @Nonnull
    public static NpcQuestPageDeps resolvedDeps() {
        Supplier<NpcQuestPageDeps> supplier = DEPS.get();
        if (supplier == null) {
            return NpcQuestPageDeps.DEFAULTS;
        }
        try {
            NpcQuestPageDeps deps = supplier.get();
            return deps != null ? deps : NpcQuestPageDeps.DEFAULTS;
        } catch (Throwable t) {
            SafeLog.warn("[progression] npc quest page deps failed to resolve: " + t.getMessage());
            return NpcQuestPageDeps.DEFAULTS;
        }
    }

    /**
     * Open {@code npcId}'s quest list on {@code ref}, with nothing singled out.
     *
     * <p>This is the host-seam shape: a null {@code npcId} means the moment genuinely had nobody in
     * front of the player, which this page serves as their own carried list rather than declining.
     * True means the screen was taken.
     */
    public static boolean open(@Nullable String npcId, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull Player player) {
        return open(npcId, null, store, ref, playerRef, player);
    }

    /**
     * Open {@code npcId}'s quest list with {@code highlightQuestId} singled out: pinned to the top of
     * the list and already open in the detail panel.
     *
     * <p>This is what a routed hand-in calls. A ready quest never takes over a conversation by
     * itself, so the beat that surfaces one routes HERE naming it, and the player lands looking at
     * the quest they pressed rather than at whichever row happened to sort first.
     */
    public static boolean open(@Nullable String npcId, @Nullable String highlightQuestId,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef, @Nonnull Player player) {
        try {
            player.getPageManager().openCustomPage(ref, store,
                    new ZigNpcQuestPage(playerRef, npcId, highlightQuestId, resolvedDeps()));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[progression] the npc quest page failed to open", t);
            return false;
        }
    }
}
