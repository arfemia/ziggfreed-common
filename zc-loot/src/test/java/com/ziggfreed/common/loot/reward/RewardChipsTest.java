package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.LongParamValue;
import com.ziggfreed.common.i18n.LangCatalog;
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
        LangCatalog.overrideForTests(null);
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
        assertNull(plan.nameKey(), "no key names it");
        assertNull(plan.itemId(), "and it is no item, so nothing can paint it");
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
                "a kind FILE's Presentation still wins over the contributed rung (at render time, "
                        + "provided something ships the key it resolves to)");
    }

    // ==================== the kind-file rung needs a key something ships ====================

    @Test
    void aKindFileKeyNothingShipsFallsThroughRatherThanPaintingARawKey() {
        RewardKindConfig.getInstance().loadDefaults(Map.of("mod_xp", RewardKindAsset.of("Mod_Xp",
                Map.of("Skill", RewardKindAsset.Param.of(Boolean.TRUE, null)),
                "/award {player} {Skill}",
                RewardKindAsset.Presentation.of("mymod.reward.xp.{Skill}", null))));
        LangCatalog.overrideForTests(Map.of("mymod.reward.xp.mining", "+{0} Mining XP"));

        RewardChip mining = RewardChips.chipFor(
                RewardSpec.of("Mod_Xp", Map.of("Skill", "MINING", "Amount", "500")));
        assertNotNull(mining, "a key the server ships renders through the kind file's template");
        assertEquals("mymod.reward.xp.mining", mining.label().getFormattedMessage().messageId);

        assertNull(RewardChips.chipFor(
                RewardSpec.of("Mod_Xp", Map.of("Skill", "HERBALISM", "Amount", "500"))),
                "a skill the key family never heard of falls through (and here drops), never "
                        + "painting the unresolvable key at the player");
    }

    @Test
    void aRewardsOwnKeyRendersAsWrittenEvenWhenNothingShipsIt() {
        // The author's explicit word painting as a traceable raw key is how the author finds the
        // typo; only the kind FILE's default is held to the something-ships-it bar.
        RewardChip chip = RewardChips.chipFor(RewardSpec.of("Mod_Mystery",
                Map.of("NameKey", "mymod.reward.special", "Amount", "3")));
        assertNotNull(chip);
        assertEquals("mymod.reward.special", chip.label().getFormattedMessage().messageId,
                "the authored key goes out as written - the traceable raw key IS the point");
    }

    // ==================== a reward's own Icon survives onto the contributed rung ====================

    @Test
    void aRewardsOwnIconRePointsAContributedChip() {
        // "A reward's own words and picture win first" holds on EVERY rung: a contributed reading
        // supplies the label the generic ladder could not, but a reward that authored its own Icon
        // is still drawn with it, not with the contribution's computed one.
        RewardChips.contribute(spec -> "Test_Icon_Kind".equalsIgnoreCase(spec.kind())
                ? RewardChip.of("Contrib_Item", Msg.raw("contributed label"))
                : null);

        RewardChip authored = RewardChips.chipFor(RewardSpec.of("Test_Icon_Kind",
                Map.of("Icon", "Deco_Rope", "Amount", "2")));
        assertNotNull(authored);
        assertEquals("Deco_Rope", authored.iconItemId(),
                "the reward's own authored Icon wins over the contribution's");
        assertEquals("contributed label", authored.label().getFormattedMessage().rawText,
                "the contribution's label is kept - only the picture is re-pointed");

        RewardChip bare = RewardChips.chipFor(RewardSpec.of("Test_Icon_Kind", Map.of("Amount", "2")));
        assertNotNull(bare);
        assertEquals("Contrib_Item", bare.iconItemId(),
                "with nothing authored, the contribution's own picture stands");
    }

    // ==================== a suspect Args entry drops rather than painting a token ====================

    @Test
    void anArgsEntryNamingNothingDeclaredDropsRatherThanRenderingARawToken() {
        // "Amonut" declares nothing and looks like no key (no '.' and no '{'): almost always a
        // mis-spelled parameter name. Its blank fills as EMPTY - never the raw word - exactly as
        // FeedbackMomentAsset.Line.Args refuses an argument the moment does not carry; the
        // reward-kind validator reports SUSPECT_ARG for it.
        RewardKindConfig.getInstance().loadDefaults(Map.of("mod_xp", RewardKindAsset.of("Mod_Xp",
                Map.of("Amount", RewardKindAsset.Param.of(Boolean.TRUE, null)),
                "/award {player} {Amount}",
                RewardKindAsset.Presentation.of("mymod.reward.typo", null,
                        new String[] {"Amonut"}))));
        LangCatalog.overrideForTests(Map.of("mymod.reward.typo", "+{0} things"));

        RewardChip chip = RewardChips.chipFor(
                RewardSpec.of("Mod_Xp", Map.of("Amount", "9")));
        assertNotNull(chip);
        FormattedMessage fm = chip.label().getFormattedMessage();
        assertNotNull(fm.messageParams, "the dropped entry still fills its blank, as empty");
        assertEquals("", fm.messageParams.get("0").rawText,
                "the blank renders empty, never the literal 'Amonut'");
    }

    // ==================== Args: what fills the key's blanks ====================

    @Test
    void argsBindDeclaredParamsAsNumbersAndKeyTemplatesAsNestedNames() {
        RewardKindConfig.getInstance().loadDefaults(Map.of("mod_xp", RewardKindAsset.of("Mod_Xp",
                Map.of("Skill", RewardKindAsset.Param.of(Boolean.TRUE, null),
                        "Amount", RewardKindAsset.Param.of(Boolean.TRUE, null)),
                "/award {player} {Skill} {Amount}",
                RewardKindAsset.Presentation.of("mymod.reward.line.xp", null,
                        new String[] {"Amount", "mymod.skill.{Skill}"}))));
        LangCatalog.overrideForTests(Map.of("mymod.reward.line.xp", "+{0, number} {1} XP"));

        RewardChip chip = RewardChips.chipFor(
                RewardSpec.of("Mod_Xp", Map.of("Skill", "MINING", "Amount", "2000")));
        assertNotNull(chip);
        FormattedMessage fm = chip.label().getFormattedMessage();
        assertEquals("mymod.reward.line.xp", fm.messageId);
        assertNotNull(fm.params, "the amount must bind as a typed numeric param");
        assertEquals(2000L, ((LongParamValue) fm.params.get("0")).value,
                "a declared parameter that reads as a number binds as one, so a {0, number} "
                        + "blank groups its digits in the player's own locale");
        assertNotNull(fm.messageParams, "the skill name must bind as a nested Message");
        assertEquals("mymod.skill.mining", fm.messageParams.get("1").messageId,
                "a key-template entry renders the VALUE's translated name, never the raw MINING");
    }

    @Test
    void withoutArgsTheOneBlankIsStillTheAmount() {
        RewardKindConfig.getInstance().loadDefaults(Map.of("mod_xp", RewardKindAsset.of("Mod_Xp",
                Map.of("Skill", RewardKindAsset.Param.of(Boolean.TRUE, null)),
                "/award {player} {Skill}",
                RewardKindAsset.Presentation.of("mymod.reward.simple", null))));
        LangCatalog.overrideForTests(Map.of("mymod.reward.simple", "+{0} things"));

        RewardChip chip = RewardChips.chipFor(
                RewardSpec.of("Mod_Xp", Map.of("Skill", "MINING", "Amount", "7")));
        assertNotNull(chip);
        FormattedMessage fm = chip.label().getFormattedMessage();
        assertNotNull(fm.params);
        assertEquals(7L, ((LongParamValue) fm.params.get("0")).value);
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
