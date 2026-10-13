package com.ziggfreed.common.objectives.title.command;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.auth.ProfileServiceClient;
import com.hypixel.hytale.server.core.auth.ServerAuthManager;
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
 *   <li>the profile service's player with exactly that username, which needs an authenticated server;</li>
 *   <li>last, an online player whose username starts with it (any case), the engine's own player
 *       argument's match.</li>
 * </ol>
 * Every exact answer comes before the partial match, so a full name typed for somebody offline never
 * lands on an online player whose name merely starts with it. The profile service is asked directly,
 * never through the engine's {@code GAME_PROFILE_LOOKUP_ASYNC} argument, which tries an online
 * starts-with match first: the order {@code /zigclaims}' {@code ClaimTargets} keeps.
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
        return known != null ? CompletableFuture.completedFuture(known)
                : lookUp(typed).thenApply(found -> found != null ? found : partial(typed));
    }

    /** The first three steps, each an exact answer from this server without asking anybody. */
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
        return null;
    }

    /** The last step: an online player whose username starts with {@code typed}, or null. */
    @Nullable
    private static Named partial(@Nonnull String typed) {
        PlayerRef partial = online(typed, NameMatching.DEFAULT);
        return partial == null ? null : new Named(partial.getUuid(), partial.getUsername());
    }

    /**
     * The profile service's player with exactly the username {@code typed}, asked off the calling thread. Null
     * when the server holds no session (an offline-auth server), the service finds nobody, or the call fails.
     */
    @Nonnull
    private static CompletableFuture<Named> lookUp(@Nonnull String typed) {
        try {
            ServerAuthManager auth = ServerAuthManager.getInstance();
            String token = auth == null ? null : auth.getSessionToken();
            if (token == null) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<ProfileServiceClient.PublicGameProfile> profile =
                    auth.getProfileServiceClient().getProfileByUsernameAsync(typed.trim(), token);
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
