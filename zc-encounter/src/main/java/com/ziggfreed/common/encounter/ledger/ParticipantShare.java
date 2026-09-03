package com.ziggfreed.common.encounter.ledger;

import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * One participant's standing at the end of a run.
 *
 * @param playerId        who
 * @param playerName      their username as last seen, for a log line or a queued payout
 * @param share           their weighted score against the top contributor's, 0 to 1 (the top reads 1)
 * @param credited        true when the share clears the rule's minimum and so earns a payout; false
 *                        is attempt credit only
 * @param damageDealt     raw damage dealt to the subject
 * @param damageTaken     raw damage taken while a member
 * @param presenceSeconds seconds spent as a member
 * @param died            whether they died during the run
 */
public record ParticipantShare(@Nonnull UUID playerId, @Nonnull String playerName, double share, boolean credited,
                               double damageDealt, double damageTaken, double presenceSeconds, boolean died) {

    /** The share loot is rolled at: the credited share, else nothing. */
    public double lootShare() {
        return credited ? share : 0.0;
    }
}
