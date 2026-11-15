package com.ziggfreed.common.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * A minted id is saved data (progress is filed under it, and it is never renamed after), so the mint must
 * never hand out an id the store already holds, under any casing, and every id it mints must be a safe
 * file name. Mechanism only: no engine.
 */
class ContentIdsTest {

    @Test
    void aTypedNameMintsAnIdLikeThisThatNamesAFile() {
        String id = ContentIds.mint("the wolf's  HUNT, part 2!", taken -> false);
        assertEquals("The_Wolfs_Hunt_Part_2", id);
        // A curly apostrophe (U+2019) drops like a straight one; accents (U+00C9, U+00EA) drop to their letters.
        assertEquals("Wolfs_Hunt", ContentIds.mint("wolf" + (char) 0x2019 + "s hunt", taken -> false));
        assertTrue(ContentIds.isSafe(id));
        assertEquals("Ecoute_La_Foret", ContentIds.mint((char) 0xC9 + "coute la for" + (char) 0xEA + "t", taken -> false));
        assertEquals(ContentIds.FALLBACK, ContentIds.mint("!!!", taken -> false));
        assertTrue(ContentIds.base("x".repeat(500)).length() <= ContentIds.MAX_LENGTH);
    }

    @Test
    void aTakenIdGetsTheFirstFreeSuffixWhateverItsCase() {
        Set<String> taken = new HashSet<>(Set.of("wolf_hunt", "wolf_hunt_2"));
        String id = ContentIds.mint("Wolf Hunt", c -> taken.contains(c.toLowerCase(Locale.ROOT)));
        assertEquals("Wolf_Hunt_3", id);
    }

    @Test
    void anIdThatCouldLeaveTheFolderIsNeverSafe() {
        assertFalse(ContentIds.isSafe("../Quests/X"));
        assertFalse(ContentIds.isSafe("a/b"));
        assertFalse(ContentIds.isSafe("a.json"));
        assertFalse(ContentIds.isSafe(""));
        assertFalse(ContentIds.isSafe(null));
    }
}
