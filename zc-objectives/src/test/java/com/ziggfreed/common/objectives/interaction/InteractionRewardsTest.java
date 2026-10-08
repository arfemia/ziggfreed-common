package com.ziggfreed.common.objectives.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSounds;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * How a ZigGrantReward node pays: which rewards, whether at all, labelled how, and to whom; and that a
 * payout here tells the player what it actually handed over, as a quest's Collect does.
 */
class InteractionRewardsTest {

    private static final Subject PLAYER = Subject.of(UUID.randomUUID(), "tester");

    // ==================== what the player is shown ====================

    @Test
    void aPayoutShowsWhatItActuallyHandedOverNeverTheBundlesName() {
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        kinds.register("Test_Bundle", "test", rolled(RewardSpec.of("Test_Sweets", "amount", "12"),
                RewardSpec.of("Test_Favor", "amount", "5")));
        List<ToastSpec> shown = new ArrayList<>();

        RewardGrants.GrantOutcome outcome = InteractionRewards.payAndShow(List.of(RewardSpec.of("Test_Bundle")),
                PLAYER, "interaction:Test_Geode", kinds, null, naming(), shown::add);

        assertEquals(1, outcome.granted());
        assertEquals(1, shown.size(), "one toast for the payout");
        ToastSpec toast = shown.get(0);
        assertEquals(ToastKind.REWARD, toast.kind(), "the gold claim toast a quest's Collect raises");
        assertEquals("ziggfreedcommon.progression.reward.received", toast.message().getFormattedMessage().messageId);
        assertEquals(List.of("Test_Sweets 12", "Test_Favor 5"),
                toast.lines().stream().map(l -> l.text().getFormattedMessage().rawText).toList(),
                "one row per thing the roll paid, read through the chip source");
        assertEquals(ToastSounds.RECEIPT, toast.effectiveSoundId(),
                "no moment fires for a payout outside a quest, so the receipt plays its own sound");
    }

    @Test
    void aPayoutThatHandedNothingOverShowsNothing() {
        RewardKindRegistry kinds = new RewardKindRegistry("test");
        kinds.register("Test_Empty_Roll", "test", rolled());
        List<ToastSpec> shown = new ArrayList<>();

        InteractionRewards.payAndShow(List.of(RewardSpec.of("Test_Empty_Roll"), RewardSpec.of("No_Such_Kind")),
                PLAYER, "interaction:Test_Geode", kinds, null, naming(), shown::add);

        assertTrue(shown.isEmpty(), "an empty roll and a lost reward hand nothing over, so nothing is said");
        assertNull(InteractionRewards.receiptToast(RewardGrants.GrantOutcome.EMPTY, naming()));
    }

    @Test
    void theHeadlineIsAuthoredInEnglish() throws IOException {
        Path lang = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
                "ziggfreedcommon.progression.lang");
        assertTrue(Files.readAllLines(lang, StandardCharsets.UTF_8).stream()
                .anyMatch(line -> line.startsWith(InteractionRewards.RECEIVED_KEY + " = ")),
                "the receipt toast's headline resolves for the client");
    }

    /** A kind that rolls: it reports what landed, never itself. */
    @Nonnull
    private static RewardHandler rolled(@Nonnull RewardSpec... landed) {
        return new RewardHandler() {
            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
            }

            @Override
            public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId,
                    @Nonnull Consumer<RewardSpec> receipt) {
                for (RewardSpec each : landed) {
                    receipt.accept(each);
                }
            }
        };
    }

    /** The consumer chip source, naming every reward by its kind and amount. */
    @Nonnull
    private static RewardChips.Source naming() {
        return spec -> RewardChip.text(Msg.raw(spec.kind() + " " + spec.paramOr("amount", "?")));
    }

    // ==================== what is paid ====================

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
