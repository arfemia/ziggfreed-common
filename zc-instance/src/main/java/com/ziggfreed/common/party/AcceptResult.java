package com.ziggfreed.common.party;

/** The outcome of a {@link PartyService#accept} attempt, for consumer feedback. */
public enum AcceptResult {

    /** The invitee joined the party. */
    JOINED,
    /** No live invite to that party exists for the invitee. */
    NO_INVITE,
    /** The invite had expired. */
    EXPIRED,
    /** The party filled up before the invite was accepted. */
    PARTY_FULL,
    /** The invitee is already in a party. */
    ALREADY_IN_PARTY;

    public boolean ok() {
        return this == JOINED;
    }
}
