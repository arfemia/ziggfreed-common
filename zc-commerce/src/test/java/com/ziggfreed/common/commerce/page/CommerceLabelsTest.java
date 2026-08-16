package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.i18n.ContentI18n;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.progress.asset.ContentTextAsset;

/**
 * Which key a band or a shelf is printed under, which is the whole of the bug this ladder exists for:
 * a free word the content invented was turned into a key and handed to a client, so a band nobody had
 * translated rendered as {@code board.grade.training} on screen.
 *
 * <p>The rungs a unit JVM can see are the ones that matter here - the authored word, a consumer's own
 * key, and the absence of any key at all. The library's own shipped default is an engine catalogue
 * lookup, so it answers "no" with no server standing; that rung is in-game smoke like the rest of the
 * rendering. A band this library itself ships a word for is therefore asserted through a consumer
 * fill, which outranks that rung whether a catalogue is standing or not, so no assertion below turns
 * on which of the two answered.
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

    /** Decodes a minimal board, the same way {@code CommerceCodecTest} does in its own package. */
    private static BoardAsset board(@Nonnull String json, @Nonnull String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(BoardAsset.class, id, null);
        return BoardAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    @Test
    @DisplayName("a board says what its own band is called, in its Grades map")
    void aBoardNamesItsOwnBand() throws IOException {
        BoardAsset daily = board("""
                { "Grades": { "Skirmish": { "TitleKey": "board.grade.skirmish" } } }
                """, "daily");

        ContentTextAsset text = daily.gradeText("SKIRMISH");

        assertEquals("board.grade.skirmish", text == null ? null : text.getTitleKey(),
                "matched however either side capitalized the band");
    }

    @Test
    @DisplayName("a band the board never named in Grades carries no authored word")
    void anUndeclaredBandHasNoText() throws IOException {
        BoardAsset daily = board("""
                { "Grades": { "Skirmish": { "TitleKey": "board.grade.skirmish" } } }
                """, "daily");

        assertNull(daily.gradeText("hard"));
        assertEquals("skirmish", CommerceLabels.grade(null, "skirmish", null).getRawText(),
                "a board that cannot even be looked up still falls through to the raw id");
    }

    @Test
    @DisplayName("the board's own Grades entry is the word its band is printed under")
    void anAuthoredWordWinsTheLadder() throws IOException {
        ContentKeys.install(new Fill("mmoskilltree.",
                Set.of("bounty.band.skirmish", "board.grade.skirmish")));
        BoardAsset daily = board("""
                { "Grades": { "Skirmish": { "TitleKey": "bounty.band.skirmish" } } }
                """, "daily");

        Message word = CommerceLabels.grade(daily, "skirmish", null);

        assertEquals("mmoskilltree.bounty.band.skirmish", word.getMessageId(),
                "the board's own key, not the convention key the next rung down would have used");
    }

    @Test
    @DisplayName("declaring a band and naming it are two different authored facts")
    void aDeclaredBandNeedNotCarryAWord() throws IOException {
        ContentKeys.install(new Fill("mmoskilltree.", Set.of("board.grade.hard")));
        BoardAsset daily = board("""
                { "Slots": [ { "Difficulty": "Hard" } ] }
                """, "daily");

        assertEquals(1, daily.slotsOrEmpty().length, "the band is declared, via a slot");
        assertEquals("hard", daily.slotsOrEmpty()[0].label());
        assertNull(daily.gradeText("hard"), "declaring a band is not the same as naming it");

        Message word = CommerceLabels.grade(daily, "hard", null);

        assertEquals("mmoskilltree.board.grade.hard", word.getMessageId(),
                "rung 1 is empty, so the band is printed under whichever lower rung ships a word");
        assertNull(word.getRawText(),
                "the raw band word is the last rung of all, reached only when nothing ships one");
    }
}
