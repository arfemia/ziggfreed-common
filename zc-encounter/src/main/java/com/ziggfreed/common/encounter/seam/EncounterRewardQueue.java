package com.ziggfreed.common.encounter.seam;

import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * Where a payout for a participant who is offline at the defeat is parked until their next connect:
 * the same replayable-command queue every other library payout retries through. The wiring root
 * fills it with the progression runtime's registered queue, read at each payout because a consumer
 * registers its own store after this library's setup has run.
 *
 * <p>Answer null when this server has no queue; the payout then pays whoever is present and reports
 * once that the rest had nowhere to go.
 */
@FunctionalInterface
public interface EncounterRewardQueue {

    /** The live queue, {@code (subject, replayable command)}, or null when there is none. */
    @Nullable
    BiConsumer<Subject, String> queue();
}
