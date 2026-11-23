package com.ziggfreed.common.owner;

import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.ziggfreed.common.util.SafeLog;

/**
 * The library's one answer to "is this player one of the server's operators": the game's own operator status, the
 * group {@code /op} puts a player in ({@link HytalePermissionsProvider#GROUP_ADMIN}, the group the engine's
 * {@code /op self} reads through {@link PermissionsModule#getGroupsForUser}), never a permission node, so no grant of a
 * node (not {@code *} either) makes a player an operator here.
 *
 * <p>For the few things an owner screen keeps to the operators alone, because they reach past the server's settings:
 * a console command an owner writes (a boost's start command, a milestone's announcement), which runs with the
 * console's whole power. Asked on every draw and every event, never kept. Fail-closed: no player, no permissions
 * module, or a provider that throws answers no.
 */
public final class ServerOperators {

    private ServerOperators() {
    }

    /** Is {@code playerRef}'s player one of the server's operators right now? No player answers no. */
    public static boolean isOperator(@Nullable PlayerRef playerRef) {
        return playerRef != null && isOperator(playerRef.getUuid());
    }

    /** Is the player {@code uuid} names one of the server's operators right now? No id answers no. */
    public static boolean isOperator(@Nullable UUID uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            PermissionsModule permissions = PermissionsModule.get();
            if (permissions == null) {
                return false;
            }
            return isOperatorGroup(permissions.getGroupsForUser(uuid));
        } catch (Throwable t) {
            SafeLog.warn("[owner] whether a player is a server operator could not be read, so they are not: "
                    + t.getMessage());
            return false;
        }
    }

    /** Does {@code groups} (a player's groups, as the permissions module answers them) hold the operators' group? */
    static boolean isOperatorGroup(@Nullable Set<String> groups) {
        return groups != null && groups.contains(operatorGroup());
    }

    /** The operators' group, as the engine names it. */
    @Nonnull
    static String operatorGroup() {
        return HytalePermissionsProvider.GROUP_ADMIN;
    }
}
