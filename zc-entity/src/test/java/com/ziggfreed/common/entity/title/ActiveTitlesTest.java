package com.ziggfreed.common.entity.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The off-thread mirror of who shows what: lower-cased, per player, and forgetting cleanly. */
class ActiveTitlesTest {

    @AfterEach
    void clear() {
        ActiveTitles.clear();
    }

    @Test
    void aShownTitleIsKeptLowerCasedPerPlayer() {
        UUID shower = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        ActiveTitles.put(shower, " Hallows_Eve_Hallowed ");

        assertEquals("hallows_eve_hallowed", ActiveTitles.of(shower));
        assertNull(ActiveTitles.of(other));
    }

    @Test
    void showingNothingAndLeavingBothForgetThePlayer() {
        UUID player = UUID.randomUUID();
        ActiveTitles.put(player, "pumpkin_king");
        ActiveTitles.put(player, null);
        assertNull(ActiveTitles.of(player));

        ActiveTitles.put(player, "pumpkin_king");
        ActiveTitles.put(player, "  ");
        assertNull(ActiveTitles.of(player));

        ActiveTitles.put(player, "pumpkin_king");
        ActiveTitles.evict(player);
        assertNull(ActiveTitles.of(player));
    }

    @Test
    void noPlayerReadsNone() {
        assertNull(ActiveTitles.of(null));
    }
}
