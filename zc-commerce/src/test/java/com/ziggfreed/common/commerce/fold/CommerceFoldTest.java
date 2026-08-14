package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.board.BountyRef;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.commerce.asset.RerollAsset;
import com.ziggfreed.common.commerce.asset.RotationAsset;
import com.ziggfreed.common.commerce.asset.SelectionAsset;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.rotation.PoolSlot;
import com.ziggfreed.common.rotation.RerollSpec;
import com.ziggfreed.common.rotation.RotationSpec;
import com.ziggfreed.common.rotation.SelectionSpec;
import com.ziggfreed.common.shop.PurchaseLimits;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;
import com.ziggfreed.common.time.DurationGroup;
import com.ziggfreed.common.util.PeriodMath;

/**
 * The crossing itself: real authored files read back and folded into the values an engine charges,
 * draws and counts, then compared against what the file actually says.
 *
 * <p>Every assertion is a RELATION between the two sides rather than a number typed in here, so a
 * balance pass moves a price and this test still says the same thing: whatever the author wrote is
 * exactly what the engine was handed. The one place literals appear is the degrade behaviour, where
 * the value IS the contract.
 */
class CommerceFoldTest {

    // ==================== Price ====================

    @Nested
    class Prices {

        @Test
        @DisplayName("a folded price charges exactly the wallets the file names")
        void anOffersPriceCrossesIntact() throws Exception {
            ShopEntryAsset boost = CommerceFoldFixtures.entry("ShopEntries/MMOSkillTree/Boost_Mining.json");

            Cost price = CommerceFold.cost(boost.getCost(), boost.getId());

            assertEquals(boost.costOrFree().currencyAmounts(), price.currencies(),
                    "the folded price is the authored one, wallet for wallet and amount for amount");
            assertEquals(boost.costOrFree().itemCosts().size(), price.items().size());
            assertEquals(Cost.Combine.ALL, price.combine(), "an unauthored Combine charges everything");
            assertNull(price.scaling(), "nothing in the schema grows a price, so nothing folds one");
        }

        @Test
        @DisplayName("an unauthored price is free, which is a real answer")
        void noPriceIsFree() {
            assertTrue(CommerceFold.cost(null, "nothing").isFree());
            assertTrue(CommerceFold.cost(CostAsset.FREE, "empty").isFree());
        }

        @Test
        @DisplayName("Any charges one component, All charges them all")
        void combineIsADiscriminator() {
            CostAsset either = CostAsset.of(Map.of("bounty_token", 300L), null, CostAsset.COMBINE_ANY);
            assertEquals(Cost.Combine.ANY, CommerceFold.cost(either, "either").combine());
        }

        @Test
        @DisplayName("a Combine word that is neither still prices the offer, charging everything")
        void anUnknownCombineDegradesRatherThanRemovingTheOffer() {
            CostAsset odd = CostAsset.of(Map.of("bounty_token", 5L), null, "Sometimes");

            Cost price = CommerceFold.cost(odd, "odd");

            assertEquals(Cost.Combine.ALL, price.combine());
            assertFalse(price.isFree(), "the offer keeps its price rather than falling off the page");
        }

        @Test
        @DisplayName("an item price keeps its count")
        void itemsCrossWithTheirCounts() {
            CostAsset iron = CostAsset.of(null,
                    new CostAsset.ItemCostAsset[]{CostAsset.ItemCostAsset.of("Ore_Iron", 16)}, null);

            Cost price = CommerceFold.cost(iron, "iron");

            assertEquals(1, price.items().size());
            assertEquals(iron.itemCosts().get(0).getItem(), price.items().get(0).item());
            assertEquals(iron.itemCosts().get(0).countOrOne(), price.items().get(0).count());
        }
    }

    // ==================== Cadence ====================

    @Nested
    class Cadences {

        @Test
        @DisplayName("a calendar board turns over on the calendar's own boundary")
        void aDailyBoardFoldsToADay() throws Exception {
            BoardAsset daily = CommerceFoldFixtures.board("Boards/MMOSkillTree/Daily.json");

            RotationSpec rotation = CommerceFold.rotation(daily.getRotation(), daily.getId());

            assertEquals(PeriodMath.DAY_MS, rotation.periodLengthMs());
            assertEquals(0L, rotation.anchorOffsetMs(), "no offset was authored");
        }

        @Test
        @DisplayName("a span board turns over on exactly the span the file spells out")
        void aSpanBoardFoldsToItsOwnLength() throws Exception {
            BoardAsset bihourly = CommerceFoldFixtures.board("Boards/MMOSkillTree/Bihourly.json");
            DurationGroup authored = bihourly.getRotation().getEvery();

            RotationSpec rotation = CommerceFold.rotation(bihourly.getRotation(), bihourly.getId());

            assertNotNull(authored);
            assertEquals(authored.totalMs(), rotation.periodLengthMs());
        }

