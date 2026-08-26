package com.ziggfreed.common.commerce.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BountyAsset;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.QuestTurnInSite;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.shop.asset.StorefrontAsset;
import com.ziggfreed.common.shop.asset.ShopEntryAsset;
import com.ziggfreed.common.shop.asset.ShopPoolAsset;

/**
 * The commerce types' decode contract, and above all what native {@code Parent} inheritance does to
 * it: which leaves an author may retune without losing the ones they did not mention, and which are
 * deliberately replaced whole.
 */
class CommerceCodecTest {

    static CurrencyAsset currency(String json, String id, String parentId, CurrencyAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(CurrencyAsset.class, id, parentId);
        return CurrencyAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    static StorefrontAsset shop(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(StorefrontAsset.class, id, null);
        return StorefrontAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    static ShopPoolAsset pool(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(ShopPoolAsset.class, id, null);
        return ShopPoolAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    static ShopEntryAsset entry(String json, String id, String parentId, ShopEntryAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(ShopEntryAsset.class, id, parentId);
        return ShopEntryAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    static BoardAsset board(String json, String id, String parentId, BoardAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(BoardAsset.class, id, parentId);
        return BoardAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    static BountyAsset bounty(String json, String id, String parentId, BountyAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(BountyAsset.class, id, parentId);
        return BountyAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), parent, new AssetExtraInfo<>(data));
    }

    // ==================== the id is the filename, lower-cased ====================

    @Test
    void everyTypeCanonicalizesItsIdToLowerCase() throws Exception {
        assertEquals("bounty_token", currency("{}", "Bounty_Token", null, null).getId());
        assertEquals("general", shop("{}", "General").getId());
        assertEquals("featured", pool("{}", "Featured").getId());
        assertEquals("boost_mining", entry("{}", "Boost_Mining", null, null).getId());
        assertEquals("daily", board("{}", "Daily", null, null).getId());
        assertEquals("bounty_hunt_trork", bounty("{}", "Bounty_Hunt_Trork", null, null).getId());
    }

    // ==================== the price ====================

    @Nested
    class Price {

        @Test
        void aMultiWalletPriceKeepsEveryComponent() throws Exception {
            ShopEntryAsset offer = entry("""
                    { "Cost": { "Currencies": { "Bounty_Token": 150, "Life_Essence": 80 } } }
                    """, "cache", null, null);

            assertEquals(2, offer.costOrFree().currencyAmounts().size());
            assertEquals(150L, offer.costOrFree().currencyAmounts().get("bounty_token"));
            assertEquals(80L, offer.costOrFree().currencyAmounts().get("life_essence"));
            assertFalse(offer.costOrFree().combinesAny(), "an unauthored Combine charges every component");
        }

        @Test
        void anUnauthoredPriceIsFreeRatherThanNull() throws Exception {
            assertTrue(entry("{}", "starter", null, null).costOrFree().isFree());
        }

        @Test
        void aChildMayRetuneTheWalletsAndKeepTheItems() throws Exception {
            ShopEntryAsset base = entry("""
                    { "Cost": { "Currencies": { "Bounty_Token": 100 },
                                "Items": [ { "Item": "Ore_Iron", "Count": 16 } ] } }
                    """, "base", null, null);
            ShopEntryAsset child = entry("""
                    { "Cost": { "Currencies": { "Bounty_Token": 250 } } }
                    """, "child", "base", base);

            assertEquals(250L, child.costOrFree().currencyAmounts().get("bounty_token"));
            assertEquals(1, child.costOrFree().itemCosts().size(),
                    "the item half of an inherited price survives a currency-only retune");
            assertEquals(16, child.costOrFree().itemCosts().get(0).countOrOne());
        }

        @Test
        void anUnknownCombineIsVisibleToAnAudit() throws Exception {
            ShopEntryAsset offer = entry("""
                    { "Cost": { "Currencies": { "Bounty_Token": 1 }, "Combine": "Either" } }
                    """, "odd", null, null);
            assertTrue(offer.costOrFree().hasUnknownCombine());
            assertFalse(offer.costOrFree().combinesAny(), "an unreadable word must not become the Any route");
        }
    }

    // ==================== the schedule ====================

    @Nested
    class Schedule {

        @Test
        void aCalendarCadenceAndASpanCadenceAreTwoDistinctLeaves() throws Exception {
            RotationAsset calendar = board("""
                    { "Rotation": { "Period": "Daily" } }
                    """, "daily", null, null).getRotation();
            assertNotNull(calendar);
            assertTrue(calendar.isCalendar());
            assertNull(calendar.getEvery());

            RotationAsset span = board("""
                    { "Rotation": { "Every": { "Hours": 2 } } }
                    """, "bihourly", null, null).getRotation();
            assertNotNull(span);
            assertNull(span.getPeriod());
            assertNotNull(span.getEvery());
            assertEquals(2L * 60L * 60L * 1000L, span.getEvery().totalMs());
        }

        @Test
        void authoringBothCadencesIsVisibleRatherThanSilentlyResolved() throws Exception {
            RotationAsset both = board("""
                    { "Rotation": { "Period": "Daily", "Every": { "Hours": 2 } } }
                    """, "confused", null, null).getRotation();
            assertNotNull(both);
            assertTrue(both.hasBothCadences());
        }

        @Test
        void aChildMayMoveTheRolloverTimeAndKeepTheCadence() throws Exception {
            BoardAsset base = board("""
                    { "Rotation": { "Period": "Weekly", "Weekday": "Monday" } }
                    """, "weekly", null, null);
            BoardAsset child = board("""
                    { "Rotation": { "OffsetMinutes": 240 } }
                    """, "weekly_late", "weekly", base);

            RotationAsset rotation = child.getRotation();
            assertNotNull(rotation);
            assertEquals(240, rotation.offsetMinutes());
            assertTrue(rotation.isWeekly(), "the inherited cadence survives an offset-only retune");
        }
    }

    // ==================== slots ====================

    @Nested
    class Slots {

        @Test
        void aBoardSlotCarriesItsBandCountAndOptionality() throws Exception {
            BoardAsset daily = board("""
                    { "Slots": [ { "Difficulty": "Training", "Count": 2 },
                                 { "Difficulty": "Hard", "Optional": true } ] }
                    """, "daily", null, null);

            assertEquals(2, daily.slotsOrEmpty().length);
            assertEquals("training", daily.slotsOrEmpty()[0].label());
            assertEquals(2, daily.slotsOrEmpty()[0].countOrOne());
            assertFalse(daily.slotsOrEmpty()[0].isOptional());
            assertEquals("hard", daily.slotsOrEmpty()[1].label());
            assertEquals(1, daily.slotsOrEmpty()[1].countOrOne(), "an unauthored Count yields one");
            assertTrue(daily.slotsOrEmpty()[1].isOptional());
        }

        @Test
        void aShelfSlotSpellsItsLabelTierAndReadsThroughTheSameHandle() throws Exception {
            ShopPoolAsset shelf = pool("""
                    { "Slots": [ { "Tier": "Master" } ] }
                    """, "xpexchange");
            assertEquals("master", shelf.slotsOrEmpty()[0].label());
        }
    }

    // ==================== the wallet ====================

    @Nested
    class Wallet {

        @Test
        void anItemBackedWalletTakesItsIconFromTheBackingItem() throws Exception {
            CurrencyAsset essence = currency("""
                    { "Backing": { "Item": "Ingredient_Life_Essence" }, "Color": "#a7e0a7" }
                    """, "Life_Essence", null, null);

            assertTrue(essence.isItemBacked());
            assertEquals("Ingredient_Life_Essence", essence.backingItemId());
            assertEquals("Ingredient_Life_Essence", essence.effectiveIconItemId());
        }

        @Test
        void aCounterBackedWalletKeepsItsOwnIcon() throws Exception {
            CurrencyAsset token = currency("""
                    { "Icon": "Ingredient_Bar_Gold", "Color": "#ffcc44", "Cap": 0 }
                    """, "Bounty_Token", null, null);

            assertFalse(token.isItemBacked());
            assertNull(token.backingItemId());
            assertEquals("Ingredient_Bar_Gold", effectiveIcon(token));
            assertEquals(0L, token.cap(), "0 is uncapped");
        }

        @Test
        void theEconomyKnobsAreIndependentNullableGroups() throws Exception {
            CurrencyAsset plain = currency("{}", "plain", null, null);
            assertEquals(0.0, plain.lossOnDeath());
            assertEquals(0.0, plain.decayPerDay());

            CurrencyAsset harsh = currency("""
                    { "OnDeath": { "LossPercent": 0.25 } }
                    """, "harsh", null, null);
            assertEquals(0.25, harsh.lossOnDeath());
            assertEquals(0.0, harsh.decayPerDay(), "authoring one knob must not imply the other");
        }

        @Test
        void aShareOutsideItsRangeIsClampedRatherThanTrusted() throws Exception {
            CurrencyAsset overshoot = currency("""
                    { "OnDeath": { "LossPercent": 10 }, "Decay": { "PerDayPercent": -1 } }
                    """, "overshoot", null, null);
            assertEquals(1.0, overshoot.lossOnDeath());
            assertEquals(0.0, overshoot.decayPerDay());
        }

        private String effectiveIcon(CurrencyAsset asset) {
            return asset.effectiveIconItemId();
        }
    }

    // ==================== the contract ====================

    @Nested
    class Contract {

        @Test
        void aChildRetuningOneStepKeepsItsSiblings() throws Exception {
            BountyAsset base = bounty("""
                    { "Abstract": true,
                      "Objectives": { "main": { "Kind": "KILL_ENTITY", "Amount": 1, "MatchMode": "CONTAINS" },
                                      "report": { "Kind": "TURN_IN", "Amount": 1, "Order": 2 } } }
                    """, "Bounty_Kill", null, null);

            BountyAsset child = bounty("""
                    { "Objectives": { "main": { "Target": "Trork", "Amount": 8 } } }
                    """, "Bounty_Hunt_Trork", "bounty_kill", base);

            assertFalse(child.isAbstract(), "Abstract must never carry down to a child");
            assertEquals(2, child.objectivesOrEmpty().size(), "the step the child never mentioned survives");
            assertEquals("Trork", child.objectivesOrEmpty().get("main").getTarget());
            assertEquals(8L, child.objectivesOrEmpty().get("main").getAmount());
            assertEquals("KILL_ENTITY", child.objectivesOrEmpty().get("main").getKind(),
                    "a sibling leaf of the very step the child touched survives");
        }

        @Test
        void boardMembershipIsStructuredAndMayNameSeveralBoards() throws Exception {
            BountyAsset contract = bounty("""
                    { "Boards": [ { "Board": "Daily", "Difficulty": "Hard", "Weight": 1 },
                                  { "Board": "Weekly", "Difficulty": "Normal", "Weight": 3 } ] }
                    """, "Bounty_Hunt_Trork", null, null);

            assertEquals(2, contract.boardMemberships().size());
            assertEquals("hard", contract.membershipOn("daily").getDifficulty());
            assertEquals(3.0, contract.membershipOn("WEEKLY").weightOrOne(),
                    "board ids match however they are capitalized");
            assertNull(contract.membershipOn("bihourly"));
        }

        @Test
        void theTypeStampsThePolicySoNoFileCanGetItWrong() throws Exception {
            BountyAsset contract = bounty("""
                    { "Boards": [ { "Board": "Daily", "Difficulty": "Hard" } ],
                      "Objectives": { "main": { "Kind": "KILL_ENTITY", "Target": "Trork", "Amount": 8 } } }
                    """, "Bounty_Hunt_Trork", null, null);

            QuestDefinition folded = contract.toDefinition(null);

            assertNotNull(folded.quest().turnInAt(),
                    "a contract is collected AT its board, so a finished one parks there rather"
                            + " than settling in the field");
            assertTrue(folded.quest().visibility().hidden(),
                    "a contract is read at its board, never listed as an open quest");
            assertNotNull(folded.quest().repeat(), "a contract always comes round again");
            assertEquals(0L, folded.quest().repeat().cooldownMs(),
                    "nothing on the contract holds it back: whatever posts it decides");
            assertEquals(QuestTurnInSite.ACCEPT_SITE, folded.turnInAt(),
                    "a contract is collected wherever it was taken from");
            assertFalse(folded.quest().occupiesLog(),
                    "and it is carried BESIDE the quest log rather than inside it, so somebody"
                            + " working several contracts still has their whole log and is never"
                            + " refused the next one for a log they are not filling");
            assertFalse(folded.quest().autoAccept());
        }

        @Test
        void anUnauthoredRequiresFoldsToTheOpenGateRatherThanNull() throws Exception {
            assertEquals(GateSpec.OPEN, bounty("{}", "open", null, null).toDefinition(null).requires());
        }

        @Test
        void theRequiresBlockIsTheSharedGateModel() throws Exception {
            BountyAsset contract = bounty("""
                    { "Requires": { "Factors": [ { "Factor": "bounty_veteran", "Min": 90 } ],
                                    "Quests": ["intro_bounty"] } }
                    """, "elite", null, null);

            GateSpec requires = contract.getRequires();
            assertNotNull(requires);
            assertEquals(1, requires.factorsOrEmpty().length);
            assertEquals("bounty_veteran", requires.factorsOrEmpty()[0].getFactor());
            assertEquals(1, requires.questsOrEmpty().length);
        }
    }

    // ==================== the board ====================

    @Nested
    class Board {

        @Test
        void perBandGatesAreOrdinaryRequiresBlocksKeyedByBand() throws Exception {
            BoardAsset daily = board("""
                    { "AcceptRequires": {
                        "Normal": { "Factors": [ { "Factor": "hytale:stat", "Param": "MMO_CombatLevel",
                                                   "Min": 25 } ] },
                        "Hard":   { "Factors": [ { "Factor": "hytale:stat", "Param": "MMO_CombatLevel",
                                                   "Min": 60 } ] } } }
                    """, "Daily", null, null);

            assertEquals(2, daily.acceptRequires().size());
            assertNotNull(daily.acceptRequiresFor("hard"));
            assertNotNull(daily.acceptRequiresFor("HARD"), "a band matches however it is capitalized");
            assertNull(daily.acceptRequiresFor("training"), "a band nobody gated is open to everybody");
            assertEquals(60.0, daily.acceptRequiresFor("Hard").factorsOrEmpty()[0].getMin());
        }

        @Test
        void aChildBoardMayRaiseOneBandAndKeepTheRest() throws Exception {
            BoardAsset base = board("""
                    { "AcceptRequires": {
                        "Normal": { "Factors": [ { "Factor": "hytale:stat", "Param": "MMO_CombatLevel",
                                                   "Min": 25 } ] },
                        "Hard":   { "Factors": [ { "Factor": "hytale:stat", "Param": "MMO_CombatLevel",
                                                   "Min": 60 } ] } } }
                    """, "Daily", null, null);

            BoardAsset strict = board("""
                    { "AcceptRequires": {
                        "Hard": { "Factors": [ { "Factor": "hytale:stat", "Param": "MMO_CombatLevel",
                                                 "Min": 80 } ] } } }
                    """, "Daily_Hardcore", "daily", base);

            assertEquals(80.0, strict.acceptRequiresFor("hard").factorsOrEmpty()[0].getMin());
            assertNotNull(strict.acceptRequiresFor("normal"),
                    "the band the child never mentioned survives: the map merges per band");
            assertEquals(25.0, strict.acceptRequiresFor("normal").factorsOrEmpty()[0].getMin());
        }

        @Test
        void aChildBoardMayRenameOneBandAndKeepTheRest() throws Exception {
            BoardAsset base = board("""
                    { "Grades": { "Skirmish": { "TitleKey": "board.grade.skirmish" },
                                  "Hard":     { "TitleKey": "board.grade.hard" } } }
                    """, "Daily", null, null);

            BoardAsset hardcore = board("""
                    { "Grades": { "Hard": { "TitleKey": "board.grade.hard.hardcore" } } }
                    """, "Daily_Hardcore", "daily", base);

            assertNotNull(hardcore.gradeText("hard"));
            assertEquals("board.grade.hard.hardcore", hardcore.gradeText("hard").getTitleKey());
            assertNotNull(hardcore.gradeText("skirmish"),
                    "the band the child never mentioned survives: the map merges per band");
            assertEquals("board.grade.skirmish", hardcore.gradeText("skirmish").getTitleKey());
        }

        @Test
        void theHeaderWalletsAreLowerCasedAndKeepTheirAuthoredOrder() throws Exception {
            BoardAsset daily = board("""
                    { "Currencies": ["Bounty_Token", "Life_Essence"] }
                    """, "Daily", null, null);
            assertEquals(java.util.List.of("bounty_token", "life_essence"), daily.currencyIds());
        }
    }
}
