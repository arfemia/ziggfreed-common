package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.stats.gearset.GearSetKeys.TierRef;
import com.ziggfreed.common.stats.gearset.GearSets.TierFlip;

/**
 * What the engine remembers is the player's own, whatever world they are in: a recompute after a
 * world change that finds the same tiers active announces nothing, and a player coming back from an
 * instance is diffed against what they last had on, never against what they wore when they left.
 * Pure: the table and the announcement rule, with no engine type.
 */
class GearSetAppliedTest {

    private static final TierRef TWO_PIECES = new TierRef("Night_Set", 0);
    private static final TierRef FULL_ARMOR = new TierRef("Night_Set", 1);

    private final UUID player = UUID.randomUUID();

    @BeforeEach
    @AfterEach
    void emptyTheTable() {
        GearSetApplied.clearForTests();
    }

    @Nonnull
    private static GearSetApplied.Applied wrote(@Nonnull Set<TierRef> active) {
        return new GearSetApplied.Applied(active, Set.of("Night_Set_Look"), active);
    }

    @Test
    void aRecomputeAfterAWorldChangeFiresNoFlip() {
        GearSetApplied.put(player, wrote(Set.of(TWO_PIECES, FULL_ARMOR)));

        // The player moves world: the table is not asked about worlds at all, so the next world's
        // ready-event recompute finds the row the last one wrote.
        GearSetApplied.Applied previous = GearSetApplied.get(player);

        assertNotNull(previous, "a world change keeps the player's row");
        assertTrue(GearSets.announcements(previous, List.of(TWO_PIECES, FULL_ARMOR)).isEmpty(),
                "the same tiers still active is not a flip, so nothing is announced");
    }

    @Test
    void comingBackFromAnInstanceIsDiffedAgainstTheLastWriteNotTheWorldLeft() {
        GearSetApplied.put(player, wrote(Set.of(TWO_PIECES)));   // in the overworld
        GearSetApplied.put(player, wrote(Set.of(TWO_PIECES, FULL_ARMOR)));   // in the instance, suited up

        List<TierFlip> onReturn = GearSets.announcements(GearSetApplied.get(player), List.of(TWO_PIECES, FULL_ARMOR));

        assertTrue(onReturn.isEmpty(), "the armor tier came on in the instance and was announced there; "
                + "the return is not a second 'set on'");
        assertEquals(1, GearSetApplied.size(), "one row per player, not one per world visited");
    }

    @Test
    void aRealFlipAfterAWorldChangeIsStillAnnounced() {
        GearSetApplied.put(player, wrote(Set.of(TWO_PIECES, FULL_ARMOR)));

        List<TierFlip> flips = GearSets.announcements(GearSetApplied.get(player), List.of(TWO_PIECES));

        assertEquals(List.of(new TierFlip(FULL_ARMOR, false)), flips);
    }

    @Test
    void theFirstRecomputeSinceLoginIsAHydrateAndAnnouncesNothing() {
        GearSetApplied.put(player, wrote(Set.of(TWO_PIECES)));
        GearSetApplied.forget(player);   // disconnect

        assertNull(GearSetApplied.get(player), "a disconnect evicts the row");
        assertTrue(GearSets.announcements(null, List.of(TWO_PIECES, FULL_ARMOR)).isEmpty(),
                "with no row the recompute is a hydrate, not a flip");
    }
}
