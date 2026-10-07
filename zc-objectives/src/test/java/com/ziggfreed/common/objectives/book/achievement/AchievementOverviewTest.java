package com.ziggfreed.common.objectives.book.achievement;

import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.DAY;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.NOW;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.ach;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.ids;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.read;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps.MilestoneView;
import com.ziggfreed.common.objectives.book.SeenMarks;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.CollectionTile;

/**
 * The Overview as data: the hero's numbers, the milestone card, the category tiles (counts with feats out, the
 * "On now" pill, the "new" mark) and the three strips (recent order, the nearest exclusions, pin order).
 */
class AchievementOverviewTest {

    private AchievementFixture f;

    @BeforeEach
    void setUp() {
        f = new AchievementFixture();
        f.category(AchievementCategoryAsset.of("combat", 1, "Weapon_Sword_Iron", null, null, "#c0504d", null, null));
        f.category(AchievementCategoryAsset.of("seasons", 2, "Pumpkin", null, null, null, null, true));
        f.category(AchievementCategoryAsset.of("festivals", 3, null, null, null, null, "spring_fair", null));
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    @Test
    void theHeroCountsWhatWasEarnedWithFeatsOut() {
        Achievement a = f.add(ach("a", "combat", null, 1).points(10), "A");
        f.add(ach("b", "combat", null, 1).points(10), "B");
        Achievement feat = f.add(ach("feat", "combat", null, 1).featOfStrength(true), "Feat");
        Achievement waiting = f.add(ach("w", "combat", null, 1).claimReward(RewardSpec.of("item", "Name", "Sword")), "W");
        f.add(ach("retired", "combat", null, 1).available(false), "Retired");
        f.earn(a, NOW - DAY);
        f.earn(feat, NOW - DAY);
        f.earn(waiting, NOW - DAY);

        AchievementOverview.Overview overview = f.reader().overview(5, 5);

        assertEquals(2, overview.earned(), "a and w; the feat counts on its own shelf");
        assertEquals(3, overview.total(), "a, b and w; the retired one nobody earned counts nowhere");
        assertNotNull(overview.completion());
        assertEquals(2, overview.completion().current());
        assertEquals(1, overview.rewardsWaiting());
        assertNull(overview.next(), "no ladder, no milestone card");
    }

    @Test
    void tilesCountEachCategoryWithFeatsOutAndAFeatsTileComesLast() {
        Achievement a = f.add(ach("a", "combat", null, 1), "A");
        f.add(ach("b", "combat", null, 1), "B");
        Achievement feat = f.add(ach("feat", "combat", null, 1).featOfStrength(true), "Feat");
        Achievement snow = f.add(ach("snow", "seasons", "winter", 1), "Snow");
        f.add(ach("loose", null, null, 1), "Loose");
        f.earn(a, NOW - DAY);
        f.earn(feat, NOW - DAY);
        f.earn(snow, NOW - DAY);

        List<CollectionTile> tiles = f.reader().overview(5, 5).tiles();

        assertEquals(List.of("combat", "seasons", AchievementOverview.FEATS_TILE), tileIds(tiles),
                "taxonomy order, no tile for content with no category, the Feats tile last");
        CollectionTile combat = tiles.get(0);
        assertEquals("Combat", read(combat.name()));
        assertEquals("1 / 2", read(combat.count()));
        assertFalse(combat.complete());
        assertEquals("Weapon_Sword_Iron", combat.picture().itemId());
        assertEquals("#c0504d", combat.accentHex());
        assertTrue(tiles.get(1).complete(), "every Seasons achievement earned: the check shows");
        CollectionTile feats = tiles.get(2);
        assertEquals("Feats of Strength", read(feats.name()));
        assertEquals("1 earned", read(feats.count()));
        assertEquals(tileIds(tiles), tileIds(f.reader().tiles()), "the dropdown reads the same tiles");
    }

    @Test
    void noFeatEarnedMeansNoFeatsTile() {
        f.add(ach("a", "combat", null, 1), "A");
        f.add(ach("feat", "combat", null, 1).featOfStrength(true), "Feat");

        assertEquals(List.of("combat"), tileIds(f.reader().tiles()));
    }

    @Test
    void aCategoryOnACalendarEventSaysOnNowWhileItRuns() {
        f.add(ach("fair", "festivals", null, 1), "Fair");
        f.add(ach("slash", "combat", null, 1), "Slash");

        assertNull(tile(f.reader().tiles(), "festivals").badge(), "no run, no pill");

        f.calendar = running("spring_fair");
        CollectionTile festivals = tile(f.reader().tiles(), "festivals");
        assertNotNull(festivals.badge());
        assertEquals("On now", read(festivals.badge().label()));
        assertNull(tile(f.reader().tiles(), "combat").badge());
    }

    @Test
    void aCategoryWhoseSubcategoriesAreEventsSaysOnNowWhileAnyOfThemRuns() {
        f.add(ach("ghoul", "seasons", "hallows_eve", 1), "Ghoul");
        f.add(ach("snow", "seasons", "winter", 1), "Snow");

        assertNull(tile(f.reader().tiles(), "seasons").badge());

        f.calendar = running("hallows_eve");
        assertNotNull(tile(f.reader().tiles(), "seasons").badge(), "Hallow's Eve runs, so Seasons is on now");
    }

    @Test
    void aTileIsNewWhenSomethingThereWasEarnedSinceItWasLastSeen() {
        Achievement a = f.add(ach("a", "combat", null, 1), "A");
        Achievement snow = f.add(ach("snow", "seasons", "winter", 1), "Snow");
        f.earn(a, NOW - DAY);
        f.earn(snow, NOW - 10 * DAY);

        assertFalse(tile(f.reader().tiles(), "combat").unseen(), "no marks kept: nothing reads as new");

        f.seen = new SeenMarks() {
            @Override
            public long seenAt(@Nullable Subject subject, @Nonnull String category) {
                return NOW - 5 * DAY;
            }

            @Override
            public void markSeen(@Nullable Subject subject, @Nonnull String category, long nowMs) {
            }
        };
        List<CollectionTile> tiles = f.reader().tiles();
        assertTrue(tile(tiles, "combat").unseen(), "earned a day ago, seen five days ago");
        assertFalse(tile(tiles, "seasons").unseen(), "earned before the last look");
    }

    @Test
    void recentlyEarnedIsNewestFirstAndDated() {
        Achievement old = f.add(ach("old", "combat", null, 1), "Old");
        Achievement mid = f.add(ach("mid", "combat", null, 1), "Mid");
        Achievement fresh = f.add(ach("fresh", "combat", null, 1), "Fresh");
        f.earn(old, NOW - 30 * DAY);
        f.earn(mid, NOW - 10 * DAY);
        f.earn(fresh, NOW - DAY);

        AchievementOverview.Overview overview = f.reader().overview(2, 5);

        assertEquals(List.of("fresh", "mid"), ids(overview.recent()), "newest first, as many as asked");
        assertEquals(LocalDate.ofInstant(Instant.ofEpochMilli(NOW - DAY), ZoneId.systemDefault()).toString(),
                read(overview.recent().get(0).value()), "a recent row is dated");
        assertNull(overview.recent().get(0).meta(), "strips are compact rows");
    }

    @Test
    void nearlyThereIsClosestFirstAndLeavesOutWhatNobodyShouldChase() {
        Achievement half = f.add(ach("half", "combat", null, 10), "Half");
        Achievement most = f.add(ach("most", "combat", null, 10), "Most");
        f.add(ach("none", "combat", null, 10), "None");
        Achievement hidden = f.add(ach("hidden", "combat", null, 10).hidden(true), "Hidden");
        Achievement feat = f.add(ach("feat", "combat", null, 10).featOfStrength(true), "Feat");
        Achievement lost = f.add(ach("lost", "combat", null, 10).serverFirst(true), "Lost");
        Achievement retired = f.add(ach("retired", "combat", null, 10).available(false), "Retired");
        for (Achievement a : List.of(half, hidden, feat, lost, retired)) {
            f.progress(a, 0, 5);
        }
        f.progress(most, 0, 9);
        f.deps = ObjectiveBookDeps.builder()
                .firstClaims((id, viewer) -> "lost".equals(id) ? new ObjectiveBookDeps.FirstClaim("Ana", false) : null)
                .build();

        assertEquals(List.of("most", "half"), ids(f.reader().overview(5, 5).nearly()),
                "no progress, hidden, feats, a server first someone else won and retired ones stay out");
    }

    @Test
    void pinnedKeepsThePinOrder() {
        Achievement a = f.add(ach("a", "combat", null, 1), "A");
        Achievement b = f.add(ach("b", "combat", null, 1), "B");
        f.pin(b, NOW - 2 * DAY);
        f.pin(a, NOW - DAY);

        assertEquals(List.of("b", "a"), ids(f.reader().overview(5, 5).pinned()), "oldest pin first");
    }

    @Test
    void theMilestoneCardIsTheNextUnclaimedRung() {
        Achievement a = f.add(ach("a", "combat", null, 1).points(830), "A");
        f.earn(a, NOW - DAY);
        List<MilestoneView> ladder = List.of(
                rung(500, true, true, false),
                rung(1000, false, false, false),
                rung(2000, false, false, false),
                rung(3000, false, false, false));

        AchievementOverview.MilestoneCard card = f.reader().overview(5, 5, ladder).next();

        assertNotNull(card);
        assertEquals(1000, card.threshold());
        assertEquals("1000 points: Rung 1000", read(card.title()));
        assertEquals(830, card.progress().current());
        assertEquals(1000, card.progress().total());
        assertEquals("Boost x1000", read(card.rewards()));
        assertFalse(card.claimable());
        assertFalse(card.allCollected());
        assertEquals(2, card.moreAfter());
        assertEquals("2 more after this", read(card.after()));
        assertEquals(List.of(500, 1000, 2000, 3000), card.thresholds());
    }

    @Test
    void aClaimableRungOffersCollectAndAFullyCollectedLadderSaysSo() {
        AchievementOverview.MilestoneCard ready = f.reader().overview(5, 5,
                List.of(rung(500, true, false, true))).next();
        assertNotNull(ready);
        assertTrue(ready.claimable());
        assertNull(ready.after(), "nothing follows the last rung");

        AchievementOverview.MilestoneCard done = f.reader().overview(5, 5,
                List.of(rung(500, true, true, false), rung(1000, true, true, false))).next();
        assertNotNull(done);
        assertTrue(done.allCollected());
        assertEquals("All milestones collected", read(done.title()));
        assertEquals(1000, done.threshold());
        assertFalse(done.claimable());
    }

    // ==================== helpers ====================

    @Nonnull
    private static MilestoneView rung(int threshold, boolean unlocked, boolean claimed, boolean claimable) {
        return new MilestoneView(threshold, Msg.raw("Rung " + threshold), null,
                List.of(RewardSpec.of("boost", "Name", "Boost x" + threshold)), unlocked, claimed, claimable);
    }

    @Nonnull
    private static List<String> tileIds(@Nonnull List<CollectionTile> tiles) {
        List<String> out = new ArrayList<>();
        for (CollectionTile tile : tiles) {
            out.add(tile.id());
        }
        return out;
    }

    @Nonnull
    private static CollectionTile tile(@Nonnull List<CollectionTile> tiles, @Nonnull String id) {
        for (CollectionTile tile : tiles) {
            if (tile.id().equals(id)) {
                return tile;
            }
        }
        throw new AssertionError("no tile " + id + " in " + tileIds(tiles));
    }

    /** A calendar on which every event in {@code eventIds} has a run on now. */
    @Nonnull
    private static OccurrenceSource running(@Nonnull String... eventIds) {
        Set<String> on = Set.of(eventIds);
        return new OccurrenceSource() {
            @Override
            public boolean isEnabled(@Nonnull String eventId) {
                return on.contains(eventId);
            }

            @Nullable
            @Override
            public Occurrence live(@Nonnull String eventId, long nowMs) {
                return on.contains(eventId) ? new Occurrence(eventId, 2026, nowMs - DAY, nowMs + DAY) : null;
            }

            @Nonnull
            @Override
            public List<Occurrence> history(@Nonnull String eventId, long nowMs) {
                Occurrence run = live(eventId, nowMs);
                return run == null ? List.of() : List.of(run);
            }
        };
    }
}
