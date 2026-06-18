package com.ziggfreed.common.lobby;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * The outcome of an atomic party {@link MatchmakingQueue#joinParty group-join}. The
 * join is all-or-none: either every member is reserved + added ({@link #ok} true,
 * {@code reason} {@link JoinResult#JOINED}, empty {@code blocked}), or nobody is and
 * {@code blocked} lists the members that prevented it (already engaged, or already
 * sitting in another queue), tagged with the coarse {@link JoinResult} reason.
 *
 * <p>Lets a consumer (a party "Queue" button) tell the owner exactly who to remove or
 * wait on before re-queuing, without inspecting the lobby internals.
 */
public record GroupJoinResult(boolean ok, @Nonnull JoinResult reason, @Nonnull List<UUID> blocked) {

    /** A fully successful group-join. */
    @Nonnull
    public static GroupJoinResult joined() {
        return new GroupJoinResult(true, JoinResult.JOINED, List.of());
    }

    /** A rejected group-join carrying the reason + the offending members. */
    @Nonnull
    public static GroupJoinResult blocked(@Nonnull JoinResult reason, @Nonnull List<UUID> blocked) {
        return new GroupJoinResult(false, reason, List.copyOf(blocked));
    }
}
