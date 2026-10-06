package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.quest.asset.QuestCategoryAsset;

/**
 * What a category and a subcategory are CALLED, rung by rung: an authored key the catalogue ships,
 * the convention key for the kind, a key the caller knows (a season's own calendar name), and last
 * the id itself, tidied, so a player never reads a raw key.
 *
 * <p>The case this exists for is a season: content files under the category {@code seasons} with the
 * event id as subcategory, so before this the breadcrumb read the id prettified ("Hallows Eve", no
 * apostrophe, never translated). Every catalogue here is a fixture handed to {@link LangCatalog}; no
 * production lang file is read.
 */
class CategoryNamesTest {

    @BeforeEach
    @AfterEach
    void realCatalogue() {
        LangCatalog.overrideForTests(null);
    }

    /** A loaded catalogue shipping exactly these full ids (the text is never read here). */
    private static void ships(@Nonnull String... fullIds) {
        Map<String, String> loaded = new LinkedHashMap<>();
        for (String id : fullIds) {
            loaded.put(id, "text of " + id);
        }
        LangCatalog.overrideForTests(loaded);
    }

    private static void assertKey(@Nonnull String expectedId, @Nonnull Message name) {
        assertKey(expectedId, name, "the name must be the shipped key");
    }

    private static void assertKey(@Nonnull String expectedId, @Nonnull Message name, @Nonnull String why) {
        assertEquals(expectedId, name.getMessageId(), why);
        assertNull(name.getRawText(), "a key is resolved by the player's own client, never frozen into text here");
    }

    private static void assertText(@Nonnull String expected, @Nonnull Message name) {
        assertText(expected, name, "the name must be the id tidied into words");
    }

    private static void assertText(@Nonnull String expected, @Nonnull Message name, @Nonnull String why) {
        assertEquals(expected, name.getRawText(), why);
        assertNull(name.getMessageId(), "there is no key to resolve, and inventing one would print it at the player");
    }

    // ==================== a category ====================

    @Test
    void anAuthoredTitleKeyTheCatalogueShipsWins() {
        ships("yourmod.fixture.category.combat", "yourmod.achievement.category.combat");
        AchievementCategoryAsset described = AchievementCategoryAsset.of("combat", 10, null,
                "fixture.category.combat", null);

        assertKey("yourmod.fixture.category.combat", CategoryNames.achievementCategory("combat", described));
    }

    @Test
    void anAuthoredTitleKeyNobodyShipsFallsToTheConventionKey() {
        ships("yourmod.achievement.category.combat");
        AchievementCategoryAsset described = AchievementCategoryAsset.of("combat", 10, null,
                "fixture.typo.combat", null);

        assertKey("yourmod.achievement.category.combat", CategoryNames.achievementCategory("combat", described));
    }

    @Test
    void theConventionKeyNamesACategoryNoFileDescribes() {
        ships("ziggfreedcommon.almanac.achievement.category.seasons");

        assertKey("ziggfreedcommon.almanac.achievement.category.seasons",
                CategoryNames.achievementCategory("Seasons", null));
    }

    @Test
    void aCategoryNoKeyNamesReadsAsItsIdTidied() {
        ships("yourmod.something.else");
        assertText("Boss Fights", CategoryNames.achievementCategory("boss_fights",
                AchievementCategoryAsset.of("boss_fights", null, null, null, null)));

        LangCatalog.overrideForTests(null);
        assertText("Boss Fights", CategoryNames.achievementCategory("boss_fights", null),
                "with no catalogue at all (a unit JVM, an early boot) the id still reads as words");
    }

    @Test
    void aQuestCategoryReadsTheSameLadderUnderItsOwnConvention() {
        ships("yourmod.quest.category.errands", "yourmod.fixture.quest.chores");

        assertKey("yourmod.quest.category.errands", CategoryNames.questCategory("Errands", null));
        assertKey("yourmod.fixture.quest.chores", CategoryNames.questCategory("chores",
                QuestCategoryAsset.of("chores", null, null, "fixture.quest.chores", null)));
        assertText("Side Jobs", CategoryNames.questCategory("side_jobs", null));
    }

    @Test
    void anAchievementKeyNeverNamesAQuestCategoryOrTheOtherWayRound() {
        ships("yourmod.achievement.category.errands");

        assertText("Errands", CategoryNames.questCategory("errands", null));
    }

    // ==================== a subcategory ====================

