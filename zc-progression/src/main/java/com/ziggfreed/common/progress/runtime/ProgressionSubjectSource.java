package com.ziggfreed.common.progress.runtime;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.subject.Subject;

/**
 * How a player becomes the {@link Subject} the ACTIVE stores understand.
 *
 * <p>This is not a formality. A store reaches a player's persisted state through whatever handle its
 * own owner attached to the subject, so a subject built by somebody else reads NEUTRAL through it -
 * no status, no progress - and silently drops every write. A surface that builds its own subject
 * therefore works perfectly on the server it was written against and does nothing at all on a server
 * where another mod's store is the registered one, which is the exact failure this seam removes: ask
 * the runtime, and the subject always matches the store that will be asked about it.
 *
 * <p>Both methods may answer null, and null always means "not this pass" rather than an error - a
 * player whose identity is not readable yet is a normal moment, and every caller treats it as a
 * reason to do nothing.
 *
 * <p><b>Whether a system is switched ON for a player is asked somewhere else.</b> A subject is also
 * what a storefront, a board and a conversation are built over, so answering an owner's "quests
 * off" switch by refusing to build one would take a wallet away along with the quest log. That
 * question is a {@link ProgressionSystemGate} contribution instead, asked per system once the
 * subject exists.
 */
public interface ProgressionSubjectSource {

    /** The subject the quest store understands, or null when there is not enough to build one. */
    @Nullable
    Subject questSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                         @Nonnull PlayerRef playerRef);

    /** The subject the achievement store understands, or null. Often the same object. */
    @Nullable
    Subject achievementSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef);
}
