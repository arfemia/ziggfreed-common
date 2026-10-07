package com.ziggfreed.common.objectives.book.achievement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.progress.CategoryNames;

/**
 * The achievement tab's grouping rules, away from the page that paints them.
 *
 * <p>Each has its own way of going wrong quietly. A label that falls to the wrong tier shows a player a raw
 * translation key, or an English word where a translated one was authored. A bucket that sorts by its name
 * rather than by "described first" puts "everything else" at the top of the list, which reads as the taxonomy
 * having been ignored.
 *
 * <p>The book names every category through {@code CategoryNames} (zc-progression, which holds the whole ladder
 * and its own test); the cases here pin the ones the book leans on, with a fixture catalogue handed to
 * {@link LangCatalog} and no production lang file read, except the one header key this page ships itself.
 */
class AchievementGroupingTest {

    /** The one header key this page authors itself, rather than reading off a category. */
    private static final String UNCATEGORISED_KEY = "book.achievements.category.uncategorised";

    @BeforeEach
    @AfterEach
    void realCatalogue() {
        LangCatalog.overrideForTests(null);
    }

    /** A loaded catalogue shipping exactly these full ids. */
    private static void ships(@Nonnull String... fullIds) {
        Map<String, String> loaded = new LinkedHashMap<>();
        for (String id : fullIds) {
            loaded.put(id, "text of " + id);
        }
        LangCatalog.overrideForTests(loaded);
    }

    // ==================== the header label ====================

    @Test
    void anAuthoredTitleKeyWins() {
        ships("fixturemod.fixture.category.combat", "fixturemod.achievement.category.combat");
        AchievementCategoryAsset described = AchievementCategoryAsset.of(
                "combat", 10, null, "fixture.category.combat", null);

        Message label = CategoryNames.achievementCategory("combat", described);

        assertEquals("fixturemod.fixture.category.combat", label.getMessageId(),
                "a file that says what this group is called must be what the header says");
        assertNull(label.getRawText(),
                "an authored key is resolved by the player's own client, never frozen into text here");
    }

    @Test
    void aDescribedCategoryWithNoTitleKeyFallsToTheConventionKey() {
        ships("fixturemod.achievement.category.gathering");
        AchievementCategoryAsset described = AchievementCategoryAsset.of(
                "gathering", 20, "Fixture_Icon", null, null);

        Message label = CategoryNames.achievementCategory("gathering", described);

        assertEquals("fixturemod.achievement.category.gathering", label.getMessageId(),
                "a category described without a title key is labelled by the convention the schema points"
                        + " authors at, so a translated line is still reachable");
    }

    @Test
    void whenNoKeyShipsTheTidiedIdIsTheName() {
        AchievementCategoryAsset described = AchievementCategoryAsset.of(
                "gathering", 20, "Fixture_Icon", "fixture.category.gathering", null);

        Message label = CategoryNames.achievementCategory("gathering", described);

        assertEquals("Gathering", label.getRawText(),
                "a key nobody ships would print at the player; the word content filed itself under is"
                        + " drawn tidied instead");
        assertNull(label.getMessageId());
    }

    @Test
    void aCategoryNothingDescribesReadsAsTidiedText() {
        Message label = CategoryNames.achievementCategory("boss_fights", null);

        assertEquals("Boss Fights", label.getRawText(),
                "nothing describes this group, so the word content filed itself under is drawn as it stands:"
                        + " a word a player can read beats a key they cannot");
        assertNull(label.getMessageId(),
                "there is no key to resolve here, and inventing one would print it at the player");
    }

    @Test
    void humanizingOpensEveryWordAndSpendsEverySeparator() {
        assertEquals("Combat", CategoryNames.humanize("combat"));
        assertEquals("Boss Fights", CategoryNames.humanize("boss_fights"));
        assertEquals("Ranged Combat", CategoryNames.humanize("ranged-combat"));
        assertEquals("Combat", CategoryNames.humanize("_combat_"),
                "a stray separator must not leave the label starting or ending in a space");
    }

    // ==================== the uncategorised bucket ====================

