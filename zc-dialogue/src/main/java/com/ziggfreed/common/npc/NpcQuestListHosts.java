package com.ziggfreed.common.npc;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.SafeLog;

/**
 * The open table of everyone who can show a player what a character has to offer.
 *
 * <p>It is what the generic {@code Quests} destination routes through, so a conversation option or a
 * placement authoring {@code "Open": "Quests"} works on any server whose quest UI registered a host,
 * and stays inert on one with no quest UI at all rather than promising a screen nobody can paint.
 *
 * <p>Backed by the shared registry ledger: case-insensitive ids, idempotent per id with last-write
 * wins, per-host owner and failure history through {@link #info()}. Each host is individually
 * guarded, so one mod's broken page costs its own opens and nobody else's.
 *
 * <p><b>Nothing is pre-seeded</b>, deliberately: an empty table opens nothing, which is the correct
 * answer on a server with no quest UI, and the caller then keeps its own response.
 *
 * <p><b>First host that takes the screen wins.</b> {@link RegistryLedger#ids()} is sorted, so which
 * host that is stays the same across restarts rather than depending on registration order.
 *
 * <p>World thread.
 */
public final class NpcQuestListHosts {

    private static final RegistryLedger<NpcQuestListHost> LEDGER = new RegistryLedger<>("npc-quests");

    private NpcQuestListHosts() {
    }

    /** Register {@code host} under {@code id}, usually your mod's name. Call once from setup. */
    public static void register(@Nullable String id, @Nullable String owner, @Nullable NpcQuestListHost host) {
        if (id == null || id.isBlank() || host == null) {
            return;
        }
        LEDGER.put(id, owner, host);
    }

    /** Is anything registered at all? The pre-check before a surface bothers to route. */
    public static boolean hasAny() {
        return !LEDGER.ids().isEmpty();
    }

    /** Every registered host's owner + failure history, keyed by id (a boot diagnostic, an admin read). */
    @Nonnull
    public static Map<String, RegistryLedger.RegistrationInfo> info() {
        return LEDGER.info();
    }

    /**
     * Hand the list to the first host that takes the screen. False when none did, in which case the
     * caller still owes the player a response.
     */
    public static boolean open(@Nullable String npcId, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        return open(npcId, null, store, ref, player);
    }

    /**
     * The same, with ONE quest called out: what a conversation's quest row sends the player here
     * about, so the quest they were just told about is the row already in front of them. A host that
     * cannot single a row out still shows the plain list.
     */
    public static boolean open(@Nullable String npcId, @Nullable String highlightQuestId,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull Player player) {
        for (String id : LEDGER.ids()) {
            NpcQuestListHost host = LEDGER.get(id);
            if (host == null) {
                continue;
            }
            try {
                if (host.open(npcId, highlightQuestId, store, ref, player)) {
                    return true;
                }
            } catch (Throwable t) {
                LEDGER.recordFailure(id, t.getMessage());
                SafeLog.warn("[npc-quests] host '" + id + "' failed: " + t.getMessage());
            }
        }
        return false;
    }

    /** Drop every registration, leaving a table that opens nothing. A content reload, and tests. */
    public static void clear() {
        LEDGER.clear();
    }
}
