package com.ziggfreed.common.proc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Which RootInteraction a player's hit came from. The engine's damage names only its chain's type
 * ({@code Damage.INTERACTION_TYPE}), so the chain starts are recorded (attacker to root, by chain type, so a guard
 * held up never hides the swing beside it), and a hit reads the latest chain of its own type: while that chain runs,
 * or for a moment after (a hit dispatched the tick its chain ends). Recorded only while a live proc asks
 * {@code Ability.Roots}. A record is dropped when a newer chain of its type replaces it, when it is read stale, and
 * when the attacker leaves. World thread; the outer map is concurrent (a player's records are touched by their own
 * world's thread alone).
 */
final class ChainRoots {

    /** How long after its start an ended chain's root still names a hit of its type. */
    static final long GRACE_NANOS = 2_000_000_000L;

    private record Entry(@Nonnull InteractionChain chain, @Nonnull String root, long startedNanos) {
    }

    private static final Map<Ref<EntityStore>, Map<InteractionType, Entry>> BY_ATTACKER = new ConcurrentHashMap<>();

    private ChainRoots() {
    }

    /** {@code attacker}'s chain started from {@code root}. */
    static void started(@Nonnull Ref<EntityStore> attacker, @Nonnull InteractionChain chain, @Nullable String root,
            long nanos) {
        InteractionType type = chain.getType();
        if (root == null || root.isBlank() || type == null) {
            return;
        }
        BY_ATTACKER.computeIfAbsent(attacker, a -> new ConcurrentHashMap<>()).put(type,
                new Entry(chain, root, nanos));
    }

    /** The root of the chain that dealt {@code damage}, as {@code attacker}'s; null when none is known. */
    @Nullable
    static String rootOf(@Nullable Ref<EntityStore> attacker, @Nullable Damage damage) {
        if (attacker == null || damage == null || BY_ATTACKER.isEmpty()) {
            return null;
        }
        Map<InteractionType, Entry> byType = BY_ATTACKER.get(attacker);
        if (byType == null) {
            return null;
        }
        InteractionType type;
        try {
            type = damage.getIfPresentMetaObject(Damage.INTERACTION_TYPE);
        } catch (Throwable t) {
            return null;
        }
        Entry entry = type == null ? null : byType.get(type);
        if (entry == null) {
            return null;
        }
        if (entry.chain().getServerState() != InteractionState.NotFinished
                && System.nanoTime() - entry.startedNanos() > GRACE_NANOS) {
            byType.remove(type, entry);
            return null;
        }
        return entry.root();
    }

    /** Forget an attacker that left its store. */
    static void forget(@Nonnull Ref<EntityStore> ref) {
        BY_ATTACKER.remove(ref);
    }

    /** Forget everything (a test). */
    static void clear() {
        BY_ATTACKER.clear();
    }
}
