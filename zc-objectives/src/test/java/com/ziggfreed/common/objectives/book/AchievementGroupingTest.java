package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.objectives.book.AchievementGrouping.HeaderWalk;

/**
 * The achievement tab's grouping rules, away from the page that paints them.
 *
 * <p>Each of the three has its own way of going wrong quietly. A label that falls to the wrong tier
 * shows a player a raw translation key, or an English word where a translated one was authored. A
 * bucket that sorts by its name rather than by "described first" puts "everything else" at the top
 * of the list, which reads as the taxonomy having been ignored. And a header drawn for a group with
 * no rows in it is a heading with nothing under it - the one thing a short catalogue cannot afford,
 * because that is most of what a short catalogue would be.
 *
 * <p>Everything asserted here is a decision, never a colour or a selector: the painting itself
 * cannot be reached from a test JVM (the page's engine base builds a logger in a static initializer
 * that refuses to load once a log manager is up), which is exactly why these rules live in a class
 * of their own.
 */
class AchievementGroupingTest {

    /** The one header key this page authors itself, rather than reading off a category. */
    private static final String UNCATEGORISED_KEY = "book.achievements.category.uncategorised";

    // ==================== the header label ====================

    @Test
    void anAuthoredTitleKeyWins() {
        AchievementCategoryAsset described = AchievementCategoryAsset.of(
                "combat", 10, null, "fixture.category.combat", null);

        Message label = AchievementGrouping.label("combat", described);

        assertEquals("fixture.category.combat", label.getMessageId(),
                "a file that says what this group is called must be what the header says");
        assertNull(label.getRawText(),
                "an authored key is resolved by the player's own client, never frozen into text"
                        + " here");
    }

    @Test
    void aDescribedCategoryWithNoTitleKeyFallsToTheConventionKey() {
        AchievementCategoryAsset described = AchievementCategoryAsset.of(
                "gathering", 20, "Fixture_Icon", null, null);

        Message label = AchievementGrouping.label("gathering", described);

        assertEquals("achievement.category.gathering", label.getMessageId(),
                "a category described without a title key is labelled by the convention the schema"
                        + " points authors at, so a translated line is still reachable");
    }

    @Test
    void aCategoryNothingDescribesReadsAsTidiedText() {
        Message label = AchievementGrouping.label("boss_fights", null);

        assertEquals("Boss Fights", label.getRawText(),
                "nothing describes this group, so the word content filed itself under is drawn as"
                        + " it stands: a word a player can read beats a key they cannot");
        assertNull(label.getMessageId(),
                "there is no key to resolve here, and inventing one would print it at the player");
    }

    @Test
    void humanizingOpensEveryWordAndSpendsEverySeparator() {
        assertEquals("Combat", AchievementGrouping.humanize("combat"));
        assertEquals("Boss Fights", AchievementGrouping.humanize("boss_fights"));
        assertEquals("Ranged Combat", AchievementGrouping.humanize("ranged-combat"));
        assertEquals("Combat", AchievementGrouping.humanize("_combat_"),
                "a stray separator must not leave the label starting or ending in a space");
    }

    // ==================== the uncategorised bucket ====================

    @Test
    void contentWithNoCategoryLandsInOneBucket() {
        assertEquals(AchievementGrouping.UNCATEGORISED, AchievementGrouping.bucketOf(null),
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
                "a category a file describes reads where that file says, ahead of one nothing"
                        + " describes");
        assertTrue(undescribed < uncategorised,
                "a named group nobody described is still a named group: it reads before"
                        + " 'everything else', not among it");
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
                "rows of one group must arrive together, described groups first by their own"
                        + " order, and the uncategorised bucket last");
    }

    // ==================== when a header is drawn ====================

    @Test
    void aHeaderIsDrawnOnlyForAGroupThatHasARow() {
        List<String> drawn = paint(
                row(0, "combat"),
                row(0, "combat"),
                row(0, "gathering"),
                row(1, "combat"));

        assertEquals(List.of(
                        "section:0", "category:combat", "row", "row",
                        "category:gathering", "row",
                        "section:1", "category:combat", "row"),
                drawn,
                "each header appears once, at the first row that needs it, and a category is"
                        + " headed again under the next lifecycle section");
        assertFalse(drawn.contains("category:mining"),
                "a category with nothing in this list has no header anywhere in it");
    }

    /**
     * The one header with no category word behind it. A raw English label here would be the only
     * untranslatable line on the page, and it is the line a bare server sees most, so the key it
     * needs is pinned beside the rule that reaches for it.
     */
    @Test
    void theUncategorisedHeaderIsALocalizedLineThisPageShips() throws IOException {
        // The book renders across a small family of sources; the rule holds wherever the header
        // is painted from, so the whole package is scanned rather than one named file.
        Path bookDir = Path.of("src", "main", "java", "com", "ziggfreed", "common",
                "objectives", "book");
        StringBuilder sources = new StringBuilder();
        try (var files = Files.list(bookDir)) {
            for (Path source : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sources.append(Files.readString(source, StandardCharsets.UTF_8));
            }
        }
        assertTrue(sources.toString().replaceAll("\\s+", "")
                        .contains("text(\"" + UNCATEGORISED_KEY + "\")"),
                "the uncategorised header must go through a translation key like every other line"
                        + " on this page");

        String english = Files.readString(Path.of("src", "main", "resources", "Server", "Languages",
                "en-US", "ziggfreedcommon.progression.lang"), StandardCharsets.UTF_8);
        assertTrue(english.contains(UNCATEGORISED_KEY + " ="),
                "the key has to be authored, or the header renders as the key itself");
    }

    @Test
    void aWalkOverNothingDrawsNothing() {
        assertEquals(List.of(), paint(),
                "an empty section is an empty section: the page shows its own empty line rather"
                        + " than a stack of headings");
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

    /** The page's own walk, recording what it would have drawn instead of drawing it. */
    @Nonnull
    private static List<String> paint(@Nonnull Row... rows) {
        List<String> drawn = new ArrayList<>();
        HeaderWalk<Integer> walk = new HeaderWalk<>();
        for (Row row : rows) {
            if (walk.enterSection(row.section())) {
                drawn.add("section:" + row.section());
            }
            if (walk.enterCategory(row.bucket())) {
                drawn.add("category:" + row.bucket());
            }
            drawn.add("row");
        }
        return drawn;
    }
}
