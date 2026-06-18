package com.ziggfreed.common.party;

/** The outcome of a {@link PartyService#invite} attempt, for consumer feedback. */
public enum InviteResult {

    /** The invite was sent. */
    SENT,
    /** The inviter tried to invite themselves. */
    SELF,
    /** Only the owner may invite and the inviter is not the owner. */
    NOT_OWNER,
    /** The party is already at {@code maxSize}. */
    PARTY_FULL,
    /** The target is not currently online. */
    TARGET_OFFLINE,
    /** The target is already in a party (this one or another). */
    TARGET_IN_PARTY,
    /** The target already has a live pending invite to this party. */
    ALREADY_INVITED;

    public boolean ok() {
        return this == SENT;
    }
}
