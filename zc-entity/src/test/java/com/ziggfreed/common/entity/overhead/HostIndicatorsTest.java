package com.ziggfreed.common.entity.overhead;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * The pure half of an overhead indicator: what one viewer sees over a host when a shared entry and
 * their own entry both stand, when an entry runs out, and how a state id is matched.
 */
class HostIndicatorsTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Test
    void aViewersOwnEntryLayersOverTheSharedOneAndFallsBackWhenHidden() {
        HostIndicators host = new HostIndicators();
        host.show(IndicatorAudience.everyone(), "Boss_Enraged", IndicatorLifetime.untilHidden(), 0L);
        host.show(IndicatorAudience.of(ALICE), "Quest_Available", IndicatorLifetime.untilHidden(), 0L);

        assertEquals("quest_available", host.stateFor(ALICE, 1L), "her own entry wins for her");
        assertEquals("boss_enraged", host.stateFor(BOB, 1L), "everyone else sees the shared one");

        host.hide(IndicatorAudience.of(ALICE));
        assertEquals("boss_enraged", host.stateFor(ALICE, 2L), "hiding hers shows her the shared one again");

        host.hide(IndicatorAudience.everyone());
        assertNull(host.stateFor(ALICE, 3L));
        assertNull(host.stateFor(BOB, 3L));
        assertTrue(host.isIdle(3L));
    }

    @Test
    void showingASecondStateToTheSameAudienceReplacesTheFirst() {
        HostIndicators host = new HostIndicators();
        host.show(IndicatorAudience.of(Set.of(ALICE, BOB)), "Quest_In_Progress", IndicatorLifetime.untilHidden(), 0L);
        host.show(IndicatorAudience.of(ALICE), "Quest_Ready_To_Turn_In", IndicatorLifetime.untilHidden(), 0L);

        assertEquals("quest_ready_to_turn_in", host.stateFor(ALICE, 1L));
        assertEquals("quest_in_progress", host.stateFor(BOB, 1L));
        assertEquals(Set.of("quest_ready_to_turn_in", "quest_in_progress"), host.liveStateKeys(1L));
    }

    @Test
    void aTimedEntryRunsOutAndAnExpiredOwnEntryFallsBackToTheSharedOne() {
        HostIndicators host = new HostIndicators();
        host.show(IndicatorAudience.everyone(), "Shrine_Lit", IndicatorLifetime.untilHidden(), 0L);
        host.show(IndicatorAudience.of(ALICE), "Hunter_Near", IndicatorLifetime.forMillis(500L), 0L);

        assertEquals("hunter_near", host.stateFor(ALICE, 499L));
        assertEquals("shrine_lit", host.stateFor(ALICE, 500L), "at the deadline her cue is over");
        assertEquals(Set.of("shrine_lit"), host.liveStateKeys(500L), "an expired entry names no live state");

        host.expire(500L);
        assertEquals("shrine_lit", host.stateFor(ALICE, 501L));
    }

    @Test
    void aZeroLifetimeShowsNothingAndAnEmptyAudienceChangesNothing() {
        HostIndicators host = new HostIndicators();
        host.show(IndicatorAudience.of(ALICE), "Flash", IndicatorLifetime.forMillis(0L), 10L);
        assertNull(host.stateFor(ALICE, 10L));
        assertTrue(IndicatorAudience.of(Set.of()).isEmpty());
        assertFalse(IndicatorAudience.everyone().isEmpty());
    }

    @Test
    void stateIdsMatchWithoutRegardToCaseOrPadding() {
        assertEquals("quest_available", HostIndicators.keyOf("  Quest_Available "));
        HostIndicators host = new HostIndicators();
        host.show(IndicatorAudience.of(BOB), "QUEST_AVAILABLE", IndicatorLifetime.untilHidden(), 0L);
        assertEquals(HostIndicators.keyOf("Quest_Available"), host.stateFor(BOB, 1L));
    }

    @Test
    void forgettingAViewerDropsOnlyTheirOwnEntry() {
        HostIndicators host = new HostIndicators();
        host.show(IndicatorAudience.everyone(), "Shared", IndicatorLifetime.untilHidden(), 0L);
        host.show(IndicatorAudience.of(ALICE), "Mine", IndicatorLifetime.untilHidden(), 0L);
        host.forget(ALICE);
        assertEquals("shared", host.stateFor(ALICE, 1L));
        host.clear();
        assertNull(host.stateFor(ALICE, 2L));
    }

    @Test
    void aLifetimeDeadlineSaturatesRatherThanWrapping() {
        assertEquals(Long.MAX_VALUE, IndicatorLifetime.untilHidden().deadlineFrom(5L));
        assertEquals(Long.MAX_VALUE, IndicatorLifetime.forMillis(Long.MAX_VALUE - 1L).deadlineFrom(10L));
        assertEquals(15L, IndicatorLifetime.forMillis(5L).deadlineFrom(10L));
        assertEquals(0L, IndicatorLifetime.forMillis(-3L).durationMs(), "a negative span is nothing");
    }
}
