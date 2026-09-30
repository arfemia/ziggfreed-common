package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.RemoveReason;
import com.ziggfreed.common.stats.gearset.GearSetKeys.TierRef;
import com.ziggfreed.common.stats.gearset.GearSets.TierFlip;

/**
 * What the engine remembers lasts while the player's entity sits in one store: a world change
 * forgets it like a disconnect does (both leave with {@code UNLOAD}), so the next world's first
 * recompute is a hydrate and announces nothing, a player coming back from an instance is not
 * diffed against what they wore when they left, and a real flip inside a world is still announced.
 * Pure: the table, the removal rule and the announcement rule; the only engine type is the
 * {@code RemoveReason} enum.
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

    private void changeWorld() {
        GearSets.onEntityRemoved(player, RemoveReason.UNLOAD);
    }

    @Test
    void aRecomputeAfterAWorldChangeFiresNoFlip() {
        GearSetApplied.put(player, wrote(Set.of(TWO_PIECES, FULL_ARMOR)));

        changeWorld();
        GearSetApplied.Applied previous = GearSetApplied.get(player);

        assertNull(previous, "a world change forgets the row");
        assertTrue(GearSets.announcements(previous, List.of(TWO_PIECES, FULL_ARMOR)).isEmpty(),
                "so the next world's first recompute is a hydrate, which announces nothing");
    }

    @Test
    void comingBackFromAnInstanceIsAQuietHydrateNotADiffAgainstTheWorldLeft() {
        GearSetApplied.put(player, wrote(Set.of(TWO_PIECES)));   // in the overworld
        changeWorld();
        GearSetApplied.put(player, wrote(Set.of(TWO_PIECES, FULL_ARMOR)));   // in the instance, suited up
        changeWorld();

        List<TierFlip> onReturn = GearSets.announcements(GearSetApplied.get(player), List.of(TWO_PIECES, FULL_ARMOR));

        assertTrue(onReturn.isEmpty(), "the armor tier came on in the instance and was announced there; "
                + "the return is not a second 'set on'");
        assertEquals(0, GearSetApplied.size(), "no row survives a world change, so none piles up per world");
    }

    @Test
    void aRealFlipInsideAWorldIsStillAnnounced() {
        GearSetApplied.put(player, wrote(Set.of(TWO_PIECES, FULL_ARMOR)));

        List<TierFlip> flips = GearSets.announcements(GearSetApplied.get(player), List.of(TWO_PIECES));

        assertEquals(List.of(new TierFlip(FULL_ARMOR, false)), flips);
    }

    @Test
    void theFirstRecomputeSinceLoginIsAHydrateAndAnnouncesNothing() {
        GearSetApplied.put(player, wrote(Set.of(TWO_PIECES)));
        GearSets.onEntityRemoved(player, RemoveReason.UNLOAD);   // the disconnect's own removal

        assertNull(GearSetApplied.get(player), "a disconnect evicts the row");
        assertTrue(GearSets.announcements(null, List.of(TWO_PIECES, FULL_ARMOR)).isEmpty(),
                "with no row the recompute is a hydrate, not a flip");
    }
}
