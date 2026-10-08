package com.ziggfreed.common.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The boot audit: the switch reads 1 or true only; every pass logs under its own label through the one
 * report split (an error to the error sink, the rest to the note sink, so a reader matches by pattern,
 * not level); a throwing pass costs itself; and one marker line counts what was found.
 */
class BootAuditTest {

    @Test
    void theSwitchReadsOneOrTrueAndNothingElse() {
        assertEquals("ZIGGFREEDCOMMON_AUDIT_ON_BOOT", BootAudit.ENV, "the key the dev harness exports");
        assertTrue(BootAudit.asked("1"));
        assertTrue(BootAudit.asked(" true "));
        assertTrue(BootAudit.asked("TRUE"));
        assertFalse(BootAudit.asked(null));
        assertFalse(BootAudit.asked(""));
        assertFalse(BootAudit.asked("0"));
        assertFalse(BootAudit.asked("yes"));
    }

    @Test
    void everyPassLogsUnderItsLabelAndTheMarkerCountsThem() {
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        List<Finding> found = BootAudit.run(List.of(
                new BootAudit.Pass("[commerce] content",
                        () -> List.of(Finding.error("board", "NO_OBJECTIVES", "asks for no work", "harvest_job"))),
                new BootAudit.Pass("[loot] audit",
                        () -> List.of(Finding.warning("bonus_rows", "UNKNOWN_SEASON", "names a typo", "row")))),
                errors::add, notes::add);

        assertEquals(2, found.size());
        assertEquals(List.of("[commerce] content 'harvest_job' [NO_OBJECTIVES]: asks for no work"), errors);
        assertEquals("[loot] audit 'row' [UNKNOWN_SEASON]: names a typo", notes.get(0),
                "a warning goes to the note sink, which logs at INFO");
        assertEquals("[zc] boot audit: 2 finding(s) (1 error(s), 1 warning(s)) from 2 of 2 pass(es)",
                notes.get(notes.size() - 1));
    }

    @Test
    void aThrowingPassCostsItselfAndTheOthersStillRun() {
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        List<Finding> found = BootAudit.run(List.of(
                new BootAudit.Pass("[gearset] audit", () -> {
                    throw new IllegalStateException("no item store");
                }),
                new BootAudit.Pass("[loot] audit", List::of)), errors::add, notes::add);

        assertTrue(found.isEmpty());
        assertTrue(errors.get(0).startsWith(BootAudit.MARKER + "the pass '[gearset] audit' could not run"), errors.toString());
        assertEquals("[zc] boot audit: 0 finding(s) (0 error(s), 0 warning(s)) from 1 of 2 pass(es)",
                notes.get(notes.size() - 1));
    }

    /**
     * The boot audit counts each finding at the level its pass gave it and never re-levels one: an
     * unknown id a validator reports as a WARNING stays a warning on the note sink, a remark counts as a
     * finding but neither an error nor a warning, and only the pass's own ERROR reaches the error sink.
     */
    @Test
    void aFindingKeepsTheLevelItsPassGaveIt() {
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        List<Finding> found = BootAudit.run(List.of(
                new BootAudit.Pass("[calendar] audit", () -> List.of(
                        Finding.warning("calendar", "SPAWN_UNKNOWN_EVENT", "rides no loaded event", "spawn"))),
                new BootAudit.Pass("[title] audit", () -> List.of(
                        Finding.info("title", "UNNAMED_TITLE", "reads as its id", "hero"),
                        Finding.error("title", "TITLE_ID_UNSAVABLE", "carries a separator", "a|b")))),
                errors::add, notes::add);

        assertEquals(List.of(Severity.WARNING, Severity.INFO, Severity.ERROR),
                found.stream().map(Finding::severity).toList(), "each finding as its pass returned it, in pass order");
        assertEquals(List.of("[title] audit 'a|b' [TITLE_ID_UNSAVABLE]: carries a separator"), errors);
        assertEquals(List.of(
                "[calendar] audit 'spawn' [SPAWN_UNKNOWN_EVENT]: rides no loaded event",
                "[title] audit 'hero' [UNNAMED_TITLE]: reads as its id",
                "[zc] boot audit: 3 finding(s) (1 error(s), 1 warning(s)) from 2 of 2 pass(es)"), notes);
    }
}
