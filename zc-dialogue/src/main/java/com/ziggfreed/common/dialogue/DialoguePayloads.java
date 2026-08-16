package com.ziggfreed.common.dialogue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * The process-wide way a mod says how to build ITS own dialogue payload for any player, so its
 * actions and conditions still answer correctly in a conversation somebody else opened.
 *
 * <p>A payload is the domain state a registered handler or evaluator reads through
 * {@link DialogueContext#payload(Class)} - a quest component, a skill component, a session handle.
 * The mod that opens a conversation packs its own payload into the context it builds, which is right
 * while only one mod is talking. On a server running two, the character in front of the player might
 * be somebody else's, and a mod whose payload is missing does not fail loudly: its gate reads as
 * locked, its quest reads as never started, its hand-in quietly does nothing.
 *
 * <p>Registering here closes that. A supplier is asked for by payload CLASS, so every mod's payload
 * is reachable in every conversation on the server and nothing has to be wired in the other
 * direction. Contributions stack; a class is claimed by its first registration and a second one is
 * reported rather than silently taking it over.
 *
 * <p>Register once, from your plugin's {@code setup()}, beside the actions and conditions that read
 * the payload. A supplier is called on the world thread with the talking player's handles, and a
 * throw or a null answer degrades to "no payload", exactly as before.
 */
public final class DialoguePayloads {

    /** Builds one mod's payload for the player a conversation is being shown to. */
    @FunctionalInterface
    public interface DialoguePayloadSupplier<T> {

        /** The payload for this player, or null when this mod has nothing to say about them. */
        @Nullable
        T supply(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                 @Nonnull PlayerRef playerRef, @Nonnull Player player);
    }

    @Nonnull
    private static final RegistryLedger<DialoguePayloadSupplier<?>> LEDGER =
            new RegistryLedger<>("dialogue-payload");

    /** A supplier that throws does so on every render, so the failure is worth one log entry. */
    private static final Set<String> WARNED_FAILURES = ConcurrentHashMap.newKeySet();

    private DialoguePayloads() {
    }

    /**
     * Register how {@code type} is built for any player, attributed to {@code owner} (the
     * contributing mod's name). Call once per payload class from that mod's {@code setup()}.
     *
     * <p>Re-registering the SAME supplier instance is silent, so a mod re-running its own setup costs
     * nothing; a DIFFERENT one for a class somebody already claimed is reported and ignored, because
     * two answers to "what is this mod's payload" is a contradiction rather than a contribution.
     */
    public static <T> void register(@Nullable Class<T> type, @Nullable String owner,
            @Nullable DialoguePayloadSupplier<T> supplier) {
        if (type == null || supplier == null) {
            return;
        }
        // Keyed by the class's own binary name through the ledger, which normalizes case: two
        // payload classes whose fully-qualified names differ ONLY in case would share a slot, which
        // Java naming makes unreachable in practice and is worth a sentence rather than a guard.
        LEDGER.putIfAbsent(type.getName(), owner, supplier);
    }

    /**
     * Build the registered payload of {@code type} for this player, or null when nobody registered
     * one, the registered one had nothing to say, or it failed (the failure counts against its
     * owner and is logged once per class).
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> T resolve(@Nullable Class<T> type, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull Player player) {
        if (type == null) {
            return null;
        }
        String id = type.getName();
        DialoguePayloadSupplier<?> supplier = LEDGER.get(id);
        if (supplier == null) {
            return null;
        }
        try {
            Object built = ((DialoguePayloadSupplier<T>) supplier).supply(store, ref, playerRef, player);
            return type.isInstance(built) ? type.cast(built) : null;
        } catch (Throwable t) {
            LEDGER.recordFailure(id, String.valueOf(t.getMessage()));
            if (WARNED_FAILURES.add(id)) {
                SafeLog.warn("[dialogue-payload] '" + id + "' failed to build: " + t.getMessage());
            }
            return null;
        }
    }

    /** Is a supplier registered for {@code type}? */
    public static boolean isRegistered(@Nullable Class<?> type) {
        return type != null && LEDGER.isRegistered(type.getName());
    }

    /** Every registered payload class, sorted (diagnostics, a validator hint). */
    @Nonnull
    public static List<String> ids() {
        return List.copyOf(LEDGER.ids());
    }

    /** Every registered class's owner + failure history (an admin registry listing). */
    @Nonnull
    public static Map<String, RegistryLedger.RegistrationInfo> info() {
        return LEDGER.info();
    }

    /** Drop every registration. Tests only; a live server registers once and never withdraws. */
    public static void resetForTests() {
        LEDGER.clear();
        WARNED_FAILURES.clear();
    }
}
