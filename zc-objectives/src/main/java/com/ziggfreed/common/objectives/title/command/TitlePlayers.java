package com.ziggfreed.common.objectives.title.command;

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
import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.util.SafeLog;

/**
 * Who a typed {@code --player} names for the title family, online or not. The first answer wins:
 * <ol>
 *   <li>an online player with exactly that username (any case);</li>
 *   <li>the player who last connected with it, from the title record ({@link ActiveTitles#idOf});</li>
 *   <li>the text read as a player id (a UUID);</li>
 *   <li>an online player whose username starts with it (any case), the engine's own player argument's match;</li>
 *   <li>the engine's profile lookup ({@code ArgTypes.GAME_PROFILE_LOOKUP_ASYNC}), which asks the
 *       profile service and so needs an authenticated server.</li>
 * </ol>
 * The exact and recorded names come before a partial online match, so a name typed for somebody
 * offline never lands on an online player whose name merely starts with it.
 */
final class TitlePlayers {

    /** A player a typed name resolved to: the id their titles are kept under, and the name answers speak. */
    record Named(@Nonnull UUID id, @Nonnull String name) {
    }

    private TitlePlayers() {
    }

    /** Who {@code typed} names, or null (never an exceptional future) when nobody answers to it. */
    @Nonnull
    static CompletableFuture<Named> resolve(@Nonnull String typed) {
        Named known = known(typed);
        return known != null ? CompletableFuture.completedFuture(known) : lookUp(typed);
    }

    /** The first four steps, each answered on this server without asking anybody. */
    @Nullable
    static Named known(@Nonnull String typed) {
        PlayerRef exact = online(typed, NameMatching.EXACT_IGNORE_CASE);
        if (exact != null) {
            return new Named(exact.getUuid(), exact.getUsername());
        }
        UUID recorded = ActiveTitles.idOf(typed);
        if (recorded != null) {
            return new Named(recorded, nameOr(ActiveTitles.usernameOf(recorded), typed));
        }
        UUID literal = uuidOf(typed);
        if (literal != null) {
            return new Named(literal, nameOr(ActiveTitles.usernameOf(literal), typed));
        }
        PlayerRef partial = online(typed, NameMatching.DEFAULT);
        return partial == null ? null : new Named(partial.getUuid(), partial.getUsername());
    }

    /** The last step: the engine's profile lookup. Null when it is unauthenticated, finds nobody or fails. */
    @Nonnull
    private static CompletableFuture<Named> lookUp(@Nonnull String typed) {
        try {
            CompletableFuture<ProfileServiceClient.PublicGameProfile> profile =
                    ArgTypes.GAME_PROFILE_LOOKUP_ASYNC.parse(typed, new ParseResult());
            if (profile == null) {
                return CompletableFuture.completedFuture(null);
            }
            return profile.handle((found, failure) -> found == null || found.getUuid() == null ? null
                    : new Named(found.getUuid(), nameOr(found.getUsername(), typed)));
        } catch (Throwable t) {
            SafeLog.fine("[title] the profile lookup for '" + typed + "' failed: " + t.getMessage());
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

    @Nonnull
    private static String nameOr(@Nullable String name, @Nonnull String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }
}