        @Test
        @DisplayName("an unauthored cadence never turns over, which is what the leaf documents")
        void noCadenceMeansNoRotation() {
            RotationSpec never = CommerceFold.rotation(null, "unauthored");

            assertEquals(CommerceFold.NEVER.periodLengthMs(), never.periodLengthMs());
            assertTrue(never.samePeriod(0L, 4_000_000_000_000L),
                    "every instant is the same period, so a draw stands for good");
        }

        @Test
        @DisplayName("a cadence word this schema does not know never quietly becomes daily")
        void aTypoIsVisibleRatherThanWorking() {
            RotationSpec typo = CommerceFold.rotation(
                    RotationAsset.of("Dailly", null, null, null), "typo");

            assertEquals(CommerceFold.NEVER.periodLengthMs(), typo.periodLengthMs());
        }

        @Test
        @DisplayName("both cadences at once runs the calendar one")
        void theCalendarCadenceWinsAConflict() {
            RotationSpec both = CommerceFold.rotation(
                    RotationAsset.of("Daily", DurationGroup.of(null, 2, null, null), null, null), "both");

            assertEquals(PeriodMath.DAY_MS, both.periodLengthMs());
        }

        @Test
        @DisplayName("a weekly cadence starts on the day it names, and on Monday when it names none")
        void aWeekStartsWhereItSays() {
            RotationSpec monday = CommerceFold.rotation(
                    RotationAsset.of("Weekly", null, null, null), "weekly");
            RotationSpec thursday = CommerceFold.rotation(
                    RotationAsset.of("Weekly", null, null, "Thursday"), "weekly");
            RotationSpec nonsense = CommerceFold.rotation(
                    RotationAsset.of("Weekly", null, null, "Blursday"), "weekly");

            assertEquals(PeriodMath.WEEK_MS, monday.periodLengthMs());
            assertEquals(PeriodMath.weekdayAnchorMs(DayOfWeek.MONDAY), monday.anchorOffsetMs());
            assertEquals(PeriodMath.weekdayAnchorMs(DayOfWeek.THURSDAY), thursday.anchorOffsetMs());
            assertEquals(monday.anchorOffsetMs(), nonsense.anchorOffsetMs(),
                    "a day name nobody recognises falls back to the documented default");
        }

        @Test
        @DisplayName("an offset moves the boundary later by exactly the minutes authored")
        void anOffsetShiftsTheRollover() {
            int minutes = 240;
            RotationSpec plain = CommerceFold.rotation(RotationAsset.of("Daily", null, null, null), "plain");
            RotationSpec shifted = CommerceFold.rotation(
                    RotationAsset.of("Daily", null, minutes, null), "shifted");

            assertEquals(plain.anchorOffsetMs() - minutes * PeriodMath.MINUTE_MS,
                    shifted.anchorOffsetMs());
        }
    }

    // ==================== Selection ====================

    @Nested
    class Selections {

        @Test
        @DisplayName("an unauthored selection is the seeded, weight-honouring draw")
        void noSelectionIsTheDefaultDraw() {
            assertEquals(SelectionSpec.DEFAULT.type(), CommerceFold.selection(null).type());
            assertEquals(SelectionSpec.DEFAULT.seed(), CommerceFold.selection(null).seed());
        }

        @Test
        @DisplayName("a strategy nobody registered is carried through rather than replaced here")
        void anUnknownStrategyIsTheDrawsProblemNotTheFolds() {
            SelectionSpec odd = CommerceFold.selection(SelectionAsset.of("Yourmod_Curated", null));

            assertEquals("Yourmod_Curated", odd.type(),
                    "folding a fallback in would hide the typo the draw is meant to report");
        }
    }

    // ==================== Slots ====================

    @Nested
    class Slots {

        @Test
        @DisplayName("a board's difficulty bands become the one grade a draw filters on")
        void everyBoardSlotCrossesIntact() throws Exception {
            BoardAsset daily = CommerceFoldFixtures.board("Boards/MMOSkillTree/Daily.json");

            List<PoolSlot> slots = CommerceFold.slots(daily.slotsOrEmpty());

            assertEquals(daily.slotsOrEmpty().length, slots.size());
            for (int i = 0; i < slots.size(); i++) {
                assertEquals(daily.slotsOrEmpty()[i].label(), slots.get(i).tier(),
                        "the board's own word for a band is what the draw filters on");
                assertEquals(daily.slotsOrEmpty()[i].countOrOne(), slots.get(i).count());
                assertEquals(daily.slotsOrEmpty()[i].isOptional(), slots.get(i).optional());
                assertNull(slots.get(i).tag(), "no schema authors the second filter axis");
            }
        }

