package com.ziggfreed.common.achievement.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * One yearly copy of a file that names an {@code Occurrence}: named for its year, in circulation only
 * while that year's occurrence is live, a feat once it is not, both read live, its year answering the
 * year sentinel, and a sibling of the same event read as the same year's copy.
 */
class OccurrenceDefinitionTest {

    private static final String EVENT = "yourmod_festival";

    private static AchievementAsset keeper() throws IOException {
        return AchievementAssetCodecTest.decodeRoot("""
                { "Occurrence": { "Event": "YourMod_Festival" },
                  "Text": { "TitleKey": "yourmod.festival.keeper.title",
                            "TextArgs": { "Title": [ "@year" ], "Flavor": [ "@amount", "@year" ] } },
                  "Listing": { "Category": "festival", "Tags": [ "festival" ] },
                  "Criteria": { "lanterns": { "Kind": "BREAK_BLOCK", "Target": "Fixture_Lantern", "Amount": 3 } },
                  "Rewards": { "Auto": [ { "Kind": "Item", "Params": { "Item": "Fixture_Lantern",
                                                                       "Count": "1",
                                                                       "StackNameArg": "@year" } } ] } }
                """, "festival_keeper");
    }

    private static OccurrenceMinting.Mint mint(FakeCalendar calendar, int year) {
        return new OccurrenceMinting.Mint("festival_keeper_" + year, EVENT, year, "festival_keeper",
                calendar.reader(), UnaryOperator.identity());
    }

    @Test
    void theOccurrenceGroupDecodesAndInherits() throws Exception {
        AchievementAsset parent = AchievementAssetCodecTest.decodeRoot("""
                { "Abstract": true, "Occurrence": { "Event": "YourMod_Festival" } }
                """, "festival_base");
        AchievementAsset child = AchievementAssetCodecTest.decode("""
                { "Criteria": { "one": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """, "festival_child", "festival_base", parent);

        assertNotNull(child.getOccurrence(), "a child of an event skeleton belongs to the same event");
        assertEquals(EVENT, child.getOccurrence().eventIdOrNull(), "the event id is lower-cased");
        assertNull(AchievementAssetCodecTest.decodeRoot("{ }", "plain").getOccurrence());
    }

    @Test
    void aCopyIsNamedForItsYearAndNamesItsOccurrence() throws Exception {
        AchievementDefinition copy = keeper().toDefinition(mint(new FakeCalendar(), 2026));

        assertEquals("festival_keeper_2026", copy.id());
        assertEquals("festival_keeper_2026", copy.achievement().id());
        assertEquals(new Achievement.Occurrence(EVENT, 2026, "festival_keeper"), copy.achievement().occurrence());
    }

    @Test
    void criterionProgressIsFiledUnderTheYearsOwnId() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event(EVENT, 2025, 2026).live(EVENT, 2026);
        Achievement y2025 = keeper().toDefinition(mint(calendar, 2025)).achievement();
        Achievement y2026 = keeper().toDefinition(mint(calendar, 2026)).achievement();
        AchievementEngine engine = AchievementEngine.builder().nativeEvents(false).build();
        engine.setAchievements(List.of(y2025, y2026));
        Subject alice = Subject.of(new UUID(0, 9), "Alice");

        engine.dispatch(alice, "BREAK_BLOCK", "Fixture_Lantern", null, 1L);

