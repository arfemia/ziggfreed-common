package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;

/**
 * How a reward READS, decided from strings alone.
 *
 * <p>Everything here drives {@link RewardChips#plan}, which is the whole decision and touches
 * no engine: what the chip will SAY, which item it draws, and how many. The rendering half is one
 * call into an item's own display name and belongs to in-game smoke.
 *
 * <p>The kind ids below are this test's own inventions on purpose - the point being proved is that
 * nothing branches on a kind id, so naming a real consumer's kind would prove the opposite.
 */
class RewardChipsTest {

    @AfterEach
    void clearKinds() {
        RewardKindConfig.getInstance().loadDefaults(Map.of());
    }

    // ==================== the item form ====================

    @Test
    void aSpecNamingAnItemDrawsThatItemAndCountsIt() {
        RewardChips.Plan plan = RewardChips.plan(
                RewardSpec.of("Item", Map.of("Item", "Rock_Crystal", "Count", "3")));
        assertEquals("Rock_Crystal", plan.itemId());
        assertEquals("Rock_Crystal", plan.iconItemId(), "an item chip is drawn with its own item");
        assertEquals(3L, plan.amount());
        assertNull(plan.nameKey(), "an item names itself, so no key is invented for it");
        assertTrue(plan.isRenderable());
    }

    @Test
    void theOtherItemSpellingIsReadToo() {
        // The loot kinds accept both, so a preview that read only one would silently drop chips.
        RewardChips.Plan plan = RewardChips.plan(
                RewardSpec.of("Item", Map.of("Id", "Deco_Rope")));
        assertEquals("Deco_Rope", plan.itemId());
    }

    @Test
    void theAmountLadderMatchesWhatThePayoutReads() {
        // A reward that previews as five and pays out one is the failure this ladder exists to stop.
        assertEquals(7L, RewardChips.amountOf(
                RewardSpec.of("Anything", Map.of("Amount", "7"))));
        assertEquals(4L, RewardChips.amountOf(
                RewardSpec.of("Anything", Map.of("Count", "4"))));
        assertEquals(2L, RewardChips.amountOf(
                RewardSpec.of("Anything", Map.of("Quantity", "2"))));
        assertEquals(1L, RewardChips.amountOf(RewardSpec.of("Anything")));
        assertEquals(1L, RewardChips.amountOf(
                RewardSpec.of("Anything", Map.of("Amount", "0"))), "a chip never counts to nothing");
    }

    // ==================== the kind file's presentation ====================

    @Test
    void aKindFileNamesEveryRewardOfItsKindAtOnce() {
        RewardKindConfig.getInstance().loadDefaults(Map.of("mod_xp", RewardKindAsset.of("Mod_Xp",
                Map.of("Skill", RewardKindAsset.Param.of(Boolean.TRUE, null)),
                "/award {player} {Skill}",
                RewardKindAsset.Presentation.of("mymod.reward.xp.{Skill}",
                        RewardKindAsset.Icon.of("Tool_Pickaxe_Crude", "Skill",
                                Map.of("ARTILLERY", "Weapon_Bomb"))))));

        RewardChips.Plan mining = RewardChips.plan(
                RewardSpec.of("Mod_Xp", Map.of("Skill", "MINING", "Amount", "500")));
        assertEquals("mymod.reward.xp.mining", mining.nameKey());
        assertEquals("Tool_Pickaxe_Crude", mining.iconItemId(), "an unmapped value falls to the default");
        assertEquals(500L, mining.amount());
        assertTrue(mining.isRenderable());

        RewardChips.Plan artillery = RewardChips.plan(
                RewardSpec.of("Mod_Xp", Map.of("Skill", "ARTILLERY")));
        assertEquals("Weapon_Bomb", artillery.iconItemId(), "a mapped value wins over the default");
    }

    @Test
    void aRewardsOwnWordsWinOverItsKindsDefault() {
        RewardKindConfig.getInstance().loadDefaults(Map.of("mod_xp", RewardKindAsset.of("Mod_Xp",
                Map.of(), null,
                RewardKindAsset.Presentation.of("mymod.reward.xp",
                        RewardKindAsset.Icon.of("Tool_Pickaxe_Crude", null, Map.of())))));

        RewardChips.Plan plan = RewardChips.plan(RewardSpec.of("Mod_Xp",
                Map.of("NameKey", "mymod.reward.special", "Icon", "Deco_Rope")));
        assertEquals("mymod.reward.special", plan.nameKey());
        assertEquals("Deco_Rope", plan.iconItemId());
    }

    // ==================== what cannot be named ====================

    @Test
    void aRewardNothingCanNameIsDroppedRatherThanGuessedAt() {
        // Painting a raw kind token at a player reads as a promise of something called that, which is
        // worse than showing one fewer chip. The fix is a Presentation on the kind file.
        RewardChips.Plan plan = RewardChips.plan(
                RewardSpec.of("Mod_Mystery", Map.of("Amount", "3")));
        assertFalse(plan.isRenderable());
        assertNull(RewardChips.chipFor(RewardSpec.of("Mod_Mystery", Map.of("Amount", "3"))));
    }

    @Test
    void aContributedReadingRescuesWhatWouldOtherwiseDrop() {
        // A kind's owner contributes the reading once; no reward of that kind authors anything.
        // The kind id is this test's own so the process-wide registration cannot leak meaningfully.
        RewardChips.contribute(spec -> "Test_Wallet_Kind".equalsIgnoreCase(spec.kind())
                ? RewardChip.text(Msg.raw("5 Test Tokens"))
                : null);
        assertEquals(1, RewardChips.chipsFor(
                List.of(RewardSpec.of("Test_Wallet_Kind", Map.of("Currency", "test", "Amount", "5"))),
                null).size(), "the contributed rung names what the generic reading cannot");
        assertNull(RewardChips.chipFor(RewardSpec.of("Mod_Mystery", Map.of("Amount", "3"))),
                "a kind no contribution answers still drops");

        RewardKindConfig.getInstance().loadDefaults(Map.of("test_wallet_kind",
                RewardKindAsset.of("Test_Wallet_Kind", Map.of(), null,
                        RewardKindAsset.Presentation.of("mymod.reward.file_says",
                                RewardKindAsset.Icon.of(null, null, Map.of())))));
        RewardChips.Plan plan = RewardChips.plan(
                RewardSpec.of("Test_Wallet_Kind", Map.of("Amount", "5")));
        assertEquals("mymod.reward.file_says", plan.nameKey(),
                "a kind FILE's Presentation still wins over the contributed rung");
    }

    @Test
    void aConsumersOwnReadingWinsAndAThrowingOneCostsNothing() {
        RewardSpec spec = RewardSpec.of("Mod_Mystery", Map.of("Amount", "3"));
        assertEquals(1, RewardChips.chipsFor(List.of(spec),
                s -> RewardChip.text(Msg.raw("three mysteries"))).size(),
                "a consumer can name what the generic reading cannot");
        assertTrue(RewardChips.chipsFor(List.of(spec), s -> {
            throw new IllegalStateException("boom");
        }).isEmpty(), "a throwing seam falls through to the generic reading, which drops this one");
    }
}