        @Test
        @DisplayName("a shelf's tiers cross the same way, with no second eligibility rule")
        void everyShelfSlotCrossesIntact() throws Exception {
            ShopPoolAsset shelf = CommerceFoldFixtures.pool("ShopPools/MMOSkillTree/XpExchange.json");

            List<PoolSlot> slots = CommerceFold.slots(shelf.slotsOrEmpty());

            assertEquals(shelf.slotsOrEmpty().length, slots.size());
            for (int i = 0; i < slots.size(); i++) {
                assertEquals(shelf.slotsOrEmpty()[i].label(), slots.get(i).tier());
                assertTrue(slots.get(i).accepts(shelf.slotsOrEmpty()[i].getTier(), null),
                        "a candidate graded as the slot asks fits it, however either side is capitalized");
            }
        }

        @Test
        @DisplayName("an unslotted set draws from everything it holds")
        void noSlotsIsNoFilter() {
            assertTrue(CommerceFold.slots(null).isEmpty());
            assertEquals(PoolSlot.ANY.count(), CommerceFold.slot(null).count());
            assertNull(CommerceFold.slot(null).tier());
        }
    }

    // ==================== Reroll ====================

    @Nested
    class Rerolls {

        @Test
        @DisplayName("a reroll charges the price its own block names, and allows what it allows")
        void aRerollCrossesIntact() throws Exception {
            BoardAsset daily = CommerceFoldFixtures.board("Boards/MMOSkillTree/Daily.json");

            RerollSpec spec = CommerceFold.reroll(daily.getReroll(), daily.getId());

            assertNotNull(spec);
            assertEquals(daily.getReroll().costOrFree().currencyAmounts(), spec.cost().currencies());
            assertEquals(daily.getReroll().maxPerPeriod(), spec.maxPerPeriod());
            assertTrue(spec.isPaid());
        }

        @Test
        @DisplayName("no block means no reroll; an empty block means a free one")
        void theAbsentBlockAndTheEmptyBlockSayDifferentThings() {
            assertNull(CommerceFold.reroll(null, "none"),
                    "a set nobody made rerollable offers no reroll at all");

            RerollSpec free = CommerceFold.reroll(RerollAsset.of(null, 2), "free");
            assertNotNull(free);
            assertFalse(free.isPaid(), "an authored block with no price is a free reroll");
            assertEquals(2, free.maxPerPeriod());
        }
    }

    // ==================== Limits ====================

    @Nested
    class Limits {

        @Test
        @DisplayName("an offer's ceilings cross exactly as authored, each independently optional")
        void limitsCrossIntact() throws Exception {
            ShopEntryAsset boost = CommerceFoldFixtures.entry("ShopEntries/MMOSkillTree/Boost_Mining.json");

            PurchaseLimits limits = CommerceFold.limits(boost.getLimits());

            assertEquals(Integer.valueOf(boost.dailyLimit()), limits.daily());
            assertNull(limits.total(), "the offer authored no lifetime cap, so nothing caps its lifetime");
            assertFalse(limits.isOpen());
        }

        @Test
        @DisplayName("an unauthored block limits nothing")
        void noLimitsIsOpen() {
            assertTrue(CommerceFold.limits(null).isOpen());
            assertTrue(CommerceFold.limits(ShopEntryAsset.Limits.of(null, null)).isOpen());
        }
    }

    // ==================== Wallets ====================

    @Nested
    class Wallets {

        @Test
        @DisplayName("a counter-backed wallet crosses with its icon, its colour and its owner knobs")
        void theCounterBackedWalletCrossesIntact() throws Exception {
            CurrencyAsset asset =
                    CommerceFoldFixtures.currency("Currencies/MMOSkillTree/Bounty_Token.json");

            CurrencyDef def = CommerceFold.currencyDef(asset);

            assertEquals(asset.getId(), def.id());
            assertFalse(def.isItemBacked());
            assertEquals(asset.effectiveIconItemId(), def.iconItemId());
            assertEquals(asset.getColor(), def.color());
            assertEquals(asset.cap(), def.cap());
            assertEquals(asset.lossOnDeath(), def.lossOnDeathPercent());
            assertEquals(asset.decayPerDay(), def.decayPerDayPercent());
            assertNotNull(def.meta().get("mmoskilltree"),
                    "the consumer's own knobs ride across untouched, under its own namespace");
            assertNotNull(def.meta("mmoskilltree", "ShowOnSidebar"),
                    "a knob keeps the word the mod that reads it wrote");
        }

