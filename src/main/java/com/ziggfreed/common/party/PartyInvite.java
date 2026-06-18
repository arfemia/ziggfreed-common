package com.ziggfreed.common.party;

import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * A pending invite from {@code inviter} to {@code invitee} into party {@code partyId},
 * valid until {@code expiresAtMillis} (0 = no expiry). Ephemeral - lives only in memory
 * on the {@link Party} (no friends list, no persistence).
 */
public record PartyInvite(@Nonnull String partyId, @Nonnull UUID inviter, @Nonnull UUID invitee, long expiresAtMillis) {

    /** True if this invite has expired as of {@code nowMillis}. */
    public boolean isExpired(long nowMillis) {
        return expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
    }
}
