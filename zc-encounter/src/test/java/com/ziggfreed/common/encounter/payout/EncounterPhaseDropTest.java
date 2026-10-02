package com.ziggfreed.common.encounter.payout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.reward.RewardKinds;

/**
 * A phase drop hands its payouts over through the loot engine's own grant pass: every item at its
 * count and every drop list, in payout order, as it did when it applied them by hand; its commands
 * through the engine's command leaf, resolved like every other loot command (the phase's
 * placeholders substituted, a positional give count read as {@code --quantity=N}, a failed line
 * warned) and counted as the engine reports them; and a registered reward kind reported once per
 * payout and never paid, even when this server registers that kind.
 */
class EncounterPhaseDropTest {

    private static final String KIND = "Phase_Drop_Test_Kind";

    private final AtomicInteger paid = new AtomicInteger();

    @BeforeEach
    void registerAKindThePhaseMustNotPay() {
        RewardKinds.shared().register(KIND, (spec, subject) -> paid.incrementAndGet());
    }

    @AfterEach
    void clearTheSharedVocabulary() {
        RewardKinds.clear();
    }

    /** Sinks that record what the engine handed them, answering that all of it landed. */
    private static final class Recording {
        final List<String> items = new ArrayList<>();
        final List<String> dropLists = new ArrayList<>();

        LootEngine.Sinks sinks() {
            return LootEngine.Sinks.builder()
                    .items((itemId, count) -> {
                        items.add(itemId + " x" + count);
                        return count;
                    })
                    .dropLists(dropListId -> {
                        dropLists.add(dropListId);
                        return Map.of();
                    })
                    .build();
        }
    }

    /** The phase drop's real sinks, its commands through {@code dispatcher}. */
    private static LootEngine.Sinks phaseSinks(List<ItemStack> pile, List<String> warned,
            CommandRunner.Dispatcher dispatcher) {
        return EncounterLoot.phaseSinks(pile, warned::add, dispatcher, "Zc_Encounter_Test", "Enraged", "ab12cd34");
    }

    private static LootEngine.Selected payout(LootGrants grants) {
        return new LootEngine.Selected(grants, null);
    }

    @Test
    void itemsAndDropListsReachTheSinksInPayoutOrder() {
        Recording rec = new Recording();
        LootGrants first = LootGrants.of(
                new LootGrants.Item[] {LootGrants.Item.of("Coin_Gold", 3), LootGrants.Item.of("Gem_Ruby", null)},
                new String[] {"Drops_Boss", " ", null}, null, null);
        LootGrants second = LootGrants.ofItem("Bone", 2);

        LootEngine.Result result = EncounterLoot.handOverPhase(List.of(payout(first), payout(second)),
                rec.sinks(), () -> { });

        assertEquals(List.of("Coin_Gold x3", "Gem_Ruby x1", "Bone x2"), rec.items,
                "each item at its count, an omitted count handing over one");
        assertEquals(List.of("Drops_Boss"), rec.dropLists, "a blank drop list entry rolls nothing");
        assertEquals(0, result.getCommandsRun());
    }

    @Test
    void commandsResolveThroughTheEngineCommandLeafAndAreCountedAsItReports() {
        List<String> dispatched = new ArrayList<>();
        List<String> warned = new ArrayList<>();
        CommandRunner.Dispatcher dispatcher = line -> {
            dispatched.add(line);
            return !line.startsWith("refused");
        };
        LootGrants a = LootGrants.of(null, null,
                new String[] {"  give Looter Coin_Gold 32  ", "say {encounter} {phase} {run}"}, null);
        LootGrants b = LootGrants.of(null, null, new String[] {" ", "refused {phase}", "say {player}"}, null);

        LootEngine.Result result = EncounterLoot.handOverPhase(List.of(payout(a), payout(b)),
                phaseSinks(new ArrayList<>(), warned, dispatcher), () -> { });

        assertEquals(List.of("give Looter Coin_Gold --quantity=32", "say Zc_Encounter_Test Enraged ab12cd34",
                "refused Enraged", "say {player}"), dispatched,
                "trimmed, the phase's placeholders substituted, a positional give count read as --quantity, "
                        + "a key the phase does not carry left standing, a blank line never dispatched");
        assertEquals(3, result.getCommandsRun(), "the count is what the engine reports: a refused line is not run");
        assertEquals(1, warned.size(), warned.toString());
        assertTrue(warned.get(0).contains("refused Enraged"), warned.get(0));
    }

    @Test
    void aRegisteredRewardKindIsReportedOncePerPayoutAndNeverPaid() {
        List<ItemStack> pile = new ArrayList<>();
        List<String> warned = new ArrayList<>();
        AtomicInteger refused = new AtomicInteger();
        LootGrants.Reward reward = LootGrants.Reward.of(KIND, null);
        LootGrants twoKinds = LootGrants.of(null, null, null, new LootGrants.Reward[] {reward, reward});
        LootGrants oneKind = LootGrants.of(null, null, null, new LootGrants.Reward[] {reward});
        assertTrue(RewardKinds.shared().isRegistered(KIND), "the kind is payable on this server");

        LootEngine.Result result = EncounterLoot.handOverPhase(List.of(payout(twoKinds), payout(oneKind)),
                phaseSinks(pile, warned, line -> true), refused::incrementAndGet);

        assertEquals(2, refused.get(), "one report per payout that authors a kind, however many it authors");
        assertEquals(0, paid.get(), "a phase drop has nobody to pay, so the kind's handler never runs");
        assertTrue(pile.isEmpty());
        assertTrue(warned.isEmpty(), warned.toString());
        assertEquals(0, result.getRewardsPaid());
        assertEquals(0, result.getCommandsRun());
    }

    @Test
    void aPayoutWithNoGrantsIsSkippedWhole() {
        Recording rec = new Recording();
        AtomicInteger refused = new AtomicInteger();

        LootEngine.Result result = EncounterLoot.handOverPhase(List.of(new LootEngine.Selected(null, "Cue_Only")),
                rec.sinks(), refused::incrementAndGet);

        assertTrue(rec.items.isEmpty() && rec.dropLists.isEmpty());
        assertEquals(0, refused.get());
        assertEquals(0, result.getCommandsRun());
    }
}
