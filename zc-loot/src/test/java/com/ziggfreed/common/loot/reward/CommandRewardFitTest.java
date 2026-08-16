package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;

/**
 * A {@code Command} reward whose line is a {@code give} hands over an ITEM, so the fit probe has to
 * see it.
 *
 * <p>The failure this closes is the one the batch exists to prevent, one level down: a hand-in
 * spends its completion because the probe reported room, and the command's item then lands on the
 * floor because nothing ever asked about it. What is pinned here is the part that decides WHETHER a
 * command needs room at all, which is settled from the line alone - a spec with nothing to hand over
 * answers null and is skipped, exactly like a rolling kind. Whether a stack that DOES need room
 * fits is {@code InventoryGrant}'s answer about a live player, and lands with the in-game pass.
 */
class CommandRewardFitTest {

    private Subject player;

    @BeforeEach
    void setUp() {
        player = Subject.of(UUID.randomUUID(), "tester");
    }

    private RewardSpec command(String line) {
        return RewardSpec.of(LootRewardKinds.KIND_COMMAND, LootRewardKinds.P_COMMAND, line);
    }

    @Test
    void aCommandThatHandsOverNoItemNeedsNoRoom() {
        assertNull(LootRewardKinds.stackFor(command("say well done {player}"), player, "quest:demo"));
        assertNull(LootRewardKinds.stackFor(command("tp {player} 0 64 0"), player, "quest:demo"));
    }

    @Test
    void aCommandRewardThatNamesNoCommandIsReadAsNeedingNoRoomRatherThanThrowing() {
        // Refusing here would report a full bag for what is really an authoring mistake, and hide
        // the mistake behind a message about inventory space. The grant path still fails loudly.
        assertNull(LootRewardKinds.stackFor(RewardSpec.of(LootRewardKinds.KIND_COMMAND), player, "quest:demo"));
    }

    @Test
    void anotherModsKindIsStillNoneOfThisProbesBusiness() {
        assertNull(LootRewardKinds.stackFor(RewardSpec.of("Mmo_Xp", "skill", "MINING"), player, "quest:demo"));
    }

    @Test
    void aListOfRewardsThatNeedNoRoomFitsWithNobodyToHandThemTo() {
        assertTrue(LootRewardKinds.canAddAll(
                List.of(command("say hello"), RewardSpec.of("Mmo_Xp", "skill", "MINING")),
                player, "quest:demo"));
    }

    @Test
    void theSourceLabelReachesTheCommandLineTheProbeReads() {
        // {source} substitutes exactly as it will at grant time, so a line whose ITEM is named by
        // the source cannot read one way in the probe and another way in the payout. This one names
        // no item at all either way, which is what makes it assertable with no engine underneath.
        assertNull(LootRewardKinds.stackFor(command("say finished {source}"), player, "quest:demo"));
    }
}
