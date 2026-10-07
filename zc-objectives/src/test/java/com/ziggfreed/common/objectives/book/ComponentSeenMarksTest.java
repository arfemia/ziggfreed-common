package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.objectives.store.ZigProgressComponent;
import com.ziggfreed.common.subject.Subject;

/**
 * The library's own seen marks, kept in the player's progress component: a category opened reads its own mark; one
 * never opened reads the player's first look at the book (so the marks' arrival lights nothing already earned);
 * marking writes the mark and reports the player dirty, never a flush; a player with no component reads as seen just
 * now and remembers nothing. The book's deps carry these marks unless a consumer names its own.
 */
class ComponentSeenMarksTest {

    private static final long NOW = 1_790_000_000_000L;

    private final List<Subject> dirtied = new ArrayList<>();
    private ZigProgressComponent component;
    private Subject player;
    private long clock = NOW;

    @BeforeEach
    void player() {
        ProgressionDefaults.reset();
        ProgressionDefaults.onProgressDirty(dirtied::add);
        component = new ZigProgressComponent();
        player = new Subject(new UUID(0, 3), "Bea", component);
    }

    @AfterEach
    void reset() {
        ProgressionDefaults.reset();
        ObjectiveBookDeps.libraryMarks(null);
    }

    private ComponentSeenMarks marks() {
        return new ComponentSeenMarks(() -> clock);
    }

    @Test
    void theFirstLookSetsTheBaselineEveryUnopenedCategoryReads() {
        ComponentSeenMarks marks = marks();

        assertEquals(NOW, marks.seenAt(player, "combat"), "the first look is the baseline");
        assertEquals(1, dirtied.size(), "the baseline is saved with the player");
        clock = NOW + 5_000;
        assertEquals(NOW, marks.seenAt(player, "gathering"), "a later look at another category reads the baseline");
        assertEquals(1, dirtied.size(), "the baseline is written once");
    }

    @Test
    void openingACategoryMarksItAndReportsThePlayerDirty() {
        ComponentSeenMarks marks = marks();
        marks.seenAt(player, "combat");
        dirtied.clear();

        marks.markSeen(player, "Combat", NOW + 60_000);

        assertEquals(NOW + 60_000, marks.seenAt(player, "combat"), "the category's own mark, under any casing");
        assertEquals(NOW, marks.seenAt(player, "gathering"), "the others keep the baseline");
        assertEquals(List.of(player), dirtied);
        assertEquals(NOW + 60_000, component.achievementSeen("combat"), "kept in the progress component");
    }

    @Test
    void noSubjectOrNoComponentReadsAsSeenAndRemembersNothing() {
        ComponentSeenMarks marks = marks();
        Subject bare = new Subject(new UUID(0, 4), "Cy", null);

        assertEquals(Long.MAX_VALUE, marks.seenAt(null, "combat"));
        assertEquals(Long.MAX_VALUE, marks.seenAt(bare, "combat"), "nothing reads as new without a place to keep it");
        marks.markSeen(bare, "combat", NOW);
        marks.markSeen(null, "combat", NOW);
        assertTrue(dirtied.isEmpty());
    }

    @Test
    void theBooksDepsCarryTheLibraryMarksUnlessAConsumerNamesItsOwn() {
        assertSame(SeenMarks.NONE, ObjectiveBookDeps.DEFAULTS.seen(), "nothing until the library fills them");
        ComponentSeenMarks library = marks();
        ObjectiveBookDeps.libraryMarks(library);

        assertSame(library, ObjectiveBookDeps.DEFAULTS.seen());
        assertSame(library, ObjectiveBookDeps.builder().build().seen(), "a consumer's deps that say nothing get them");
        assertSame(SeenMarks.NONE, ObjectiveBookDeps.builder().seen(SeenMarks.NONE).build().seen(),
                "a consumer may turn them off");
    }
}
