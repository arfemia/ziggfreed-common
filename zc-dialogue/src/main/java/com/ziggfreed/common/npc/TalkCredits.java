package com.ziggfreed.common.npc;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.IEventDispatcher;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one place a conversation becomes CREDIT: who is told, once each, and whether this moment counts
 * at all.
 *
 * <h2>Credit is an authored beat</h2>
 *
 * <p>Nothing here fires because a player interacted with something or because a page opened. A
 * conversation is credited when an author put the beat in it, which is the difference between "talk
 * to the blacksmith" being a step in a story and being a walk-past-and-mash-F counter. What the
 * engine supplies for free is the TARGET, never the trigger: a beat with no target named credits
 * whoever the player is talking to, alias set included, so an author never types an id to credit the
 * character they are already writing for.
 *
 * <h2>The re-trigger window lives HERE</h2>
 *
 * <p>A short per-player, per-id window absorbs a double-click and a page re-render. It sits in front
 * of the sinks rather than inside any one of them because it is a property of the MOMENT: if two
 * sinks disagreed about whether this conversation happened, a quest would tick while the statistic
 * counting the same conversations did not. It is in memory, cleared on disconnect, and never
 * persisted - "talk to anyone" must count again tomorrow.
 *
 * <p>The window is claimed PER ID, so an alias fired beside its primary is de-duped on its own terms.
 *
 * <h2>Sinks, then the event</h2>
 *
 * <p>Every registered {@link TalkCreditSink} runs, each individually guarded, and a failure is
 * recorded against its registration rather than costing the other sinks their credit. Afterwards one
 * {@link NpcTalkedEvent} fires for anything that only wants to watch. {@link #hasAny()} is the cheap
 * pre-check for a caller deciding whether to assemble a credit at all.
 *
 * <p>World thread (a credit carries the caller's live store and refs straight through).
 */
public final class TalkCredits {

    /** How long one credited id stays claimed for a player. Generous enough for a render bounce. */
    private static final long RETRIGGER_WINDOW_MS = 2000L;

    private static final RegistryLedger<TalkCreditSink> LEDGER = new RegistryLedger<>("talk");

    /** {@code playerUuid + "|" + lowercased id} to the moment it was last credited. */
    private static final Map<String, Long> LAST_CREDITED = new ConcurrentHashMap<>();

    private TalkCredits() {
    }

    /**
     * Register {@code sink} under {@code id}, usually your mod's name. Call once from your plugin's
     * {@code setup()}. Registering the same id twice replaces the sink, so a reload does not double
     * the credit.
     */
    public static void register(@Nullable String id, @Nullable String owner, @Nullable TalkCreditSink sink) {
        if (id == null || id.isBlank() || sink == null) {
            return;
        }
        LEDGER.put(id, owner, sink);
    }

    /** Is anything at all listening for credit? The pre-check before building a {@link TalkCredit}. */
    public static boolean hasAny() {
        return !LEDGER.ids().isEmpty();
    }

    /** Every registered sink's owner + failure history, keyed by sink id (an admin read). */
    @Nonnull
    public static Map<String, RegistryLedger.RegistrationInfo> info() {
        return LEDGER.info();
    }

    /**
     * Credit a conversation with whoever {@code npcId} names, resolving the alias set for you.
     * Returns false when there was nothing to credit or the re-trigger window swallowed it.
     */
    public static boolean credit(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef, @Nullable Ref<EntityStore> npcRef, @Nullable String npcId,
            @Nullable String qualifier) {
        if (npcId == null || npcId.isBlank()) {
            return false;
        }
        String primary = npcId.trim();
        List<String> answers = List.copyOf(NpcIdentities.answerSetForPrimary(primary));
        return fire(new TalkCredit(store, playerRef, npcRef, primary, answers, qualifier));
    }

    /**
     * Credit an already-assembled conversation. Returns false when the re-trigger window swallowed
     * it, in which case no sink ran and no event fired.
     *
     * <p>The window is claimed on the PRIMARY id. A caller crediting each alias separately claims each
     * of those in turn through {@link #claim}, which is what keeps an alias from being swallowed by
     * its own primary's window.
     */
    public static boolean fire(@Nonnull TalkCredit credit) {
        UUID playerId = playerIdOf(credit);
        if (playerId == null || credit.npcId().isBlank()) {
            return false;
        }
        if (!claim(playerId, credit.npcId())) {
            return false;
        }
        dispatch(playerId, credit);
        return true;
    }

    /**
     * Tell every sink, then everyone watching. The half AFTER the window has been taken, kept separate
     * so the decision to count a conversation and the act of counting it are not tangled together.
     */
    static void dispatch(@Nonnull UUID playerId, @Nonnull TalkCredit credit) {
        for (String id : LEDGER.ids()) {
            TalkCreditSink sink = LEDGER.get(id);
            if (sink == null) {
                continue;
            }
            try {
                sink.credit(credit);
            } catch (Throwable t) {
                LEDGER.recordFailure(id, t.getMessage());
                SafeLog.warn("[talk] credit sink '" + id + "' failed for npc '" + credit.npcId()
                        + "': " + t.getMessage());
            }
        }
        fireEvent(playerId, credit);
    }

    /**
     * Take this player's re-trigger window for one id, or report that it is still open. Public because
     * a sink crediting an alias set has to claim each id on its own terms, and the window is the same
     * window for all of them.
     */
    public static boolean claim(@Nonnull UUID playerId, @Nonnull String id) {
        if (id.isBlank()) {
            return false;
        }
        String key = playerId + "|" + id.trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Long last = LAST_CREDITED.get(key);
        if (last != null && now - last < RETRIGGER_WINDOW_MS) {
            return false;
        }
        LAST_CREDITED.put(key, now);
        return true;
    }

    /** Disconnect cleanup: drop this player's window entries. */
    public static void clearPlayer(@Nonnull UUID playerId) {
        String prefix = playerId + "|";
        LAST_CREDITED.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Drop every registration and every open window. Tests only. */
    static void clearForTests() {
        LEDGER.clear();
        LAST_CREDITED.clear();
    }

    @Nullable
    private static UUID playerIdOf(@Nonnull TalkCredit credit) {
        try {
            PlayerRef player = credit.player();
            return player == null ? null : player.getUuid();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Tell anything that only wants to watch. Guarded whole: an outbound courtesy must never take a
     * credited quest step down with it.
     */
    private static void fireEvent(@Nonnull UUID playerId, @Nonnull TalkCredit credit) {
        try {
            IEventDispatcher<NpcTalkedEvent, NpcTalkedEvent> dispatcher =
                    HytaleServer.get().getEventBus().dispatchFor(NpcTalkedEvent.class);
            if (dispatcher.hasListener()) {
                dispatcher.dispatch(new NpcTalkedEvent(playerId, credit.npcId(), credit.answersTo(),
                        credit.qualifier()));
            }
        } catch (Throwable t) {
            SafeLog.fine("[talk] could not fire NpcTalked: " + t.getMessage());
        }
    }
}
