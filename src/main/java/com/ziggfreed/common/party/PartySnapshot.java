package com.ziggfreed.common.party;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * An immutable point-in-time view of a {@link Party}, the only thing a UI page renders
 * from. Carries UUIDs + invites only; usernames are resolved live at render time
 * ({@code Universe.getPlayer}), never carried here (the leaderboard-page pattern).
 */
public record PartySnapshot(@Nonnull String id, @Nonnull String gameId, @Nonnull UUID owner,
                            @Nonnull List<UUID> members, @Nonnull List<PartyInvite> pendingInvites, int maxSize,
                            boolean privateLobby) {

    public int size() {
        return members.size();
    }

    public boolean isOwner(@Nonnull UUID uuid) {
        return owner.equals(uuid);
    }

    public boolean isFull() {
        return members.size() >= maxSize;
    }
}
