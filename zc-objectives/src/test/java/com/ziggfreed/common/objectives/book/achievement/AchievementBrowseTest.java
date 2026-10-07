package com.ziggfreed.common.objectives.book.achievement;

import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.DAY;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.NOW;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.ach;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.ids;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.read;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.rung;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;

/**
 * The Browse list's rules: its sections (Pinned, one per category or subcategory, Feats), the ladder collapse
 * and the search that bypasses it, the cap before "Show N more", and what each status keeps.
 */
class AchievementBrowseTest {

    private AchievementFixture f;

    @BeforeEach
    void setUp() {
        f = new AchievementFixture();
        f.category(AchievementCategoryAsset.of("combat", 1, null, null, null));
        f.category(AchievementCategoryAsset.of("seasons", 2, null, null, List.of("winter", "hallows_eve")));
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    @Test
    void everyCategoryReadsAsOneSectionInTaxonomyOrderWithEverythingElseLast() {
        f.add(ach("loose", null, null, 1), "Loose");
        f.add(ach("ghoul", "seasons", "hallows_eve", 1), "Ghoul");
        f.add(ach("slash", "combat", null, 1), "Slash");
        f.add(ach("homegrown", "zzz_home", null, 1), "Homegrown");

        LedgerModel model = f.reader().browse(BrowseFilter.NONE);

        assertEquals(List.of("c.combat", "c.seasons", "c.zzz_home", "other"), sectionIds(model));
        assertEquals("Combat", read(section(model, "c.combat").label()));
        assertEquals("Other", read(section(model, "other").label()), "no category: the page's own Other line");
        assertEquals("slash", model.firstSelectable());
    }

    @Test
    void aChosenCategoryGroupsBySubcategoryInTheFilesOrder() {
        f.add(ach("ghoul", "seasons", "hallows_eve", 1), "Ghoul");
        f.add(ach("snow", "seasons", "winter", 1), "Snow");
        f.add(ach("plain", "seasons", null, 1), "Plain");
        f.add(ach("slash", "combat", null, 1), "Slash");

        LedgerModel model = f.reader().browse(new BrowseFilter("seasons", null, null, null));

        assertEquals(List.of("c.seasons", "s.seasons.winter", "s.seasons.hallows_eve"), sectionIds(model),
                "rows with no subcategory first, then the file's own order");
        assertEquals("Hallows Eve", read(section(model, "s.seasons.hallows_eve").label()));
        assertFalse(rowIds(model).contains("slash"), "another category's rows stay out");
    }

    @Test
    void aSubcategoryHeadReadsItsConventionKeyWhenOneShips() {
        f.add(ach("ghoul", "seasons", "hallows_eve", 1), "Ghoul");
        Map<String, String> loaded = new LinkedHashMap<>();
        loaded.put("seasonspack.achievement.category.seasons.hallows_eve", "Hallow's Eve");
        LangCatalog.overrideForTests(loaded);

        LedgerModel model = f.reader().browse(new BrowseFilter("seasons", null, null, null));

        assertEquals("seasonspack.achievement.category.seasons.hallows_eve",
                section(model, "s.seasons.hallows_eve").label().getMessageId(),
                "the breadcrumb and the head read the translated name, apostrophe and all");
    }

    @Test
    void pinnedRowsLeadInTheirOwnSectionAndAreNotRepeated() {
        Achievement slash = f.add(ach("slash", "combat", null, 1), "Slash");
        f.add(ach("parry", "combat", null, 1), "Parry");
        f.pin(slash, NOW - DAY);

        LedgerModel model = f.reader().browse(BrowseFilter.NONE);

        assertEquals("pinned", sectionIds(model).get(0));
        assertEquals(List.of("slash"), ids(section(model, "pinned").rows()));
        assertEquals(List.of("parry"), ids(section(model, "c.combat").rows()), "a row id is unique across the list");
        assertEquals(rowIds(model).size(), new HashSet<>(rowIds(model)).size());
    }

    @Test
    void earnedFeatsHaveTheirOwnClosedSectionLast() {
        Achievement feat = f.add(ach("old_guard", "combat", null, 1).featOfStrength(true), "Old Guard");
        f.add(ach("unearned_feat", "combat", null, 1).featOfStrength(true), "Unearned");
        f.add(ach("slash", "combat", null, 1), "Slash");
        f.earn(feat, NOW - DAY);

        LedgerModel model = f.reader().browse(BrowseFilter.NONE);

        assertEquals("feats", sectionIds(model).get(sectionIds(model).size() - 1));
        LedgerSection feats = section(model, "feats");
        assertEquals("Feats of Strength", read(feats.label()));
        assertEquals(List.of("old_guard"), ids(feats.rows()), "a feat lists once earned");
        assertFalse(feats.openByDefault(), "the feats shelf opens on request");
    }

    @Test
    void aLadderCollapsesToTheRungBeingClimbedButPinnedRungsStay() {
        Achievement t1 = f.add(rung("miner_1", "combat", "miner", 1), "Miner I");
        f.add(rung("miner_2", "combat", "miner", 2), "Miner II");
        f.add(rung("miner_3", "combat", "miner", 3), "Miner III");
        f.earn(t1, NOW - DAY);

        assertEquals(List.of("miner_2"), rowIds(f.reader().browse(BrowseFilter.NONE)),
                "the lowest rung not yet earned stands for the ladder");

        Achievement t3 = f.reader().achievement("miner_3");
        assertNotNull(t3);
        f.pin(t3, NOW - DAY);
        assertEquals(Set.of("miner_2", "miner_3"), new HashSet<>(rowIds(f.reader().browse(BrowseFilter.NONE))),
                "a pinned rung stays in view");
    }

    @Test
    void aFinishedLadderShowsItsTopRung() {
        Achievement t1 = f.add(rung("miner_1", "combat", "miner", 1), "Miner I");
        Achievement t2 = f.add(rung("miner_2", "combat", "miner", 2), "Miner II");
        f.earn(t1, NOW - 2 * DAY);
        f.earn(t2, NOW - DAY);

        assertEquals(List.of("miner_2"), rowIds(f.reader().browse(BrowseFilter.NONE)));
    }

    @Test
    void aSearchBypassesTheCollapseAndOpensEverySection() {
        f.add(rung("miner_1", "combat", "miner", 1), "Miner I");
        f.add(rung("miner_2", "combat", "miner", 2), "Miner II");
        Achievement feat = f.add(ach("old_miner", "combat", null, 1).featOfStrength(true), "Old Miner");
        f.add(ach("slash", "combat", null, 1), "Slash");
        f.earn(feat, NOW - DAY);

        LedgerModel model = f.reader().browse(new BrowseFilter(null, null, null, "miner"));

        assertEquals(Set.of("miner_1", "miner_2", "old_miner"), new HashSet<>(rowIds(model)),
                "every rung can be found, and only what matches lists");
        for (LedgerSection section : model.sections()) {
            assertTrue(section.openByDefault(), "a search opens " + section.id());
        }
    }

    @Test
    void aSearchReadsTheFlavourAndACapstonesChildren() {
        f.flavors.put("lantern", "Light every lantern in the hollow.");
        f.add(ach("lantern", "combat", null, 1), "Lantern Keeper");
        f.add(ach("child", "combat", null, 1), "Pumpkin Smasher");
        f.add(Achievement.builder("cap").category("combat").metaChildren(List.of("child")), "The Hallowed");

        assertEquals(List.of("lantern"), rowIds(f.reader().browse(new BrowseFilter(null, null, null, "hollow"))));
        assertTrue(rowIds(f.reader().browse(new BrowseFilter(null, null, null, "pumpkin"))).contains("cap"));
    }

    @Test
    void aSectionCapsAtFortyBeforeShowMore() {
        for (int i = 0; i < 45; i++) {
            f.add(ach(String.format(Locale.ROOT, "a%02d", i), "combat", null, 1), "A" + i);
        }

        LedgerSection combat = section(f.reader().browse(BrowseFilter.NONE), "c.combat");

        assertEquals(45, combat.rows().size(), "every row is in the model");
        assertEquals(LedgerSection.DEFAULT_CAP, combat.cap(), "the painter shows forty, then Show 5 more");
    }

    @Test
    void everyStatusKeepsItsOwnRows() {
        Achievement earned = f.add(ach("earned", "combat", null, 1), "Earned");
        Achievement waiting = f.add(ach("waiting", "combat", null, 1)
                .claimReward(RewardSpec.of("item", "Name", "Sword")), "Waiting");
        f.add(ach("progress", "combat", null, 5), "Progress");
        Achievement feat = f.add(ach("feat", "combat", null, 1).featOfStrength(true), "Feat");
        f.earn(earned, NOW - 3 * DAY);
        f.earn(waiting, NOW - 2 * DAY);
        f.earn(feat, NOW - DAY);

        AchievementReader reader = f.reader();

        assertEquals(Set.of("earned", "waiting", "progress", "feat"), status(reader, "all"));
        assertEquals(Set.of("progress"), status(reader, "progress"));
        assertEquals(Set.of("earned", "waiting", "feat"), status(reader, "earned"));
        assertEquals(Set.of("waiting"), status(reader, "waiting"));
        assertEquals(Set.of("feat"), status(reader, "feats"));
        assertTrue(section(reader.browse(new BrowseFilter(null, "feats", null, null)), "feats").openByDefault(),
                "the Feats status opens its shelf");
        assertEquals(status(reader, "all"), status(reader, "no_such_status"), "an unknown status reads as all");
    }

    @Test
    void whatIsNotEarnedListsOnlyInCirculationAndInSight() {
        f.add(ach("retired", "combat", null, 1).available(false), "Retired");
        f.add(ach("secret", "combat", null, 1).hidden(true), "Secret");
        Achievement kept = f.add(ach("kept", "combat", null, 1).available(false), "Kept");
        f.earn(kept, NOW - DAY);

        assertEquals(List.of("kept"), rowIds(f.reader().browse(BrowseFilter.NONE)),
                "what a player earned is theirs whatever its circulation");
    }

    @Test
    void theDefaultSortPutsWhatIsInProgressFirstAndAzSortsByName() {
        Achievement done = f.add(ach("done", "combat", null, 1).sortOrder(0), "Alpha");
        f.add(ach("later", "combat", null, 1).sortOrder(2), "Bravo");
        f.add(ach("sooner", "combat", null, 1).sortOrder(1), "Charlie");
        f.earn(done, NOW - DAY);

        assertEquals(List.of("sooner", "later", "done"), rowIds(f.reader().browse(BrowseFilter.NONE)));
        assertEquals(List.of("done", "later", "sooner"),
                rowIds(f.reader().browse(new BrowseFilter(null, null, "az", null))));
    }

    @Test
    void closestFirstSortsWhatIsUnearnedByHowFarAlong() {
        Achievement half = f.add(ach("half", "combat", null, 10), "Half");
        Achievement most = f.add(ach("most", "combat", null, 10), "Most");
        f.add(ach("none", "combat", null, 10), "None");
        f.progress(half, 0, 5);
        f.progress(most, 0, 9);

        assertEquals(List.of("most", "half", "none"),
                rowIds(f.reader().browse(new BrowseFilter(null, null, "closest", null))));
    }

    // ==================== helpers ====================

    @Nonnull
    private Set<String> status(@Nonnull AchievementReader reader, @Nonnull String status) {
        return new HashSet<>(rowIds(reader.browse(new BrowseFilter(null, status, null, null))));
    }

    @Nonnull
    private static List<String> sectionIds(@Nonnull LedgerModel model) {
        List<String> out = new ArrayList<>();
        for (LedgerSection section : model.sections()) {
            out.add(section.id());
        }
        return out;
    }

    @Nonnull
    private static List<String> rowIds(@Nonnull LedgerModel model) {
        List<String> out = new ArrayList<>();
        for (LedgerSection section : model.sections()) {
            for (LedgerRow row : section.rows()) {
                out.add(row.id());
            }
        }
        return out;
    }

    @Nonnull
    private static LedgerSection section(@Nonnull LedgerModel model, @Nonnull String id) {
        LedgerSection found = find(model, id);
        assertNotNull(found, "no section " + id + " in " + sectionIds(model));
        return found;
    }

    @Nullable
    private static LedgerSection find(@Nonnull LedgerModel model, @Nonnull String id) {
        for (LedgerSection section : model.sections()) {
            if (section.id().equals(id)) {
                return section;
            }
        }
        return null;
    }
}