    @Test
    void contentWithNoCategoryLandsInOneBucket() {
        assertEquals(AchievementGrouping.UNCATEGORISED, AchievementGrouping.bucketOf((String) null),
                "content another mod folded reads as belonging to no group");
        assertEquals(AchievementGrouping.UNCATEGORISED, AchievementGrouping.bucketOf("   "),
                "a blank category is no category, and must not open a group of its own");
        assertEquals("combat", AchievementGrouping.bucketOf("combat"));
    }

    @Test
    void theUncategorisedBucketReadsAfterEveryNamedGroup() {
        int described = AchievementGrouping.rankOf("combat", 10);
        int undescribed = AchievementGrouping.rankOf("zzz_homegrown", Integer.MAX_VALUE);
        int uncategorised = AchievementGrouping.rankOf(null, Integer.MAX_VALUE);

        assertTrue(described < undescribed,
                "a category a file describes reads where that file says, ahead of one nothing describes");
        assertTrue(undescribed < uncategorised,
                "a named group nobody described is still a named group: it reads before 'everything else',"
                        + " not among it");
    }

    @Test
    void theSortLeavesEveryGroupContiguousAndEverythingElseLast() {
        List<String> painted = order(
                row(0, null),
                row(0, "gathering"),
                row(0, "combat"),
                row(0, "zzz_homegrown"),
                row(0, "combat"));

        assertEquals(List.of("combat", "combat", "gathering", "zzz_homegrown",
                        AchievementGrouping.UNCATEGORISED), painted,
                "rows of one group must arrive together, described groups first by their own order, and the"
                        + " uncategorised bucket last");
    }

    /**
     * The one header with no category word behind it. A raw English label here would be the only
     * untranslatable line on the page, and it is the line a bare server sees most, so the key it
     * needs is pinned beside the rule that reaches for it.
     */
    @Test
    void theUncategorisedHeaderIsALocalizedLineThisPageShips() throws IOException {
        // The achievement readers live across a small family of sources; the rule holds wherever the
        // header is built, so the whole package is scanned rather than one named file.
        Path dir = Path.of("src", "main", "java", "com", "ziggfreed", "common",
                "objectives", "book", "achievement");
        StringBuilder sources = new StringBuilder();
        try (var files = Files.list(dir)) {
            for (Path source : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sources.append(Files.readString(source, StandardCharsets.UTF_8));
            }
        }
        assertTrue(sources.toString().replaceAll("\\s+", "")
                        .contains("text(\"" + UNCATEGORISED_KEY + "\")"),
                "the uncategorised header must go through a translation key like every other line on this"
                        + " page");

        String english = Files.readString(Path.of("src", "main", "resources", "Server", "Languages",
                "en-US", "ziggfreedcommon.progression.lang"), StandardCharsets.UTF_8);
        assertTrue(english.contains(UNCATEGORISED_KEY + " ="),
                "the key has to be authored, or the header renders as the key itself");
    }

    // ==================== fixtures ====================

    /** A row's grouping identity, which is all these rules ever read. */
    private record Row(int section, @Nonnull String bucket, int rank) {
    }

    @Nonnull
    private static Row row(int section, @Nullable String category) {
        // A described fixture category ranks by its own order; everything else takes the taxonomy's
        // "nothing describes this" answer, exactly as the page hands it over.
        int described = switch (category == null ? "" : category) {
            case "combat" -> 10;
            case "gathering" -> 20;
            default -> Integer.MAX_VALUE;
        };
        return new Row(section, AchievementGrouping.bucketOf(category),
                AchievementGrouping.rankOf(category, described));
    }

    /** The page's own sort, over the fixture rows: section, rank, bucket, then id. */
    @Nonnull
    private static List<String> order(@Nonnull Row... rows) {
        List<Row> sorted = new ArrayList<>(List.of(rows));
        sorted.sort(Comparator.comparingInt((Row r) -> r.section())
                .thenComparingInt(Row::rank)
                .thenComparing(Row::bucket));
        List<String> out = new ArrayList<>();
        for (Row row : sorted) {
            out.add(row.bucket());
        }
        return out;
    }
}
