package com.ziggfreed.common.reputation;

import static com.ziggfreed.common.reputation.ReputationRankWatch.Trigger.CHANGE;
import static com.ziggfreed.common.reputation.ReputationRankWatch.Trigger.LOGIN;
import static com.ziggfreed.common.reputation.ReputationRankWatch.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.reputation.ReputationRankWatch.Decision;

/**
 * The effective-rank rule (the maintainer's "effective rank, self-healing"): the first check after login
 * is a silent hydrate that credits what is held; a change always re-credits; only a rise during play is
 * announced.
 */
class ReputationRankWatchTest {

    @Test
    void theRule() {
        assertEquals(new Decision(true, false), decide(null, 3, CHANGE), "no check since login: a silent hydrate");
        assertEquals(new Decision(true, false), decide(2, 3, LOGIN), "a login is always a hydrate");
        assertEquals(new Decision(true, true), decide(2, 5, CHANGE), "a grant raised it");
        assertEquals(new Decision(true, false), decide(3, 3, CHANGE), "a change always re-credits, quietly");
        assertEquals(new Decision(true, false), decide(4, 3, CHANGE), "a fall re-credits what is still held, quietly");
    }
}
