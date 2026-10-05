package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.validation.Finding;

/**
 * What a capstone's {@code MetaSelector} picks: every achievement matching all its leaves, never the
 * capstone itself or any other capstone, only inside the capstone's own occurrence, beside its
 * explicit children; and that the engine's cascade then reads the picked set.
 */
class MetaSelectorTest {

    private static final String ONE_STEP =
            "\"Criteria\": { \"one\": { \"Kind\": \"BREAK_BLOCK\", \"Amount\": 1 } }";

    private static AchievementAsset leaf(@Nonnull String id, @Nonnull String listing) throws IOException {
        return AchievementAssetCodecTest.decodeRoot("{ \"Listing\": " + listing + ", " + ONE_STEP + " }", id);
    }

    private static AchievementAssetStore.Resolution resolve(@Nonnull FakeCalendar calendar,
            @Nonnull AchievementAsset... assets) {
        Map<String, AchievementAsset> layer = new LinkedHashMap<>();
        for (AchievementAsset asset : assets) {
            layer.put(asset.getId(), asset);
        }
        return AchievementAssetStore.resolve(layer, List.of(), calendar.reader());
    }

    private static List<String> childrenOf(@Nonnull AchievementAssetStore.Resolution resolution,
            @Nonnull String id) {
        return resolution.pool().definition(id).achievement().metaChildren();
    }

    @Test
    void aSelectorPicksEveryAchievementMatchingAllItsLeaves() throws Exception {
        AchievementAsset capstone = AchievementAssetCodecTest.decodeRoot("""
                { "MetaSelector": { "Category": "Festival", "Tags": [ "hallowed" ] } }
                """, "capstone");
        AchievementAssetStore.Resolution resolution = resolve(new FakeCalendar(), capstone,
                leaf("a", "{ \"Category\": \"festival\", \"Tags\": [ \"hallowed\" ] }"),
                leaf("b", "{ \"Category\": \"festival\", \"Tags\": [ \"extra\", \"Hallowed\" ] }"),
                leaf("c", "{ \"Category\": \"festival\" }"),
                leaf("d", "{ \"Category\": \"other\", \"Tags\": [ \"hallowed\" ] }"));

        assertEquals(List.of("a", "b"), childrenOf(resolution, "capstone"),
                "category and every tag must match, without regard to case; picks sort by id");
    }

    @Test
    void aSubcategoryLeafNarrowsThePick() throws Exception {
        AchievementAsset capstone = AchievementAssetCodecTest.decodeRoot("""
                { "MetaSelector": { "Category": "seasons", "Subcategory": "festival" } }
                """, "capstone");
        AchievementAssetStore.Resolution resolution = resolve(new FakeCalendar(), capstone,
                leaf("a", "{ \"Category\": \"seasons\", \"Subcategory\": \"festival\" }"),
                leaf("b", "{ \"Category\": \"seasons\", \"Subcategory\": \"harvest\" }"));

        assertEquals(List.of("a"), childrenOf(resolution, "capstone"));
    }

    @Test
    void aSelectorNeverPicksAnotherCapstoneOrItself() throws Exception {
        AchievementAsset first = AchievementAssetCodecTest.decodeRoot("""
                { "Listing": { "Category": "festival" }, "MetaSelector": { "Category": "festival" } }
                """, "first_capstone");
        AchievementAsset second = AchievementAssetCodecTest.decodeRoot("""
                { "Listing": { "Category": "festival" }, "MetaSelector": { "Category": "festival" } }
                """, "second_capstone");
        AchievementAsset listed = AchievementAssetCodecTest.decodeRoot("""
                { "Listing": { "Category": "festival" }, "MetaChildren": [ "x" ] }
                """, "listed_capstone");
        AchievementAssetStore.Resolution resolution = resolve(new FakeCalendar(), first, second, listed,
                leaf("x", "{ \"Category\": \"festival\" }"));

        assertEquals(List.of("x"), childrenOf(resolution, "first_capstone"),
                "two capstones over one category would otherwise wait on each other forever");
        assertEquals(List.of("x"), childrenOf(resolution, "second_capstone"));
    }

