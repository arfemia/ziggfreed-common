package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.board.asset.BoardSlotAsset;
import com.ziggfreed.common.i18n.ContentI18n;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.progress.asset.ContentTextAsset;

/**
 * Which key a band or a shelf is printed under, which is the whole of the bug this ladder exists for:
 * a free word the content invented was turned into a key and handed to a client, so a band nobody had
 * translated rendered as {@code board.grade.training} on screen.
 *
 * <p>The rungs a unit JVM can see are the ones that matter here - a consumer's own key, and the
 * absence of any key at all. The library's own shipped default is an engine catalogue lookup, so it
 * answers "no" with no server standing and the assertions below read as the unfilled case;
 * that rung is in-game smoke like the rest of the rendering.
 */
class CommerceLabelsTest {

    /** A consumer that ships exactly the keys it was built with. */
    private record Fill(@Nonnull String prefix, @Nonnull Set<String> keys) implements ContentI18n {

        @Override
        @Nonnull
        public String keyPrefix() {
            return prefix;
        }

        @Override
        public boolean hasKey(@Nonnull String unprefixedKey) {
            return keys.contains(unprefixedKey);
        }
    }

    @BeforeEach
    @AfterEach
    void unfilled() {
        ContentKeys.reset();
    }

    @Test
    @DisplayName("a band nobody ships a word for is printed as no key at all, never as its key")
    void anUnknownBandHasNoKey() {
        assertNull(CommerceLabels.labelKey(CommerceLabels.GRADE_PREFIX + "skirmish"),
                "with nothing shipping it, the caller must fall back to the word itself");
        assertNull(CommerceLabels.labelKey(CommerceLabels.CATEGORY_PREFIX + "relics"));
        assertNull(CommerceLabels.labelKey(""));
    }

    @Test
    @DisplayName("a consumer that ships the convention key keeps it, namespace and all")
    void aConsumerKeyIsUsedAsAuthored() {
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("board.grade.veteran")));

        String key = CommerceLabels.labelKey(CommerceLabels.GRADE_PREFIX + "veteran");

        assertEquals("board.grade.veteran", key,
                "the unprefixed key, so ContentKeys still decides whose namespace it belongs to");
        assertEquals("mmoskilltree.board.grade.veteran", ContentKeys.resolved(key));
    }

    @Test
    @DisplayName("a consumer's word for a band it does not ship leaves the band unnamed")
    void anotherBandIsNotClaimed() {
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("board.grade.veteran")));

        assertNull(CommerceLabels.labelKey(CommerceLabels.GRADE_PREFIX + "skirmish"));
    }

    // ==================== the authored rung ====================

    @Test
    @DisplayName("a board says what its own band is called, beside the band")
    void aSlotCarriesItsOwnWord() {
        BoardSlotAsset[] slots = {BoardSlotAsset.of("Skirmish", 2, null,
                ContentTextAsset.of("board.grade.skirmish", null, null))};

        ContentTextAsset text = CommerceLabels.gradeTextOf(slots, "skirmish");

        assertEquals("board.grade.skirmish", text == null ? null : text.getTitleKey(),
                "matched however either side capitalized the band");
    }

    @Test
    @DisplayName("a band the board never declared carries no authored word")
    void anUndeclaredBandHasNoText() {
        BoardSlotAsset[] slots = {BoardSlotAsset.of("Skirmish", null, null,
                ContentTextAsset.of("board.grade.skirmish", null, null))};

        assertNull(CommerceLabels.gradeTextOf(slots, "hard"));
        assertNull(CommerceLabels.gradeTextOf(null, "skirmish"));
    }

    @Test
    @DisplayName("a slot that names a band without naming it in words is not an error")
    void aSlotNeedNotCarryText() {
        BoardSlotAsset[] slots = {BoardSlotAsset.of("Hard", null, null)};

        assertNull(CommerceLabels.gradeTextOf(slots, "hard"));
    }
}
