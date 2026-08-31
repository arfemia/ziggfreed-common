package com.ziggfreed.common.dialogue;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

/**
 * The SESSION backend: everything a memory declared {@code "Session": true} remembers, kept in a
 * per-player key set for as long as that player is connected and dropped when they leave.
 *
 * <p>It is the in-memory default this domain was missing, matching {@code InMemoryCommerceStore},
 * {@code InMemoryCounterStore}, {@code InMemoryQuestProgressStore} and
 * {@code InMemoryAchievementProgressStore} - except that this one is not a stand-in for a real
 * store. It IS the real store for one of the two lifetimes an author can declare, which is why it
 * ships in the library rather than being re-derived by whichever mod happened to want it.
 *
 * <p>Keyed by player UUID and static, because the state has to outlive the per-render context that
 * reads it while needing no component, no codec and no saved world. {@link #forgetPlayer} is what a
 * consumer with a shorter window than a login - a minigame round, an instance visit - calls at its
 * own boundary; {@code DialogueBootstrap} already calls it when the player disconnects.
 */
public final class InMemoryDialogueFlagStore {

    private static final Map<UUID, Set<String>> KEYS = new ConcurrentHashMap<>();

    private InMemoryDialogueFlagStore() {
    }

    /** A store view over one player's session keys. Cheap: the set is created on first write. */
    @Nonnull
    public static DialogueFlagStore forPlayer(@Nonnull UUID playerId) {
        return new PlayerView(playerId);
    }

    /**
     * Drop everything this player remembers for the session. Called from {@code DialogueBootstrap} when they
     * disconnect, and callable by a consumer whose own session boundary is shorter than that.
     */
    public static void forgetPlayer(@Nonnull UUID playerId) {
        KEYS.remove(playerId);
    }

    /** Forget every player's session state (a test reset, and shutdown). */
    public static void reset() {
        KEYS.clear();
    }

    /** How many keys this player is currently remembering, for a test and for a diagnostic. */
    public static int size(@Nonnull UUID playerId) {
        Set<String> keys = KEYS.get(playerId);
        return keys == null ? 0 : keys.size();
    }

    private record PlayerView(@Nonnull UUID playerId) implements DialogueFlagStore {

        @Override
        public boolean has(@Nonnull String flag) {
            Set<String> keys = KEYS.get(playerId);
            return keys != null && keys.contains(flag);
        }

        @Override
        public void set(@Nonnull String flag) {
            KEYS.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet()).add(flag);
        }

        @Override
        public void clear(@Nonnull String flag) {
            Set<String> keys = KEYS.get(playerId);
            if (keys != null) {
                keys.remove(flag);
            }
        }

        @Override
        public void clearWithPrefix(@Nonnull String prefix) {
            Set<String> keys = KEYS.get(playerId);
            if (keys != null) {
                keys.removeIf(key -> key.startsWith(prefix));
            }
        }

        @Override
        public void clearAll() {
            KEYS.remove(playerId);
        }
    }
}
