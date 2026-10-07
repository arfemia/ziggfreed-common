package com.ziggfreed.common.objectives.book.achievement;

import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.DAY;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.NOW;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.ach;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.read;
import static com.ziggfreed.common.objectives.book.achievement.AchievementFixture.rung;
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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.book.BookActions;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;

/**
 * One achievement read into a row and a page, state by state: what a player sees for an achievement in
 * progress, earned, waiting to be collected, a feat, a ladder rung, a capstone, a server first and a seasonal
 * copy. Mechanics only: the words come from a fixed fixture catalogue, never the shipped lang file.
 */
class AchievementReaderTest {

    private AchievementFixture f;

    @BeforeEach
    void setUp() {
        f = new AchievementFixture();
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    @Test
    void anAchievementInProgressReadsItsCountAndCarriesABar() {
        Achievement ghouls = f.add(ach("ghoul_breaker", "combat", null, 50).points(10), "Ghoul Breaker");
        f.progress(ghouls, 0, 12);

        LedgerRow row = f.reader().row(ghouls);

        assertEquals("ghoul_breaker", row.id());
        assertEquals("Ghoul Breaker", read(row.title()));
        assertEquals(Tone.ACTIVE, row.tone());
        assertEquals("In progress", read(row.state()));
        assertEquals("12 / 50", read(row.meta()), "a single count reads as its tally");
        assertEquals("10 pts", read(row.value()), "points ride the trail in plain ink");
        assertNotNull(row.progress(), "a row in progress carries its bar");
        assertEquals(12, row.progress().current());
        assertEquals(50, row.progress().total());
        assertFalse(row.faint());
        assertEquals(Mark.NONE, row.mark());
    }

    @Test
    void aMultiStepAchievementCountsItsSteps() {
        Achievement trio = f.add(Achievement.builder("trio").category("combat")
                .criterion(ObjectiveDef.builder("0", "KILL").amount(1).build())
                .criterion(ObjectiveDef.builder("1", "KILL").amount(1).build())
                .criterion(ObjectiveDef.builder("2", "KILL").amount(1).build()), "Trio");
        f.progress(trio, 0, 1);
        f.progress(trio, 2, 1);

        AchievementReader reader = f.reader();

        assertEquals("2 / 3 steps", read(reader.row(trio).meta()));
        DetailBlock criteria = block(reader.page(trio), "criteria");
        assertNotNull(criteria, "more than one criterion shows the Criteria block");
        assertEquals("Criteria", read(criteria.label()));
        assertEquals(List.of(Tick.DONE, Tick.AHEAD, Tick.DONE), ticks(criteria));
        assertEquals("1 / 1", read(criteria.lines().get(0).count()));
        assertEquals("trio step 1", read(criteria.lines().get(1).text()));
    }

    @Test
    void anEarnedAchievementReadsItsDateFaintWithNoBar() {
        Achievement first = f.add(ach("first_blood", "combat", null, 1), "First Blood");
        f.earn(first, NOW - 3 * DAY);

        AchievementReader reader = f.reader();
        LedgerRow row = reader.row(first);

        assertEquals(Tone.DONE, row.tone());
        assertEquals("Done", read(row.state()));
        assertEquals("Earned on " + date(NOW - 3 * DAY), read(row.meta()));
        assertNull(row.progress(), "a finished row draws no bar");
        assertTrue(row.faint(), "a finished row's meta reads in the faint ink");

        DetailView page = reader.page(first);
        assertEquals("Earned on " + date(NOW - 3 * DAY), read(page.hint()));
        assertNull(page.action(ActionSlot.PRIMARY), "nothing to collect, no Collect");
        assertNotNull(page.progress());
        assertTrue(page.progress().complete(), "an earned page's bar is full");
    }

    @Test
    void anEarnedAchievementWithRewardsWaitingSaysCollectAndOffersIt() {
        Achievement chest = f.add(ach("chest", "combat", null, 1)
                .claimReward(RewardSpec.of("item", "Name", "Iron Sword"))
                .autoReward(RewardSpec.of("xp", "Name", "250 XP")), "Chest");
        f.earn(chest, NOW - DAY);

        AchievementReader reader = f.reader();
        LedgerRow row = reader.row(chest);
        assertEquals(Tone.COLLECT, row.tone());
        assertEquals("Collect", read(row.state()));
        assertFalse(row.faint(), "a row with rewards waiting asks for attention");
        assertTrue(reader.waiting(chest));

        DetailView page = reader.page(chest);
        DetailAction collect = page.action(ActionSlot.PRIMARY);
        assertNotNull(collect);
        assertEquals(ActionLook.COLLECT, collect.look());
        assertEquals(BookActions.CLAIM, collect.actionId());
        assertEquals("Collect", read(collect.label()));

        DetailBlock rewards = block(page, "rewards");
        assertNotNull(rewards);
        assertEquals(List.of("250 XP:Auto", "Iron Sword:Waiting"), rewardLines(rewards));

        f.collect(chest);
        AchievementReader after = f.reader();
        assertEquals(Tone.DONE, after.row(chest).tone());
        assertNull(after.page(chest).action(ActionSlot.PRIMARY));
        assertEquals(List.of("250 XP:Auto", "Iron Sword:Collected"), rewardLines(block(after.page(chest), "rewards")));
    }

    @Test
    void anUnearnedAchievementsRewardsReadLockedAndItsHintSaysWhatIsLeft() {
        Achievement chest = f.add(ach("chest", "combat", null, 20)
                .claimReward(RewardSpec.of("item", "Name", "Iron Sword")), "Chest");
        f.progress(chest, 0, 5);

        DetailView page = f.reader().page(chest);

        assertEquals(List.of("Iron Sword:Locked"), rewardLines(block(page, "rewards")));
        assertEquals("15 to go", read(page.hint()));
    }

    @Test
    void aFeatReadsFeatWithNoPointsAndNeverOffersPin() {
        Achievement feat = f.add(ach("old_guard", "combat", null, 1).featOfStrength(true).legacySince("1.4"),
                "Old Guard");
        f.earn(feat, NOW - DAY);

        AchievementReader reader = f.reader();
        LedgerRow row = reader.row(feat);
        assertEquals("Feat", read(row.state()));
        assertNull(row.value(), "a feat is worth bragging, not points");

        DetailView page = reader.page(feat);
        assertNull(page.toggle(), "an earned trophy tracks nothing");
        assertFalse(reader.offersPin(feat));
        assertTrue(pills(page).contains("Feat of Strength since 1.4"));
    }

    @Test
    void thePinToggleFollowsThePinOfferAndNamesItsState() {
        Achievement open = f.add(ach("open", "combat", null, 5), "Open");
        Achievement pinned = f.add(ach("pinned", "combat", null, 5), "Pinned one");
        f.pin(pinned, NOW - DAY);

        AchievementReader reader = f.reader();

        assertEquals("Pin", read(reader.page(open).toggle().label()));
        assertFalse(reader.page(open).toggle().on());
        assertEquals(BookActions.PIN, reader.page(open).toggle().actionId());
        assertEquals("Pin this achievement", read(reader.page(open).toggle().tooltip()),
                "the Pin toggle keeps its tooltip");
        assertEquals("Unpin", read(reader.page(pinned).toggle().label()));
        assertTrue(reader.page(pinned).toggle().on());
        assertEquals(Mark.PINNED, reader.row(pinned).mark());
        assertEquals(Mark.NONE, reader.row(open).mark());
    }

    @Test
    void aLadderRungReadsItsTierAndThePageListsTheWholeLadder() {
        Achievement t1 = f.add(rung("miner_1", "gathering", "miner", 1), "Miner I");
        Achievement t2 = f.add(rung("miner_2", "gathering", "miner", 2), "Miner II");
        Achievement t3 = f.add(rung("miner_3", "gathering", "miner", 3), "Miner III");
        f.earn(t1, NOW - DAY);
        f.progress(t2, 0, 4);

        AchievementReader reader = f.reader();

        assertTrue(read(reader.row(t2).meta()).startsWith("Tier 2 of 3"),
                "a rung says where it stands on its ladder: " + read(reader.row(t2).meta()));
        DetailBlock ladder = block(reader.page(t2), "ladder");
        assertNotNull(ladder);
        assertEquals(List.of(Tick.DONE, Tick.CURRENT, Tick.AHEAD), ticks(ladder));
        assertTrue(ladder.lines().get(1).current(), "the rung being read is the current line");
        assertEquals("Tier 2", read(ladder.lines().get(1).count()));
        assertNull(ladder.lines().get(1).selectId(), "the current rung opens nothing: it is this page");
        assertEquals("miner_3", ladder.lines().get(2).selectId(), "another rung opens its own page");
        assertFalse(moreInIds(reader.page(t3)).contains("miner_1"),
                "a rung's own ladder is the Ladder block, never repeated under More in");
    }

    @Test
    void aCapstoneListsWhatItNeedsAndAChildSaysWhatItIsPartOf() {
        Achievement a = f.add(ach("a", "combat", null, 1), "A");
        Achievement b = f.add(ach("b", "combat", null, 1), "B");
        Achievement hiddenChild = f.add(ach("h", "combat", null, 1).hidden(true), "Hidden");
        Achievement cap = f.add(Achievement.builder("cap").category("combat")
                .metaChildren(List.of("a", "b", "h")), "Capstone");
        f.earn(a, NOW - DAY);

        AchievementReader reader = f.reader();

        DetailBlock needs = block(reader.page(cap), "needs");
        assertNotNull(needs);
        assertEquals(List.of("a", "b"), selectIds(needs), "a hidden child nobody earned stays hidden");
        assertEquals(List.of(Tick.DONE, Tick.AHEAD), ticks(needs));
        assertEquals("1 / 2 steps", read(reader.row(cap).meta()), "a capstone counts the children it lists");

        DetailBlock partOf = block(reader.page(b), "part_of");
        assertNotNull(partOf);
        assertEquals(List.of("cap"), selectIds(partOf));
        assertNull(block(reader.page(cap), "criteria"), "a capstone has no criteria of its own");
        assertNotNull(hiddenChild);
    }

    @Test
    void aServerFirstReadsByWhoClaimedIt() {
        Achievement first = f.add(ach("first", "combat", null, 1).serverFirst(true), "First");

        f.deps = depsWithClaim(null);
        DetailView unclaimed = f.reader().page(first);
        assertTrue(pills(unclaimed).contains("Server first"), "unclaimed, it is still a server first to win");
        assertNull(unclaimed.subMeta());

        f.deps = depsWithClaim(new ObjectiveBookDeps.FirstClaim("Alice", true));
        DetailView mine = f.reader().page(first);
        assertTrue(pills(mine).contains("Server first"));
        assertNull(mine.subMeta(), "my own claim needs no claimant line");

        f.deps = depsWithClaim(new ObjectiveBookDeps.FirstClaim("Ana", false));
        DetailView theirs = f.reader().page(first);
        assertFalse(pills(theirs).contains("Server first"), "someone else won it");
        assertEquals("First claimed by Ana", read(theirs.subMeta()));
    }

    @Test
    void theBreadcrumbNamesTheCategoryAndSubcategoryAndThePoints() {
        Achievement ghoul = f.add(ach("ghoul_2026", "seasons", "hallows_eve", 50).points(10), "Ghoul Breaker 2026");

        DetailView page = f.reader().page(ghoul);

        assertEquals("Seasons > Hallows Eve  -  10 points", read(page.meta()),
                "with no key shipped, each name is its id tidied");
        assertEquals("Ghoul Breaker 2026", read(page.title()));
    }

    @Test
    void aSeasonalCopyWearsItsPillAndSaysWhenItEndsWhileItsRunIsOn() {
        Achievement copy = f.add(ach("geode_2026", "seasons", "hallows_eve", 15)
                .occurrence(new Achievement.Occurrence("hallows_eve", 2026, "geode")), "Geode Cracker 2026");
        f.calendar = runningUntil("hallows_eve", 2026, NOW + 5 * DAY - 1000);

        AchievementReader reader = f.reader();

        assertTrue(read(reader.row(copy).meta()).endsWith("Ends in 5 days"), read(reader.row(copy).meta()));
        assertTrue(pills(reader.page(copy)).contains("Seasonal"));
        assertTrue(read(reader.page(copy).meta()).endsWith("Ends in 5 days"));
    }

    @Test
    void aSeasonalCopyOutOfItsRunSaysNoEndButKeepsItsPill() {
        Achievement copy = f.add(ach("geode_2025", "seasons", "hallows_eve", 15)
                .occurrence(new Achievement.Occurrence("hallows_eve", 2025, "geode")), "Geode Cracker 2025");
        f.earn(copy, NOW - 300 * DAY);
        f.calendar = runningUntil("hallows_eve", 2026, NOW + 5 * DAY);

        AchievementReader reader = f.reader();

        assertFalse(read(reader.row(copy).meta()).contains("Ends in"), "last year's copy is not this run's");
        assertTrue(pills(reader.page(copy)).contains("Seasonal"));

        f.calendar = OccurrenceSource.NONE;
        assertFalse(read(f.reader().row(copy).meta()).contains("Ends in"), "no run on, no end to count down");
    }

    @Test
    void thePictureIsTheAchievementsOwnElseItsCategorys() {
        f.category(AchievementCategoryAsset.of("combat", 1, "Weapon_Sword_Iron", null, null));
        Achievement own = f.add(ach("own", "combat", null, 1).icon("Ore_Gold"), "Own");
        Achievement bare = f.add(ach("bare", "combat", null, 1), "Bare");

        AchievementReader reader = f.reader();

        assertEquals("Ore_Gold", reader.row(own).picture().itemId());
        assertEquals("Weapon_Sword_Iron", reader.row(bare).picture().itemId());
        assertEquals("Weapon_Sword_Iron", reader.page(bare).picture().itemId());
    }

    @Test
    void aCompactRowHasNoMeta() {
        Achievement ghouls = f.add(ach("ghouls", "combat", null, 50), "Ghouls");
        f.progress(ghouls, 0, 10);

        LedgerRow compact = f.reader().compactRow(ghouls);

        assertNull(compact.meta());
        assertNotNull(compact.progress());
        assertEquals("In progress", read(compact.state()));
    }

    @Test
    void moreInListsSiblingsInTheSameSubcategory() {
        Achievement self = f.add(ach("geode", "seasons", "hallows_eve", 15), "Geode");
        f.add(ach("lantern", "seasons", "hallows_eve", 25), "Lantern");
        f.add(ach("ghoul", "seasons", "hallows_eve", 50), "Ghoul");
        f.add(ach("snow", "seasons", "winter", 5), "Snow");

        DetailView page = f.reader().page(self);
        DetailBlock more = block(page, "more_in");

        assertNotNull(more);
        assertEquals("More in Hallows Eve", read(more.label()));
        assertEquals(List.of("ghoul", "lantern"), selectIds(more).stream().sorted().toList(),
                "the siblings, never itself and never another subcategory");
    }

    // ==================== helpers ====================

    @Nonnull
    private static String date(long ms) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString();
    }

