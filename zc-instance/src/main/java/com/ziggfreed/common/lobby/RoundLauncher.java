package com.ziggfreed.common.lobby;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;

/**
 * The injected seam that turns a snapshotted party into an actual game session. This
 * is the whole DRY boundary: everything up to "hand a party to the launcher" is the
 * generic lobby; everything the launcher does (resolve a ruleset, spawn an instance
 * world, teleport, bind to a round registry, start the loop) is consumer policy.
 *
 * <p>Kweebec wires {@code (init, party) -> RoundService.getInstance().startChase(init,
 * party, presetId)}; a future hyMMO instance wires its own dungeon builder. The lobby
 * never imports a ruleset, world, or any mod type - only this functional seam.
 *
 * <p>Implementations must NOT block: return a {@link CompletableFuture} that completes
 * when the launch is committed (or fails). The lobby attaches a completion handler but
 * never {@code join()}s. The launcher is invoked off the lobby's lock.
 */
@FunctionalInterface
public interface RoundLauncher {

    /**
     * Launch a session for {@code party} led by {@code initiator}. The party has
     * already been filtered to online, not-already-engaged players, and
     * {@code initiator} is the first still-eligible joiner.
     *
     * @return a future that completes (normally) when the launch commits, or
     *         completes exceptionally on failure; never {@code null}
     */
    @Nonnull
    CompletableFuture<?> launch(@Nonnull UUID initiator, @Nonnull List<UUID> party);
}
