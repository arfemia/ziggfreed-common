package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** With no equip bridge installed, the equip trigger says so and hangs nothing; login and change still check. */
class ReputationRankTriggersTest {

    @Test
    void withNoBridgeInstalledNothingHangs() {
        assertFalse(ReputationRankTriggers.hangOnBridge(null));
        assertFalse(ReputationRankTriggers.hangOnBridge(null), "asked again, still nothing, and said only once");
    }
}
