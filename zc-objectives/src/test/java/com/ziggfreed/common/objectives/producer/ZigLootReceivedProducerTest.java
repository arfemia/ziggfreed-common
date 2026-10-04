package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.loot.reward.LootReceivedEvent;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;

/**
 * The whole decision this producer makes, with no server near it: one {@code LOOT_RECEIVED} per item
 * a payout handed over, on that item, qualified by what paid it. The engine half (resolving the
 * player, hopping onto their world after the payout settles) is the shared bus dispatch and lands
 * behind in-game smoke.
 */
class ZigLootReceivedProducerTest {

    private static final UUID ALICE = UUID.randomUUID();

    private record Fired(@Nonnull UUID playerId, @Nonnull String kind, @Nonnull String target,
            @Nullable String qualifier, long amount) {
    }

    @Nonnull
    private static List<Fired> fan(@Nonnull LootReceivedEvent event) {
        List<Fired> fired = new ArrayList<>();
        ZigLootReceivedProducer.fanOut(event, (playerId, kind, target, qualifier, amount) ->
                fired.add(new Fired(playerId, kind, target, qualifier, amount)));
        return fired;
    }

    @Test
    void eachReceivedItemIsOneMomentOnItsItemQualifiedByThePayout() {
        LootReceivedEvent event = new LootReceivedEvent(ALICE, "achievement:festival_keeper_2026",
                List.of(new LootReceivedEvent.Received("Fixture_Lantern", 1),
                        new LootReceivedEvent.Received("Fixture_Gem", 5)));

        assertEquals(List.of(
                new Fired(ALICE, "LOOT_RECEIVED", "Fixture_Lantern", "achievement:festival_keeper_2026", 1),
                new Fired(ALICE, "LOOT_RECEIVED", "Fixture_Gem", "achievement:festival_keeper_2026", 5)),
                fan(event));
    }

    @Test
    void aPayoutNamingNoSourceIsUnqualified() {
        LootReceivedEvent event = new LootReceivedEvent(ALICE, "  ",
                List.of(new LootReceivedEvent.Received("Fixture_Gem", 1)));

        assertNull(fan(event).get(0).qualifier(), "an unqualified step and an unqualified moment meet");
    }

    @Test
    void theProducerFiresTheBuiltInKind() {
        assertEquals("LOOT_RECEIVED", ZigLootReceivedProducer.KIND);
        assertTrue(ObjectiveKindRegistry.isBuiltIn(ZigLootReceivedProducer.KIND));
    }
}