        @Test
        @DisplayName("a wallet with no name key of its own falls back the way its leaf documents")
        void theNameLadderIsTheOneTheAuthorReads() throws Exception {
            CurrencyAsset token =
                    CommerceFoldFixtures.currency("Currencies/MMOSkillTree/Bounty_Token.json");
            CurrencyAsset essence =
                    CommerceFoldFixtures.currency("Currencies/MMOSkillTree/Life_Essence.json");

            assertNull(token.getText(), "neither shipped wallet writes a name of its own");
            assertEquals("currency." + token.getId() + ".name",
                    CommerceFold.currencyDef(token).nameKey(),
                    "a counter-backed wallet falls back to the convention key");
            assertNull(CommerceFold.currencyDef(essence).nameKey(),
                    "an item-backed one falls back further, to the backing item's own name");
        }

        @Test
        @DisplayName("an item-backed wallet takes its picture from what backs it")
        void theBackingItemSuppliesThePicture() throws Exception {
            CurrencyAsset essence =
                    CommerceFoldFixtures.currency("Currencies/MMOSkillTree/Life_Essence.json");

            CurrencyDef def = CommerceFold.currencyDef(essence);

            assertTrue(def.isItemBacked());
            assertEquals(essence.backingItemId(), def.backingItemId());
            assertEquals(essence.backingItemId(), def.iconItemId());
        }
    }

    // ==================== The three engine views ====================

    @Nested
    class EngineViews {

        @Test
        @DisplayName("an offer answers the six questions a purchase asks, off the file it came from")
        void anOfferViewAnswersThePurchase() throws Exception {
            ShopEntryAsset asset =
                    CommerceFoldFixtures.entry("ShopEntries/MMOSkillTree/Featured_Cache_Copper.json");

            ShopEntryOffer offer = ShopEntryOffer.of(asset);

            assertEquals(asset.getId(), offer.offerId());
            assertTrue(offer.enabled());
            assertEquals(asset.costOrFree().currencyAmounts(), offer.cost().currencies());
            assertEquals(asset.rewardsOrEmpty().length, offer.rewards().size());
            assertEquals(asset.getPool().getId(), offer.poolId());
            assertEquals(asset.getPool().getTier(), offer.poolTier());
            assertEquals(asset.getPool().weightOrOne(), offer.poolWeight());
            assertNotNull(offer.limits(), "the file caps how many a player may buy in a day");
            assertEquals(asset, offer.asset(), "the view keeps its source rather than copying it");
        }

        @Test
        @DisplayName("an offer with no limits reports none, so a purchase never asks a store about them")
        void anUnlimitedOfferReportsNoLimits() {
            ShopEntryAsset bare = new ShopEntryAsset();

            assertNull(ShopEntryOffer.of(bare).limits());
        }

        @Test
        @DisplayName("a board answers the draw off its own file, and its per-band gates come with it")
        void aBoardViewAnswersTheDraw() throws Exception {
            BoardAsset asset = CommerceFoldFixtures.board("Boards/MMOSkillTree/Daily.json");

            BoardAssetSpec spec = BoardAssetSpec.of(asset);

            assertEquals(asset.getId(), spec.boardId());
            assertTrue(spec.enabled());
            assertEquals(asset.slotsOrEmpty().length, spec.slots().size());
            assertEquals(asset.currencyIds(), List.copyOf(spec.currencies()));
            assertEquals(asset.acceptRequires().keySet(), spec.acceptRequires().keySet());
            assertNotNull(spec.reroll());
            assertEquals(PeriodMath.DAY_MS, spec.rotation().periodLengthMs());
        }

        @Test
        @DisplayName("a contract answers per board, and says nothing about a board it is not on")
        void aContractViewAnswersPerBoard() throws Exception {
            BountyAsset base = CommerceFoldFixtures.bounty("Bounties/MMOSkillTree/Bounty_Kill.json",
                    null, null);
            BountyAsset trork = CommerceFoldFixtures.bounty(
                    "Bounties/MMOSkillTree/Bounty_Hunt_Trork.json", base, base.getId());

            BountyRef ref = BountyAssetRef.of(trork);
            String board = trork.boardMemberships().get(0).getBoard();

            assertEquals(trork.getId(), ref.bountyId());
            assertTrue(ref.enabled());
            assertTrue(ref.isOn(board));
            assertTrue(ref.isOn(board.toUpperCase(Locale.ROOT)),
                    "a board reference is matched however it is capitalized");
            assertEquals(trork.membershipOn(board).getDifficulty(), ref.difficultyOn(board));
            assertEquals(trork.membershipOn(board).weightOrOne(), ref.weightOn(board));

            assertFalse(ref.isOn("weekly"));
            assertNull(ref.difficultyOn("weekly"));
            assertEquals(1.0, ref.weightOn("weekly"),
                    "a board it does not hang on biases nothing rather than reading as zero");
        }
    }
}
