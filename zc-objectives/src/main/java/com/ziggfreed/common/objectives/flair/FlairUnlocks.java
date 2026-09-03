package com.ziggfreed.common.objectives.flair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.flair.ZigFlairComponent;
import com.ziggfreed.common.subject.Subject;

/**
 * The ONE write path onto a player's unlocked-flair set ({@link ZigFlairComponent}): unlock,
 * revoke, and read back. The {@code Flair} reward kind, every {@code /zigflair} verb and any
 * consumer's own alias command all come through here, so a flair granted any of those ways is
 * announced the same way - one native event, one notice - and a write that changed nothing
 * announces nothing.
 *
 * <p><b>Why the facade sits above the component.</b> The component is a bare persisted set and
 * belongs to the entity module, which can see neither the event bus contract this library keeps nor
 * the toast engine. This module sees both, so this is where a change becomes a {@link
 * ZigFlairChangedEvent} on the bus (every real change, unlock and revoke alike) and, for a new
 * unlock, a notice through the authored {@code Flair_Unlocked} moment.
 *
 * <p><b>World thread</b>, like every component write: the live forms take the player's own
 * {@code (store, ref)} and their resolved {@link PlayerRef}, so a caller already standing on the
 * world thread hands over what it has. An id is lower-cased at write, exactly as the component
 * does, so an outcome reported here is the component's own.
 */
public final class FlairUnlocks {

    /** What a write did, and whether it was a REAL change (the only kind that is announced). */
    public enum Outcome {

        /** The flair was not there and is now. Announced. */
        UNLOCKED(true),

        /** The player already had it; nothing changed and nothing was announced. */
        ALREADY_UNLOCKED(false),

        /** The flair was there and is now gone. Announced. */
        REVOKED(true),

        /** The player never had it; nothing changed and nothing was announced. */
        NOT_UNLOCKED(false),

        /** The id is blank or carries a character the save format reserves; nothing was written. */
        REFUSED(false),

        /** The player carries no flair record to write to (registration failed, or not attached). */
        NO_RECORD(false);

        private final boolean changed;

        Outcome(boolean changed) {
            this.changed = changed;
        }

        /** True for the two outcomes that changed the set, which are the two that were announced. */
        public boolean changed() {
            return changed;
        }
    }

    private FlairUnlocks() {
    }

    /** Unlock {@code flairId} for the player; a flair they already have is a successful no-op. */
    @Nonnull
    public static Outcome unlock(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull PlayerRef playerRef, @Nullable String flairId) {
        return write(peek(store, ref), subjectOf(store, ref, playerRef), flairId, true);
    }

    /** Take {@code flairId} away from the player; one they never had is a no-op. */
    @Nonnull
    public static Outcome revoke(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                 @Nonnull PlayerRef playerRef, @Nullable String flairId) {
        return write(peek(store, ref), subjectOf(store, ref, playerRef), flairId, false);
    }

    /** Every flair id the player has unlocked, sorted; empty when they carry no record. */
    @Nonnull
    public static List<String> unlocked(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return unlockedOf(peek(store, ref));
    }

    /**
     * The player's flair record, or null when the component type never registered or this entity
     * does not carry one. A consumer that only READS flairs peeks the same way.
     */
    @Nullable
    public static ZigFlairComponent peek(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        return ZigFlairComponent.TYPE == null ? null : store.getComponent(ref, ZigFlairComponent.TYPE);
    }

    // ==================== the write itself ====================

    /**
     * The write, over the record and the subject it belongs to: the part every live form shares and
     * a test drives directly. Refuses before it touches the set, announces only a real change.
     */
    @Nonnull
    static Outcome write(@Nullable ZigFlairComponent flairs, @Nonnull Subject who,
                         @Nullable String flairId, boolean unlock) {
        if (flairs == null) {
            return Outcome.NO_RECORD;
        }
        if (flairId == null || flairId.isBlank() || ZigFlairComponent.usesReservedDelimiter(flairId)) {
            return Outcome.REFUSED;
        }
        String id = flairId.trim().toLowerCase(Locale.ROOT);
        boolean changed = unlock ? flairs.unlock(id) : flairs.revoke(id);
        if (!changed) {
            return unlock ? Outcome.ALREADY_UNLOCKED : Outcome.NOT_UNLOCKED;
        }
        FlairEvents.fireChanged(who, id, unlock);
        if (unlock) {
            FlairText.announceUnlocked(who, id);
        }
        return unlock ? Outcome.UNLOCKED : Outcome.REVOKED;
    }

    /** The sorted ids in a record; empty for none. */
    @Nonnull
    static List<String> unlockedOf(@Nullable ZigFlairComponent flairs) {
        if (flairs == null || flairs.unlockedFlairs == null || flairs.unlockedFlairs.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(flairs.unlockedFlairs);
        Collections.sort(ids);
        return ids;
    }

    /**
     * The subject a live write is announced for: the player's id and name, with a handle that
     * answers {@link PlayerRef} (the event and the notice both want it) and {@link Player} (a
     * reward-shaped reader may). Built here rather than asked of the progression runtime, so a
     * flair write does not depend on that runtime being built.
     */
    @Nonnull
    private static Subject subjectOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                     @Nonnull PlayerRef playerRef) {
        UUID id = playerRef.getUuid();
        String name = playerRef.getUsername();
        Player player = store.getComponent(ref, Player.getComponentType());
        return new Subject(id, name == null ? "" : name, new LiveHandle(playerRef, player));
    }

    /** The two live handles a flair write has, offered by type. */
    private record LiveHandle(@Nonnull PlayerRef playerRef, @Nullable Player player)
            implements Subject.HandleFacets {

        @Override
        @Nullable
        public Object facet(@Nonnull Class<?> type) {
            if (type == PlayerRef.class) {
                return playerRef;
            }
            return type == Player.class ? player : null;
        }
    }
}
