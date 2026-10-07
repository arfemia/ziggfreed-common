package com.ziggfreed.common.objectives.book.achievement;

import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.DAY;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.NOW;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.ach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.objectives.book.BookState;
import com.ziggfreed.common.objectives.book.SeenMarks;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.CollectionTile;

/**
 * The "new" mark's round trip as the tab drives it: a tile reads new once something was earned there after the last
 * look; opening Browse on that category is what the tab marks seen (only a category that reads new, never "all" or
 * a category with nothing new), and the tile then reads plain until the next thing is earned there.
 */
class AchievementSeenFlowTest {

    private AchievementFixture f;
    private final Map<String, Long> marks = new HashMap<>();

    @BeforeEach
    void fixture() {
        f = new AchievementFixture();
        f.seen = new SeenMarks() {
            @Override
            public long seenAt(@Nullable Subject subject, @Nonnull String category) {
                return marks.getOrDefault(category.toLowerCase(Locale.ROOT), NOW - 5 * DAY);
            }

            @Override
            public void markSeen(@Nullable Subject subject, @Nonnull String category, long nowMs) {
                marks.put(category.toLowerCase(Locale.ROOT), nowMs);
            }
        };
    }

    @AfterEach
    void close() {
        f.close();
    }

    @Test
    void openingANewCategoryClearsItsMarkUntilTheNextEarn() {
        Achievement first = f.add(ach("a_first", "combat", null, 1), "First");
        Achievement second = f.add(ach("a_second", "combat", null, 1), "Second");
        Achievement old = f.add(ach("a_old", "gathering", null, 1), "Old");
        f.earn(first, NOW - DAY);
        f.earn(old, NOW - 10 * DAY);

        List<CollectionTile> tiles = f.reader().tiles();
        CollectionTile combat = AchievementsTab.unseenTile(tiles, "Combat");
        assertNotNull(combat, "earned a day ago, last looked five days ago: Browse on it marks it");
        assertEquals("combat", combat.id());
        assertNull(AchievementsTab.unseenTile(tiles, "gathering"), "nothing new there, so nothing is written");
        assertNull(AchievementsTab.unseenTile(tiles, BookState.ALL), "All categories is no category's look");
        assertNull(AchievementsTab.unseenTile(tiles, null));
        assertNull(AchievementsTab.unseenTile(tiles, "nowhere"));

        f.seen.markSeen(AchievementFixture.ALICE, combat.id(), NOW);
        assertFalse(tile(f.reader().tiles(), "combat").unseen(), "opened just now: the mark is gone");

        f.earn(second, NOW + 60_000L);
        assertTrue(tile(f.reader().tiles(), "combat").unseen(), "something earned after the look lights it again");
    }

    @Test
    void anAchievementWithNoUnlockStampNeverLightsATile() {
        Achievement a = f.add(ach("a_unstamped", "combat", null, 1), "Unstamped");
        f.engine.store().setStatus(AchievementFixture.ALICE, a.id(), AchievementStatus.CLAIMED);
        assertNull(AchievementsTab.unseenTile(f.reader().tiles(), "combat"));
    }

    @Nonnull
    private static CollectionTile tile(@Nonnull List<CollectionTile> tiles, @Nonnull String id) {
        for (CollectionTile tile : tiles) {
            if (tile.id().equals(id)) {
                return tile;
            }
        }
        throw new AssertionError("no tile " + id);
    }
}
