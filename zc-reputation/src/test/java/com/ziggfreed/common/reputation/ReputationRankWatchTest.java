package com.ziggfreed.common.reputation;

import static com.ziggfreed.common.reputation.ReputationRankWatch.Trigger.CHANGE;
import static com.ziggfreed.common.reputation.ReputationRankWatch.Trigger.EQUIP;
import static com.ziggfreed.common.reputation.ReputationRankWatch.Trigger.LOGIN;
import static com.ziggfreed.common.reputation.ReputationRankWatch.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.reputation.ReputationRankWatch.Decision;

/**
 * The effective-rank rule (the maintainer's "effective rank, self-healing"): the first check after login
 * is a silent hydrate that credits what is held; a change always re-credits; an equip credits only when
 * the rank moved; only a rise during play is announced.
 */
class ReputationRankWatchTest {

    @Test
    void theRule() {
        assertEquals(new Decision(true, false), decide(null, 3, EQUIP), "no check since login: a silent hydrate");
        assertEquals(new Decision(true, false), decide(2, 3, LOGIN), "a login is always a hydrate");
        assertEquals(new Decision(true, true), decide(2, 3, EQUIP), "gear raised the rank: credit and announce");
        assertEquals(new Decision(true, true), decide(2, 5, CHANGE), "a grant raised it");
        assertEquals(new Decision(false, false), decide(3, 3, EQUIP), "an equip that moves nothing does nothing");
        assertEquals(new Decision(true, false), decide(3, 3, CHANGE), "a change always re-credits, quietly");
        assertEquals(new Decision(true, false), decide(4, 3, EQUIP), "a fall re-credits what is still held, quietly");
    }
}
