package com.ziggfreed.common.dialogue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one-line note a conversation shows under the speaker's name, and the vocabulary of where such
 * a line can come from.
 *
 * <p>A conversation names the sources it wants, in order, and the first one with something to say is
 * the line drawn:
 *
 * <pre>{@code
 * "Header": ["ActiveObjective"]
 * }</pre>
 *
 * <p>Naming none - which is the default - shows no header at all, so a character who should never
 * mention quests simply says nothing about them.
 *
 * <p><b>The vocabulary is additive.</b> The library ships {@code ActiveObjective}; any mod
 * contributes another with {@link #register}, attributed to its owner, and a conversation names it
 * the same way. Nothing has to be wired in the other direction and no mod's sources are hidden from
 * another mod's conversation, which is what lets one server's characters draw on all of them. A name
 * is claimed by its first registration; a second one for a name somebody already holds is reported
 * rather than silently taking it over.
 *
 * <p>A source is asked on the world thread with the reading player's handles and the character they
 * are standing at. It answers null for "nothing to say", which is the ordinary case and costs the
 * conversation nothing; a source that throws is reported once and treated the same way.
 */
public final class DialogueHeaders {

    /** Builds one header line for the player a conversation is being shown to. */
    @FunctionalInterface
    public interface HeaderSource {

        /**
         * The line to draw, or null when this source has nothing to say about this player here.
         *
         * @param contextNpcId the character the conversation is with, when it is with one
         */
        @Nullable
        Message lineFor(@Nullable String contextNpcId, @Nonnull Ref<EntityStore> ref,
                @Nonnull Store<EntityStore> store);
    }

    @Nonnull
    private static final RegistryLedger<HeaderSource> LEDGER = new RegistryLedger<>("dialogue-header");

    /** A source that throws does so on every render, so the failure is worth one log entry. */
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    private DialogueHeaders() {
    }

    /**
     * Contribute a header source under {@code name}, attributed to {@code owner} (the contributing
     * mod's name). Call once per source from that mod's {@code setup()}, before assets load.
     *
     * @return true when this call claimed the name; false when another mod already held it
     */
    public static boolean register(@Nullable String name, @Nullable String owner,
            @Nullable HeaderSource source) {
        if (name == null || name.isBlank() || source == null) {
            return false;
        }
        return LEDGER.putIfAbsent(RegistryLedger.normalize(name), owner, source);
    }

    /** Which mod contributed each source name (an admin listing). */
    @Nonnull
    public static Map<String, RegistryLedger.RegistrationInfo> info() {
        return LEDGER.info();
    }

    /**
     * The header for this conversation: the first source it names that has something to say, or null
     * when it names none, none of them answer, or there is no player behind the screen.
     */
    @Nullable
    public static Message lineFor(@Nonnull NpcDialogue dialogue, @Nullable String contextNpcId,
            @Nullable Ref<EntityStore> ref, @Nullable Store<EntityStore> store) {
        if (ref == null || store == null) {
            return null;
        }
        List<String> names = dialogue.getHeaderSources();
        for (String name : names) {
            Message line = ask(name, contextNpcId, ref, store);
            if (line != null) {
                return line;
            }
        }
        return null;
    }

    @Nullable
    private static Message ask(@Nullable String name, @Nullable String contextNpcId,
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = RegistryLedger.normalize(name);
        HeaderSource source = LEDGER.get(key);
        if (source == null) {
            if (WARNED.putIfAbsent("missing:" + key, Boolean.TRUE) == null) {
                SafeLog.warn("[dialogue] a conversation asks for the header source '" + name
                        + "', which nothing on this server contributes, so it draws no line");
            }
            return null;
        }
        try {
            return source.lineFor(contextNpcId, ref, store);
        } catch (Throwable t) {
            if (WARNED.putIfAbsent("threw:" + key, Boolean.TRUE) == null) {
                SafeLog.warn("[dialogue] the header source '" + name + "' failed, so it draws no line: "
                        + t.getMessage());
            }
            return null;
        }
    }

    /** Forget every source, which is the unregistered state a test starts from. */
    public static void resetForTests() {
        LEDGER.clear();
        WARNED.clear();
    }
}
