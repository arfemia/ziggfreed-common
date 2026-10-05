package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.state.DialogueOnce;
import com.ziggfreed.common.dialogue.state.DialogueStateKeys;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;
import com.ziggfreed.common.world.WorldSelector;

/**
 * The {@code Period} leaf on the {@code Once} group, purely: how it reads, where the window goes in
 * the key, when each window turns over, what the stale-window prefix can and cannot reach, and the
 * audit of a word that is neither {@code Daily} nor {@code Weekly}.
 */
class DialogueOncePeriodTest {

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    @Nonnull
    private static DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).build();
    }

    private static long at(@Nonnull String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    @Test
    void periodIsALeafOfTheOnceGroupOnLinesAndBeats() {
        NpcDialogue d = engine().decode("old_jack", """
                { "Start": { "First": [ { "Node": "n", "Once": { "Period": "Weekly" } } ] },
                  "Nodes": { "n": { "Options": [
                    { "LabelKey": "a", "Once": { "Period": "daily" } },
                    { "LabelKey": "b", "Once": true },
                    { "LabelKey": "c", "Once": { "Where": { "Match": ["pumpkin_patch"] }, "Period": "Daily" } } ] } } }
                """);
        assertNotNull(d);
        assertEquals(DialogueOnce.Period.WEEKLY, d.getStart().first().get(0).getOnce().getPeriod());
        assertEquals(DialogueOnce.Period.DAILY, d.getNode("n").getOptions().get(0).getOnce().getPeriod(),
                "the window word is read without regard to case");
        assertNull(d.getNode("n").getOptions().get(1).getOnce().getPeriod(),
                "true is still spent for good");
        DialogueOnce both = d.getNode("n").getOptions().get(2).getOnce();
        assertEquals(DialogueOnce.Period.DAILY, both.getPeriod());
        assertEquals("pumpkin_patch", both.getWhere().getMatch()[0], "Where and Period are independent leaves");
    }

    @Test
    void aWindowIsFiledAfterTheWholeKeyWorldScopeIncluded() {
        long noon = at("2026-10-31T12:00:00Z");
        long day = LocalDate.of(2026, 10, 31).toEpochDay();
        DialogueOnce daily = DialogueOnce.of(null, "Daily");
        DialogueOnce.Slot slot = daily.slotOf("once:o:old_jack:menu:candy", noon);
        assertEquals("once:o:old_jack:menu:candy:PD" + day, slot.key());
        assertEquals("once:o:old_jack:menu:candy:P", slot.staleFamily());

        DialogueOnce perWorld = DialogueOnce.of(
                WorldSelector.of(new String[] {"pumpkin_patch"}, null, null), "Daily");
        String scoped = perWorld.resolveKey("once:o:old_jack:menu:candy", "Pumpkin_Patch");
        assertEquals("once:o:old_jack:menu:w:pumpkin_patch:candy", scoped);
        assertEquals("once:o:old_jack:menu:w:pumpkin_patch:candy:PD" + day, perWorld.slotOf(scoped, noon).key(),
                "the window goes after the world scope, so each world keeps its own day");
    }

    @Test
    void aOnceWithNoPeriodKeepsItsKeyAndHasNothingToClear() {
        DialogueOnce.Slot slot = DialogueOnce.GLOBAL.slotOf("once:e:old_jack:hello", at("2026-10-31T12:00:00Z"));
        assertEquals("once:e:old_jack:hello", slot.key());
        assertNull(slot.staleFamily());
    }

    @Test
    void dailyTurnsOverAtMidnightUtc() {
        DialogueOnce.Period daily = DialogueOnce.Period.DAILY;
        long lastMoment = daily.index(at("2026-10-31T23:59:59.999Z"));
        assertEquals(lastMoment, daily.index(at("2026-10-31T00:00:00Z")), "one UTC day is one window");
        assertEquals(lastMoment + 1, daily.index(at("2026-11-01T00:00:00Z")));
    }

    @Test
    void weeklyTurnsOverAtMondayMidnightUtc() {
        assertEquals(DayOfWeek.MONDAY, LocalDate.of(2026, 11, 2).getDayOfWeek(), "fixture: 2 Nov 2026 is a Monday");
        DialogueOnce.Period weekly = DialogueOnce.Period.WEEKLY;
        long sunday = weekly.index(at("2026-11-01T23:59:59Z"));
        long monday = weekly.index(at("2026-11-02T00:00:00Z"));
        assertEquals(sunday + 1, monday);
        assertEquals(monday, weekly.index(at("2026-11-08T23:59:59Z")), "Monday through Sunday is one window");
        assertEquals(sunday, weekly.index(at("2026-10-26T00:00:00Z")), "the week before began the previous Monday");
    }

    @Test
    void aWindowFamilyReachesOnlyThatKeysOwnWindows() {
        // The worst neighbourhood for a prefix clear: a line whose identity is "w", the world-scope
        // marker, beside a sibling kept per world whose world starts with p, and a sibling whose
        // OnceId reads like a window segment.
        String line = DialogueStateKeys.optionOnce("old_jack", "menu", "w");
        String perWorldSibling = DialogueOnce.ofWhere(WorldSelector.of(new String[] {"pumpkin_patch"}, null, null))
                .resolveKey(DialogueStateKeys.optionOnce("old_jack", "menu", "candy"), "pumpkin_patch");
        String lookalike = DialogueStateKeys.optionOnce("old_jack", "menu", "w:PD20757");
        DialogueOnce daily = DialogueOnce.of(null, "Daily");
        DialogueOnce.Slot today = daily.slotOf(line, at("2026-10-31T12:00:00Z"));
        String yesterday = daily.slotOf(line, at("2026-10-30T12:00:00Z")).key();

        assertEquals("once:o:old_jack:menu:w:pumpkin_patch:candy", perWorldSibling);
        assertTrue(today.key().startsWith(today.staleFamily()) && yesterday.startsWith(today.staleFamily()),
                "every window of the line sits under its family");
        assertFalse(line.startsWith(today.staleFamily()), "the line's own permanent key is not one of its windows");
        assertFalse(perWorldSibling.startsWith(today.staleFamily()), "a sibling kept per world is out of reach");
        assertFalse(lookalike.startsWith(today.staleFamily()), "an OnceId that reads like a window is folded lower-case");
    }

    @Test
    void anUnknownWordReadsAsDailyAndIsReported() {
        DialogueOnce typo = DialogueOnce.of(null, "Fortnightly");
        assertEquals(DialogueOnce.Period.DAILY, typo.getPeriod(), "a window was asked for, so the line keeps coming back");
        assertTrue(typo.hasUnknownPeriod());
        assertFalse(DialogueOnce.of(null, " weekly ").hasUnknownPeriod());
        assertNull(DialogueOnce.of(null, "  ").getPeriod(), "a blank word is no window at all");

        NpcDialogue d = engine().decode("old_jack", """
                { "Start": { "First": [ { "Node": "n", "Once": { "Period": "Hourly" } } ] },
                  "Nodes": { "n": { "Options": [ { "LabelKey": "a", "Once": { "Period": "Fortnightly" } } ] } } }
                """);
        assertNotNull(d);
        List<Finding> found = DialogueStructureValidator.validate(d).stream()
                .filter(f -> f.code().equals("ONCE_UNKNOWN_PERIOD")).toList();
        assertEquals(2, found.size(), "the beat and the line are each named: " + found);
        assertTrue(found.stream().allMatch(f -> f.severity() == Severity.ERROR));
        assertTrue(found.stream().anyMatch(f -> f.message().contains("'Fortnightly'")), found.toString());
    }
}
