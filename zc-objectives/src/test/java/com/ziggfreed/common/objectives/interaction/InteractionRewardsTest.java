package com.ziggfreed.common.objectives.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.asset.PresenceRequiresCodec;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.subject.Subject;

/** How a ZigGrantReward node pays: which rewards, whether at all, labelled how, and to whom. */
class InteractionRewardsTest {

    private static final Subject PLAYER = Subject.of(UUID.randomUUID(), "tester");

    @Test
    void aRewardsListPaysItsEntriesInOrderAndDropsOneNamingNoKind() {
        RewardEntryAsset[] entries = {
                RewardEntryAsset.of("Test_Coin", Map.of("Amount", "5")),
                RewardEntryAsset.of("  ", Map.of("Amount", "9")),
                null,
                RewardEntryAsset.of("Item", Map.of("Item", "Rock_Stone", "Count", "2"))
        };

        List<RewardSpec> specs = InteractionRewards.specs(entries);

        assertEquals(List.of("Test_Coin", "Item"), specs.stream().map(RewardSpec::kind).toList());
        assertEquals("5", specs.get(0).param("amount"), "a parameter reads however its file spelled the key");
    }

    /** An inline row gated on a missing mod is absent here too; no store folds this list, so nothing counts it. */
    @Test
    void aRowGatedOnAMissingModPaysNothingWhileItsSiblingsPay() {
        String mmo = "Ziggfreed:MMOSkillTree";
        List<String> lines = new ArrayList<>();
        ModGates.useProbeForTests(param -> param != null && mmo.equals(param.trim()) ? 0.0 : 1.0);
        ModGates.reportIntoForTests(lines::add);
        try {
            RewardEntryAsset[] entries = {
                    RewardEntryAsset.of("Item", Map.of("Item", "Harvest_Feast_Pie", "Count", "1")),
                    RewardEntryAsset.of("Mmo_Xp", Map.of("Skill", "Cooking"), PresenceRequiresCodec.Block.of(
                            FactorCondition.of("hytale:mod_installed", mmo, 1.0, null)))
            };

            assertEquals(List.of("Item"), InteractionRewards.specs(entries).stream().map(RewardSpec::kind).toList());
            assertTrue(lines.isEmpty(), "an inline list is read, never folded, so nothing is counted: " + lines);
        } finally {
            ModGates.useProbeForTests(null);
            ModGates.reportIntoForTests(null);
        }
    }

    @Test
    void noRewardsListPaysNothing() {
        assertEquals(List.of(), InteractionRewards.specs(null));
        assertEquals(List.of(), InteractionRewards.specs(new RewardEntryAsset[0]));
    }

    @Test
    void anAbsentChanceOrOneOrMoreAlwaysPays() {
        assertTrue(InteractionRewards.rolls(null, () -> 0.999));
        assertTrue(InteractionRewards.rolls(1f, () -> 0.999));
        assertTrue(InteractionRewards.rolls(4f, () -> 0.999), "a chance above one is a certainty, not an error");
    }

    @Test
    void aChanceRollsAgainstTheDraw() {
        assertTrue(InteractionRewards.rolls(0.25f, () -> 0.2));
        assertFalse(InteractionRewards.rolls(0.25f, () -> 0.25), "the draw must land below the chance");
    }

    @Test
    void aChanceOfZeroBelowZeroOrNotANumberNeverPays() {
        assertFalse(InteractionRewards.rolls(0f, () -> 0.0));
        assertFalse(InteractionRewards.rolls(-1f, () -> 0.0));
        assertFalse(InteractionRewards.rolls(Float.NaN, () -> 0.0),
                "a malformed chance pays nothing rather than everything");
    }

    @Test
    void aPayoutIsLabelledWithTheItemUsedElseTheType() {
        assertEquals("interaction:Rock_Geode", InteractionRewards.sourceId("Rock_Geode", "ZigGrantReward"));
        assertEquals("interaction:ZigGrantReward", InteractionRewards.sourceId(null, "ZigGrantReward"));
        assertEquals("interaction:ZigGrantReward", InteractionRewards.sourceId("  ", "ZigGrantReward"));
    }

    @Test
    void eachRewardReachesItsKindWithThePayoutLabelWrittenOn() {
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        List<RewardSpec> paid = new ArrayList<>();
        kinds.register("Test_Coin", "test", (spec, subject) -> paid.add(spec));

        RewardGrants.GrantOutcome outcome = InteractionRewards.pay(
                List.of(RewardSpec.of("Test_Coin", "amount", "5")), PLAYER, "interaction:Rock_Geode", kinds, null);

        assertEquals(1, outcome.granted());
        assertEquals(1, paid.size());
        assertEquals("interaction:Rock_Geode", paid.get(0).param(RewardGrants.P_SOURCE));
    }

    @Test
    void aKindNobodyRegisteredIsCountedLostAndNeverThrown() {
        RewardGrants.GrantOutcome outcome = InteractionRewards.pay(
                List.of(RewardSpec.of("No_Such_Kind")), PLAYER, "interaction:Rock_Geode",
                new RewardKindRegistry("test"), null);

        assertEquals(0, outcome.granted());
        assertEquals(1, outcome.failed());
    }
}