    @Test
    void aYearlyCapstonePicksOnlyItsOwnYearsCopiesOfTheSameEvent() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("yourmod_festival", 2026, 2026)
                .event("yourmod_harvest", 2026, 2026);
        AchievementAsset capstone = AchievementAssetCodecTest.decodeRoot("""
                { "Occurrence": { "Event": "yourmod_festival" }, "MetaSelector": { "Category": "festival" } }
                """, "capstone");
        AchievementAsset sameEvent = AchievementAssetCodecTest.decodeRoot("""
                { "Occurrence": { "Event": "yourmod_festival" }, "Listing": { "Category": "festival" }, %s }
                """.replace("%s", ONE_STEP), "lantern");
        AchievementAsset otherEvent = AchievementAssetCodecTest.decodeRoot("""
                { "Occurrence": { "Event": "yourmod_harvest" }, "Listing": { "Category": "festival" }, %s }
                """.replace("%s", ONE_STEP), "sheaf");
        AchievementAssetStore.Resolution resolution = resolve(calendar, capstone, sameEvent, otherEvent,
                leaf("plain", "{ \"Category\": \"festival\" }"));

        assertEquals(List.of("lantern_2026"), childrenOf(resolution, "capstone_2026"));
        assertEquals(List.of("lantern_2027"), childrenOf(resolution, "capstone_2027"));
    }

    @Test
    void anOrdinaryCapstoneNeverPicksAYearlyCopy() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event("yourmod_festival", 2026, 2026);
        AchievementAsset capstone = AchievementAssetCodecTest.decodeRoot("""
                { "MetaSelector": { "Category": "festival" } }
                """, "capstone");
        AchievementAsset yearly = AchievementAssetCodecTest.decodeRoot("""
                { "Occurrence": { "Event": "yourmod_festival" }, "Listing": { "Category": "festival" }, %s }
                """.replace("%s", ONE_STEP), "lantern");
        AchievementAssetStore.Resolution resolution = resolve(calendar, capstone, yearly,
                leaf("plain", "{ \"Category\": \"festival\" }"));

        assertEquals(List.of("plain"), childrenOf(resolution, "capstone"),
                "every year's copy would grow its set forever");
    }

    @Test
    void explicitChildrenComeFirstAndNoChildIsListedTwice() throws Exception {
        AchievementAsset capstone = AchievementAssetCodecTest.decodeRoot("""
                { "MetaChildren": [ "b" ], "MetaSelector": { "Category": "festival" } }
                """, "capstone");
        AchievementAssetStore.Resolution resolution = resolve(new FakeCalendar(), capstone,
                leaf("a", "{ \"Category\": \"festival\" }"), leaf("b", "{ \"Category\": \"festival\" }"));

        assertEquals(List.of("b", "a"), childrenOf(resolution, "capstone"));
    }

    @Test
    void anEmptySelectorPicksNothingAndSaysSo() throws Exception {
        AchievementAsset capstone = AchievementAssetCodecTest.decodeRoot("""
                { "MetaSelector": { } }
                """, "capstone");
        AchievementAssetStore.Resolution resolution = resolve(new FakeCalendar(), capstone,
                leaf("a", "{ \"Category\": \"festival\" }"));

        assertTrue(childrenOf(resolution, "capstone").isEmpty(),
                "a selector picking everything would capstone the whole catalogue");
        boolean warned = false;
        for (Finding finding : resolution.issues()) {
            warned |= finding.code().equals("EMPTY_META_SELECTOR");
        }
        assertTrue(warned);
    }

    @Test
    void aSelectedCapstoneEarnsItselfWhenEveryPickedChildIsEarned() throws Exception {
        AchievementAsset capstone = AchievementAssetCodecTest.decodeRoot("""
                { "MetaSelector": { "Category": "festival" } }
                """, "capstone");
        AchievementPool pool = resolve(new FakeCalendar(), capstone,
                leaf("a", "{ \"Category\": \"festival\" }"), leaf("b", "{ \"Category\": \"festival\" }")).pool();
        AchievementEngine engine = AchievementEngine.builder().nativeEvents(false).build();
        engine.setAchievements(pool.achievements());
        Subject alice = Subject.of(new UUID(0, 11), "Alice");

        engine.unlock(alice, engine.achievement("a"));
        assertFalse(engine.isUnlocked(alice, "capstone"));
        engine.unlock(alice, engine.achievement("b"));
        assertTrue(engine.isUnlocked(alice, "capstone"), "the cascade reads the picked children");
    }
}
