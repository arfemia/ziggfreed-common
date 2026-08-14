package com.ziggfreed.common.instance.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.reward.CommandRewardKind;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindAsset;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * Turning a decided payout into something that can be shown now and handed over later.
 *
 * <p>The case that matters most is the last one: a reward whose kind cannot say what it would hand
 * over later is DROPPED and reported, rather than promised on a results screen and then quietly
 * missing when the player comes to claim it.
 */
class DeferredRewardsTest {

    static final Subject CLAIMANT = Subject.of(UUID.randomUUID(), "{player}");

    /** A kind whose payout is a console line, which is what makes it deferrable. */
    static final class Replayable implements RewardHandler {
        @Override
        public void grant(RewardSpec spec, Subject subject) {
        }

        @Override
        public String retryCommand(RewardSpec spec, Subject subject, String sourceId) {
            return "awardxp " + subject.name() + " " + spec.param("Skill") + " " + spec.param("Amount");
        }
    }

    /** A kind that decides what it pays at grant time, so replaying it would pay something else. */
    static final class NotReplayable implements RewardHandler {
        @Override
        public void grant(RewardSpec spec, Subject subject) {
        }
    }

    static RewardKindRegistry kinds() {
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        kinds.register("Mod_Xp", "testmod", new Replayable());
        kinds.register("Rolled", "testmod", new NotReplayable());
        return kinds;
    }

    static List<InstanceReward> defer(LootGrants grants, List<String> warnings) {
        return DeferredRewards.from(grants, kinds(), CLAIMANT, "test:table", warnings::add);
    }

    // ==================== the plain leaves ====================

    @Nested
    class PlainGrants {

        @Test
        void anItemBecomesAnItemRewardSoTheInventoryGuardStillApplies() {
            List<InstanceReward> out = defer(LootGrants.ofItem("Coin_Gold", 5), new ArrayList<>());

            assertEquals(1, out.size());
            assertEquals(InstanceReward.Kind.ITEM, out.get(0).kind());
            assertEquals("Coin_Gold", out.get(0).id());
            assertEquals(5, out.get(0).quantity());
            assertTrue(out.get(0).isItem());
        }

        @Test
        void aCommandBecomesACommandReward() {
            LootGrants grants = LootGrants.of(null, null, new String[] {"say hello"}, null);
            List<InstanceReward> out = defer(grants, new ArrayList<>());

            assertEquals(1, out.size());
            assertEquals(InstanceReward.Kind.COMMAND, out.get(0).kind());
            assertEquals("say hello", out.get(0).id());
        }

        @Test
        void aBlankCommandIsSkippedRatherThanQueuedAsNothing() {
            LootGrants grants = LootGrants.of(null, null, new String[] {"  ", null}, null);
            assertEquals(List.of(), defer(grants, new ArrayList<>()));
        }

        @Test
        void anAbsentGrantsGroupDefersNothing() {
            assertEquals(List.of(), defer(null, new ArrayList<>()));
        }

        @Test
        void everyLeafIsDeferredInAuthoredOrder() {
            LootGrants grants = LootGrants.of(
                    new LootGrants.Item[] {LootGrants.Item.of("Coin_Gold", 1)}, null,
                    new String[] {"say hi"},
                    new LootGrants.Reward[] {LootGrants.Reward.of("Mod_Xp",
                            Map.of("Skill", "MINING", "Amount", "500"))});

            List<InstanceReward> out = defer(grants, new ArrayList<>());

            assertEquals(3, out.size());
            assertEquals(InstanceReward.Kind.ITEM, out.get(0).kind());
            assertEquals("say hi", out.get(1).id());
            assertEquals("awardxp {player} MINING 500", out.get(2).id());
        }
    }

    // ==================== registered kinds ====================

    @Nested
    class RegisteredRewards {

        static LootGrants xp(Map<String, String> params) {
            return LootGrants.of(null, null, null,
                    new LootGrants.Reward[] {LootGrants.Reward.of("Mod_Xp", params)});
        }

