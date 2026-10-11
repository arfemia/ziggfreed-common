package com.ziggfreed.common.instance.claims.command;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.auth.ProfileServiceClient;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ziggfreed.common.util.SafeLog;

/**
 * Who a typed {@code --player} names for {@code /zigclaims}, online or not, since a Claim can wait for a player
 * who is away. The first answer wins: an online player with exactly that username (any case); the text read as
 * a player id (a UUID); an online player whose username starts with it (the engine's own player argument's
 * match); the engine's profile lookup, which asks the profile service and so needs an authenticated server.
 * The exact name comes before a partial match, so a name typed for somebody offline never lands on an online
 * player whose name merely starts with it.
 */
final class ClaimTargets {

    /** A player a typed name resolved to: whose Claims to write, and the name the answers speak. */
    record Named(@Nonnull UUID id, @Nonnull String name) {
    }

    private ClaimTargets() {
    }

    /** Who {@code typed} names, or null (never an exceptional future) when nobody answers to it. */
    @Nonnull
    static CompletableFuture<Named> resolve(@Nonnull String typed) {
        PlayerRef exact = online(typed, NameMatching.EXACT_IGNORE_CASE);
        if (exact != null) {
            return CompletableFuture.completedFuture(new Named(exact.getUuid(), exact.getUsername()));
        }
        UUID literal = uuidOf(typed);
        if (literal != null) {
            return CompletableFuture.completedFuture(new Named(literal, typed));
        }
        PlayerRef partial = online(typed, NameMatching.DEFAULT);
        if (partial != null) {
            return CompletableFuture.completedFuture(new Named(partial.getUuid(), partial.getUsername()));
        }
        return lookUp(typed);
    }

    /** The engine's profile lookup. Null when it is unauthenticated, finds nobody or fails. */
    @Nonnull
    private static CompletableFuture<Named> lookUp(@Nonnull String typed) {
        try {
            CompletableFuture<ProfileServiceClient.PublicGameProfile> profile =
                    ArgTypes.GAME_PROFILE_LOOKUP_ASYNC.parse(typed, new ParseResult());
            if (profile == null) {
                return CompletableFuture.completedFuture(null);
            }
            return profile.handle((found, failure) -> found == null || found.getUuid() == null ? null
                    : new Named(found.getUuid(), found.getUsername() == null ? typed : found.getUsername()));
        } catch (Throwable t) {
            SafeLog.fine("[claims] the profile lookup for '" + typed + "' failed: " + t.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    @Nullable
    private static PlayerRef online(@Nonnull String typed, @Nonnull NameMatching matching) {
        try {
            Universe universe = Universe.get();
            return universe == null ? null : universe.getPlayerByUsername(typed, matching);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static UUID uuidOf(@Nonnull String typed) {
        try {
            return UUID.fromString(typed.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
