package com.ziggfreed.common.reputation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Where a player's standing with a reputation is kept: what {@link ReputationService} reads every standing
 * through and writes every change to. Production is {@link TrackReputationLedger}: the reputation's track holds
 * the standing, and the engine's own value is a copy kept in step for NPC attitudes. {@link NativeReputationLedger}
 * keeps it in the engine's own value alone, as the library did before reputations were tracks, and is what a
 * service built with no ledger uses. World thread for every call; {@code ladder} is the reputation's own
 * ({@link ReputationService#ladderFor}).
 */
public interface ReputationLedger {

    /**
     * One player's standing with one reputation.
     *
     * @param earned the current climb's standing: what the bar, the page and a reward's receipt show
     * @param held   the standing every rank reads, and what the engine's copy shows: {@code earned}, or after a
     *               prestige at least the floor of the best rank the player ever reached
     * @param entry  has anything ever been recorded for the player (a move, an import)?
     */
    record Reading(int earned, int held, boolean entry) {

        /** A reading whose current standing is the one its ranks read. */
        @Nonnull
        public static Reading of(int standing, boolean entry) {
            return new Reading(standing, standing, entry);
        }
    }

    /** The live player's standing with {@code def}, or null when it cannot be read (no live player). */
    @Nullable
    Reading read(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull ReputationDef def,
            @Nonnull ReputationLadder ladder);

    /**
     * Move it by {@code delta}, already raised by the worn gain and cut at the Cap: the reading after, or null
     * when nothing could be written.
     */
    @Nullable
    Reading add(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull ReputationDef def,
            @Nonnull ReputationLadder ladder, int delta, @Nonnull String source);

    /** Once the player is in a world: bring what is kept in step (an import, an outside write). Nothing by default. */
    default void settle(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder) {
    }

    /**
     * Where the standing is kept moved outside the service (a reward paying the reputation's track, an admin, a
     * prestige): keep what follows it in step. {@code before} is the standing the move started from, as it was
     * kept before it (what the engine's copy held then, unless something else moved the copy since), or null when
     * the move landed on a fresh climb (nothing on record before it), which the track starts at 0 rather than the
     * reputation's starting value. Nothing by default.
     */
    default void followed(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder, @Nullable Reading before) {
    }
}