        @Test
        void aDeferredRewardKeepsThePlayerPlaceholderForTheClaimingSiteToFill() {
            List<InstanceReward> out = defer(xp(Map.of("Skill", "MINING", "Amount", "500")),
                    new ArrayList<>());

            assertEquals("awardxp {player} MINING 500", out.get(0).id(),
                    "who claims it is decided when they claim it, not when it was rolled");
        }

        @Test
        void thePresentationParametersReachTheChipWithoutReachingTheCommand() {
            List<InstanceReward> out = defer(xp(Map.of("Skill", "MINING", "Amount", "500",
                    "NameKey", "mymod.reward.xp.mining", "Icon", "Tool_Pickaxe_Crude")), new ArrayList<>());

            InstanceReward deferred = out.get(0);
            assertEquals("mymod.reward.xp.mining", deferred.displayKey());
            assertEquals("Tool_Pickaxe_Crude", deferred.iconItemId());
            assertEquals(500, deferred.quantity(), "the chip shows the amount, not a count of one");
            assertFalse(deferred.id().contains("NameKey"), "presentation never reaches the command line");
        }

        @Test
        void aRewardWithNoAmountShowsAsOne() {
            List<InstanceReward> out = defer(xp(Map.of("Skill", "MINING")), new ArrayList<>());
            assertEquals(1, out.get(0).quantity());
        }

        @Test
        void aKindThatCannotBeHandedOverLaterIsDroppedAndReported() {
            List<String> warnings = new ArrayList<>();
            LootGrants grants = LootGrants.of(null, null, null,
                    new LootGrants.Reward[] {LootGrants.Reward.of("Rolled", Map.of("Table", "x"))});

            assertEquals(List.of(), defer(grants, warnings));
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("Rolled"));
        }

        @Test
        void aKindNobodyRegisteredIsDroppedAndReported() {
            List<String> warnings = new ArrayList<>();
            LootGrants grants = LootGrants.of(null, null, null,
                    new LootGrants.Reward[] {LootGrants.Reward.of("Nobody_Registered_This", Map.of())});

            assertEquals(List.of(), defer(grants, warnings));
            assertEquals(1, warnings.size());
        }

