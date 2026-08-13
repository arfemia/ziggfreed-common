package com.ziggfreed.common.dialogue.quest;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.SafeLog;

/**
 * The open table of everyone who can put a conversation on a player's screen.
 *
 * <p>The sibling of the offer table on the other side of a quest's life: that one answers "what does
 * this character have for me", this one answers "who can show me what they say afterwards". A
 * consumer registers its own page routing once at setup, and the shared routing then drives it -
 * which is what lets the hand-off decision live in one place while every UI keeps its own screens.
 *
 * <p>Backed by the shared registry ledger: case-insensitive ids, idempotent per id with last-write
 * wins, per-host owner and failure history through {@link #info()} (the boot diagnostic read). Each
 * host is individually guarded, so one mod's broken page costs its own conversations and nobody
 * else's.
 *
 * <p><b>Nothing is pre-seeded</b>, deliberately: an empty table opens nothing, which is the correct
 * answer on a server with no conversation UI at all, and a caller then simply keeps its own refresh.
 *
 * <p><b>First host that knows it wins.</b> {@link RegistryLedger#ids()} is sorted, so which host that
 * is stays the same across restarts rather than depending on which mod happened to register first.
 *
 * <p>World thread.
 */
public final class QuestDialogueHosts {

    private static final RegistryLedger<QuestDialogueHost> LEDGER = new RegistryLedger<>("quest-dialogue");

    private QuestDialogueHosts() {
    }

    /** Register {@code host} under {@code id}, usually your mod's name. Call once from setup. */
    public static void register(@Nullable String id, @Nullable String owner,
            @Nullable QuestDialogueHost host) {
        if (id == null || id.isBlank() || host == null) {
            return;
        }
        LEDGER.put(id, owner, host);
    }

    /** Is anything registered at all? The pre-check before a surface bothers to route. */
    public static boolean hasAny() {
        return !LEDGER.ids().isEmpty();
    }

    /** Every registered host's owner + failure history, keyed by id (the boot diagnostic + an admin read). */
    @Nonnull
    public static Map<String, RegistryLedger.RegistrationInfo> info() {
        return LEDGER.info();
    }

    /**
     * Can ANYBODY open this conversation? Stops at the first yes, because this runs inside the
     * routing decision on every hand-in.
     */
    public static boolean knows(@Nullable String dialogueId) {
        if (dialogueId == null || dialogueId.isBlank()) {
            return false;
        }
        for (String id : LEDGER.ids()) {
            QuestDialogueHost host = LEDGER.get(id);
            if (host == null) {
                continue;
            }
            try {
                if (host.knows(dialogueId)) {
                    return true;
                }
            } catch (Throwable t) {
                LEDGER.recordFailure(id, t.getMessage());
                SafeLog.warn("[quest-dialogue] host '" + id + "' failed: " + t.getMessage());
            }
        }
        return false;
    }

    /**
     * Hand the conversation to the first host that knows it AND takes the screen. False when none
     * did, in which case the caller still owes the player a response.
     *
     * <p>A host that knows the conversation but declines to open it does not stop the walk: another
     * mod may know the same id and be able to.
     */
    public static boolean open(@Nonnull QuestHandOff handOff, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull Player player) {
        String dialogueId = handOff.dialogueId();
        if (dialogueId == null) {
            return false;
        }
        for (String id : LEDGER.ids()) {
            QuestDialogueHost host = LEDGER.get(id);
            if (host == null) {
                continue;
            }
            try {
                if (host.knows(dialogueId) && host.open(handOff, store, ref, playerRef, player)) {
                    return true;
                }
            } catch (Throwable t) {
                LEDGER.recordFailure(id, t.getMessage());
                SafeLog.warn("[quest-dialogue] host '" + id + "' failed: " + t.getMessage());
            }
        }
        return false;
    }

    /**
     * Drop every registration, leaving a table that opens nothing. For a full content reload, and for
     * a test resetting between cases; the offer table exposes the same.
     */
    public static void clear() {
        LEDGER.clear();
    }
}
