package com.ziggfreed.common.party;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.entity.Allies;

/**
 * The library's own party ({@link ZcParty}) as the party half of zc-core's ally rule ({@link Allies}), installed by
 * the instance bootstrap at setup, so a server running CommonLib alone reads a party's members as allies with no
 * consumer wiring (M513).
 */
public final class PartyAllies {

    private PartyAllies() {
    }

    /** Is {@code other} in {@code player}'s party? Null when {@code player} is in none. */
    @Nullable
    public static Boolean sameGroup(@Nonnull UUID player, @Nonnull UUID other) {
        Party party = ZcParty.service().partyOf(player);
        return party == null ? null : party.contains(other);
    }
}