        @Test
        void withNoRegistryAtAllEveryRegisteredRewardIsDropped() {
            LootGrants grants = LootGrants.of(null, null, null,
                    new LootGrants.Reward[] {LootGrants.Reward.of("Mod_Xp", Map.of("Skill", "MINING"))});

            assertEquals(List.of(),
                    DeferredRewards.from(grants, null, CLAIMANT, "test:table", null));
        }
    }

    // ==================== a kind's own default presentation ====================

    /**
     * A kind written as a file can say how its rewards READ, so a whole family of rewards renders
     * titled and iconed without one of them authoring a label.
     *
     * <p>The order is what these pin: a reward's own pair first, then the kind's, then nothing. The
     * last rung matters as much as the first - answering with a half-guessed label would be worse
     * than leaving the chip to work one out from the reward itself.
     */
    @Nested
    class KindPresentation {

        static final RewardKindAsset XP_KIND = RewardKindAsset.of("Mmo_Xp",
                Map.of("Skill", RewardKindAsset.Param.of(null, "ALL"),
                        "Amount", RewardKindAsset.Param.of(true, null)),
                "mmoawardxp {player} {Skill} {Amount}",
                RewardKindAsset.Presentation.of("mymod.reward.xp.{Skill}",
                        RewardKindAsset.Icon.of("Rock_Crystal_Iridescent_Small", "Skill",
                                Map.of("ARTILLERY", "Weapon_Bomb"))));

        static RewardKindRegistry authoredKinds() {
            RewardKindRegistry kinds = new RewardKindRegistry("test");
            // A recording dispatcher is never reached: deferring asks only for the line, never runs it.
            kinds.register("Mmo_Xp", "testmod", new CommandRewardKind(XP_KIND, command -> true));
            return kinds;
        }

        static InstanceReward defer(Map<String, String> params) {
            LootGrants grants = LootGrants.of(null, null, null,
                    new LootGrants.Reward[] {LootGrants.Reward.of("Mmo_Xp", params)});
            List<InstanceReward> out = DeferredRewards.from(grants, authoredKinds(), CLAIMANT,
                    "test:table", null);
            assertEquals(1, out.size());
            return out.get(0);
        }

        @Test
        void aRewardThatAuthorsNothingIsLabelledAndIconedByItsKind() {
            InstanceReward deferred = defer(Map.of("Skill", "ARTILLERY", "Amount", "300"));

            assertEquals("mymod.reward.xp.artillery", deferred.displayKey(),
                    "the value is written ARTILLERY and the key artillery; the template bridges them");
            assertEquals("Weapon_Bomb", deferred.iconItemId());
            assertEquals(300, deferred.quantity());
            assertEquals("mmoawardxp {player} ARTILLERY 300", deferred.id(),
                    "how it reads changes nothing about what it pays");
        }

        @Test
        void aRewardsOwnPairBeatsItsKinds() {
            InstanceReward deferred = defer(Map.of("Skill", "ARTILLERY", "Amount", "300",
                    "NameKey", "mymod.reward.special", "Icon", "Ingredient_Bar_Gold"));

            assertEquals("mymod.reward.special", deferred.displayKey());
            assertEquals("Ingredient_Bar_Gold", deferred.iconItemId());
        }

        @Test
        void aRewardMayOverrideJustOneHalf() {
            InstanceReward deferred = defer(Map.of("Skill", "ARTILLERY", "Amount", "300",
                    "Icon", "Ingredient_Bar_Gold"));

            assertEquals("mymod.reward.xp.artillery", deferred.displayKey(),
                    "the two are independent, so overriding the icon keeps the kind's label");
            assertEquals("Ingredient_Bar_Gold", deferred.iconItemId());
        }

        @Test
        void anUnmappedValueTakesTheKindsDefaultIcon() {
            InstanceReward deferred = defer(Map.of("Skill", "TAMING", "Amount", "50"));

            assertEquals("mymod.reward.xp.taming", deferred.displayKey());
            assertEquals("Rock_Crystal_Iridescent_Small", deferred.iconItemId(),
                    "a mapping table names only the cases worth distinguishing");
        }

        @Test
        void anOmittedParameterFillsInFromTheKindsOwnDefault() {
            assertEquals("mymod.reward.xp.all", defer(Map.of("Amount", "50")).displayKey());
        }

        @Test
        void aKindWithNothingToSayLeavesTheChipToWorkItOut() {
            LootGrants grants = LootGrants.of(null, null, null,
                    new LootGrants.Reward[] {LootGrants.Reward.of("Mod_Xp",
                            Map.of("Skill", "MINING", "Amount", "500"))});

            InstanceReward deferred = DeferredRewards
                    .from(grants, kinds(), CLAIMANT, "test:table", null).get(0);

            assertNull(deferred.displayKey(), "a Java kind knows how to pay out, not how it reads");
            assertNull(deferred.iconItemId());
        }
    }

    // ==================== a whole selection ====================

    @Test
    void awholeSelectionDefersInDecisionOrder() {
        List<LootEngine.Selected> decided = List.of(
                new LootEngine.Selected(LootGrants.ofItem("Baseline", 1), null),
                new LootEngine.Selected(null, "fanfare"),
                new LootEngine.Selected(LootGrants.ofItem("Drawn", 2), null));

        List<InstanceReward> out = DeferredRewards.fromSelection(decided, kinds(), CLAIMANT,
                "test:table", null);

        assertEquals(List.of("Baseline", "Drawn"), List.of(out.get(0).id(), out.get(1).id()));
        assertEquals(2, out.size(), "a cue with no grants beside it defers nothing");
    }

    @Test
    void aWarningSinkThatThrowsCostsOnlyItsOwnLine() {
        LootGrants grants = LootGrants.of(null, null, null,
                new LootGrants.Reward[] {LootGrants.Reward.of("Rolled", Map.of())});

        assertNull(DeferredRewards.from(grants, kinds(), CLAIMANT, "test:table", message -> {
            throw new IllegalStateException("sink blew up");
        }).stream().findFirst().orElse(null));
    }
}