    @Nullable
    private static DetailBlock block(@Nonnull DetailView view, @Nonnull String id) {
        for (DetailBlock block : view.blocks()) {
            if (block.id().equals(id)) {
                return block;
            }
        }
        return null;
    }

    @Nonnull
    private static List<Tick> ticks(@Nonnull DetailBlock block) {
        List<Tick> out = new ArrayList<>();
        for (DetailLine line : block.lines()) {
            out.add(line.tick());
        }
        return out;
    }

    @Nonnull
    private static List<String> selectIds(@Nonnull DetailBlock block) {
        List<String> out = new ArrayList<>();
        for (DetailLine line : block.lines()) {
            out.add(line.selectId());
        }
        return out;
    }

    @Nonnull
    private static List<String> moreInIds(@Nonnull DetailView view) {
        DetailBlock more = block(view, "more_in");
        return more == null ? List.of() : selectIds(more);
    }

    @Nonnull
    private static List<String> rewardLines(@Nonnull DetailBlock block) {
        List<String> out = new ArrayList<>();
        for (DetailLine line : block.lines()) {
            out.add(read(line.text()) + ":" + (line.tag() == null ? "" : read(line.tag().label())));
        }
        return out;
    }

    @Nonnull
    private static List<String> pills(@Nonnull DetailView view) {
        List<String> out = new ArrayList<>();
        for (Pill pill : view.badges()) {
            out.add(read(pill.label()));
        }
        return out;
    }

    @Nonnull
    private ObjectiveBookDeps depsWithClaim(@Nullable ObjectiveBookDeps.FirstClaim claim) {
        return ObjectiveBookDeps.builder().firstClaims((id, viewer) -> claim).build();
    }

    /** A calendar on which {@code eventId}'s {@code year} run is on now and ends at {@code endMs}. */
    @Nonnull
    private static OccurrenceSource runningUntil(@Nonnull String eventId, int year, long endMs) {
        Occurrence run = new Occurrence(eventId, year, NOW - 10 * DAY, endMs);
        return new OccurrenceSource() {
            @Override
            public boolean isEnabled(@Nonnull String id) {
                return id.equals(eventId);
            }

            @Nullable
            @Override
            public Occurrence live(@Nonnull String id, long nowMs) {
                return id.equals(eventId) && run.contains(nowMs) ? run : null;
            }

            @Nonnull
            @Override
            public List<Occurrence> history(@Nonnull String id, long nowMs) {
                return id.equals(eventId) ? List.of(run) : List.of();
            }
        };
    }
}