    @Test
    void aSeasonReadsItsTranslatedNameUnderItsCategory() {
        ships("ziggfreedcommon.almanac.achievement.category.seasons",
                "hallowseve.progression.achievement.category.seasons.hallows_eve");
        AchievementCategoryAsset seasons = AchievementCategoryAsset.of("seasons", null, null, null, null,
                null, null, true);

        assertKey("ziggfreedcommon.almanac.achievement.category.seasons",
                CategoryNames.achievementCategory("seasons", seasons));
        assertKey("hallowseve.progression.achievement.category.seasons.hallows_eve",
                CategoryNames.achievementSubcategory("Seasons", "Hallows_Eve", seasons),
                "the subcategory key is the category's own key plus the subcategory, whatever the casing");
    }

    @Test
    void aSeasonNoConventionKeyNamesReadsTheNameItsCalendarEventAlreadyShips() {
        ships("hallowseve.world.calendar.hallows_eve.name");
        AchievementCategoryAsset seasons = AchievementCategoryAsset.of("seasons", null, null, null, null,
                null, null, true);

        assertKey("hallowseve.world.calendar.hallows_eve.name",
                CategoryNames.achievementSubcategory("seasons", "hallows_eve", seasons, "calendar.hallows_eve.name"));
    }

    @Test
    void theConventionKeyOutranksTheCallersKeyWhenBothShip() {
        ships("hallowseve.world.calendar.hallows_eve.name",
                "hallowseve.progression.achievement.category.seasons.hallows_eve");

        assertKey("hallowseve.progression.achievement.category.seasons.hallows_eve",
                CategoryNames.achievementSubcategory("seasons", "hallows_eve", null, "calendar.hallows_eve.name"),
                "a key written to name this group says so on purpose; the event's own name is the fallback");
    }

    @Test
    void aSubcategoryNoKeyNamesReadsAsItsIdTidied() {
        ships("yourmod.something.else");

        assertText("Hallows Eve", CategoryNames.achievementSubcategory("seasons", "Hallows_Eve", null));
        assertText("Hallows Eve", CategoryNames.achievementSubcategory("seasons", "hallows_eve", null,
                "calendar.not_shipped.name"), "a caller's key nobody ships is no better than none");
        assertText("Hallows Eve", CategoryNames.achievementSubcategory("seasons", "hallows_eve", null, null));
    }

    @Test
    void aCategoryWithItsOwnTitleKeyNamesItsSubcategoriesUnderThatKeyFirst() {
        ships("yourmod.fixture.category.combat.melee", "yourmod.achievement.category.combat.melee");
        AchievementCategoryAsset combat = AchievementCategoryAsset.of("combat", 0, null,
                "fixture.category.combat", null);

        assertKey("yourmod.fixture.category.combat.melee",
                CategoryNames.achievementSubcategory("combat", "melee", combat));

        ships("yourmod.achievement.category.combat.melee");
        assertKey("yourmod.achievement.category.combat.melee",
                CategoryNames.achievementSubcategory("combat", "melee", combat),
                "an author's key for the subcategory that nobody ships falls to the convention");
    }

    // ==================== tidying an id ====================

    @Test
    void tidyingOpensEveryWordAndSpendsEverySeparator() {
        assertEquals("Combat", CategoryNames.humanize("combat"));
        assertEquals("Boss Fights", CategoryNames.humanize("boss_fights"));
        assertEquals("Ranged Combat", CategoryNames.humanize("ranged-combat"));
        assertEquals("Combat", CategoryNames.humanize("_combat_"),
                "a stray separator must not leave the label starting or ending in a space");
        assertEquals("", CategoryNames.humanize("  "));
    }

    // ==================== the accent ====================

    @Test
    void anAuthoredAccentIsTheAccent() {
        assertEquals("#e07b2a", CategoryNames.accent("seasons", "#E07B2A"));
    }

    @Test
    void anUnauthoredOrMalformedAccentTakesAStablePaletteColour() {
        String first = CategoryNames.accent("homegrown", null);
        assertTrue(CategoryNames.PALETTE.contains(first), first + " is not a palette colour");
        assertEquals(first, CategoryNames.accent("homegrown", null), "one category is always one colour");
        assertEquals(first, CategoryNames.accent("HomeGrown", "  "), "casing and a blank leaf change nothing");
        assertEquals(first, CategoryNames.accent("homegrown", "orange"),
                "a colour that is not #rrggbb is treated as unauthored, never pushed to a client");
    }

    @Test
    void thePaletteIsSixDigitColoursAndSpreadsAcrossCategories() {
        for (String colour : CategoryNames.PALETTE) {
            assertTrue(colour.matches("#[0-9a-f]{6}"), colour + " is not a #rrggbb colour");
        }
        Set<String> seen = new HashSet<>();
        for (String id : new String[]{"combat", "gathering", "crafting", "exploration", "quests", "misc",
                "seasons", "errands", "chores", "fishing"}) {
            seen.add(CategoryNames.accent(id, null));
        }
        assertTrue(seen.size() > 1, "every category hashing to one colour is no accent at all: " + seen);
    }
}