        assertEquals(0, engine.progressOf(alice, y2025, 0).current(), "a past year counts nothing");
        assertEquals(1, engine.progressOf(alice, y2026, 0).current(), "the live year counts, on its own key");
    }

    @Test
    void aCopyLeavesCirculationTheMomentItsOccurrenceCloses() throws Exception {
        FakeCalendar calendar = new FakeCalendar().event(EVENT, 2026, 2026).live(EVENT, 2026);
        Achievement copy = keeper().toDefinition(mint(calendar, 2026)).achievement();
        assertTrue(copy.available());
        assertFalse(copy.featOfStrength(), "while its occurrence runs it is browsed like any other");

        calendar.ended(EVENT, 2026);
        assertFalse(copy.available(), "no rebuild: closed is out of circulation at once");
        assertTrue(copy.featOfStrength(), "and afterwards a feat its earners keep");
    }

    @Test
    void anAuthoredFeatStaysAFeatThroughItsOccurrence() throws Exception {
        AchievementAsset asset = AchievementAssetCodecTest.decodeRoot("""
                { "Occurrence": { "Event": "yourmod_festival" }, "Listing": { "Feat": true },
                  "Criteria": { "one": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """, "festival_trophy");
        FakeCalendar calendar = new FakeCalendar().event(EVENT, 2026, 2026).live(EVENT, 2026);

        Achievement copy = asset.toDefinition(new OccurrenceMinting.Mint("festival_trophy_2026", EVENT, 2026,
                "festival_trophy", calendar.reader(), UnaryOperator.identity())).achievement();
        assertTrue(copy.available());
        assertTrue(copy.featOfStrength());
    }

    @Test
    void aDisabledFileIsMintedButNeverInCirculation() throws Exception {
        AchievementAsset asset = AchievementAssetCodecTest.decodeRoot("""
                { "Enabled": false, "Occurrence": { "Event": "yourmod_festival" },
                  "Criteria": { "one": { "Kind": "BREAK_BLOCK", "Amount": 1 } } }
                """, "festival_retired");
        FakeCalendar calendar = new FakeCalendar().event(EVENT, 2026, 2026).live(EVENT, 2026);

        Achievement copy = asset.toDefinition(new OccurrenceMinting.Mint("festival_retired_2026", EVENT, 2026,
                "festival_retired", calendar.reader(), UnaryOperator.identity())).achievement();
        assertFalse(copy.available(), "still minted, so whoever earned it keeps it listed");
    }

    @Test
    void aCalendarThatThrowsReadsAsClosedNotAsACrash() throws Exception {
        Achievement copy = keeper().toDefinition(mint(new FakeCalendar().throwing(), 2026)).achievement();

        assertFalse(copy.available());
        assertTrue(copy.featOfStrength());
    }

    @Test
    void theYearSentinelReadsAsTheCopysYear() throws Exception {
        AchievementDefinition copy = keeper().toDefinition(mint(new FakeCalendar(), 2026));

        assertEquals(List.of("2026"), copy.titleArgs());
        assertEquals(List.of("@amount", "2026"), copy.flavorArgs(), "only the year sentinel is answered here");
        assertEquals("2026", copy.achievement().autoRewards().get(0).param("StackNameArg"));
        assertEquals("Fixture_Lantern", copy.achievement().autoRewards().get(0).param("Item"),
                "every other parameter stays as written");
    }

    @Test
    void anExplicitChildIsReadThroughTheRewrite() throws Exception {
        AchievementAsset capstone = AchievementAssetCodecTest.decodeRoot("""
                { "Occurrence": { "Event": "yourmod_festival" },
                  "MetaChildren": [ "Festival_Sibling", "Plain_Child" ] }
                """, "festival_capstone");

        AchievementDefinition copy = capstone.toDefinition(new OccurrenceMinting.Mint("festival_capstone_2026",
                EVENT, 2026, "festival_capstone", new FakeCalendar().reader(),
                child -> "festival_sibling".equals(child) ? child + "_2026" : child));
        assertEquals(List.of("festival_sibling_2026", "plain_child"), copy.achievement().metaChildren());
    }

    @Test
    void theOrdinaryFoldIsUnchanged() throws Exception {
        AchievementDefinition plain = keeper().toDefinition();

        assertEquals("festival_keeper", plain.id());
        assertNull(plain.achievement().occurrence());
        assertTrue(plain.achievement().available());
        assertFalse(plain.achievement().featOfStrength());
        assertEquals(List.of("@year"), plain.titleArgs(), "an unanswered sentinel stays as written");
    }
}
