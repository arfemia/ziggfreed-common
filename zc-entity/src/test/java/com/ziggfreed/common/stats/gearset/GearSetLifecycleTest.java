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

import com.hypixel.hytale.component.RemoveReason;
import com.ziggfreed.common.stats.gearset.GearSetKeys.TierRef;
import com.ziggfreed.common.stats.gearset.GearSets.EffectChanges;

/**
 * The lifecycle paths a player's look and row go through beyond an equip change: a death and
 * respawn (the engine clears every effect, the row still lists the look), a death itself (no look
 * on a corpse), and the end of a session (a recompute queued before the disconnect must not leave a
 * row behind). Pure: the effect rule, the table and the removal rule; the only engine type is the
 * {@code RemoveReason} enum.
 */
class GearSetLifecycleTest {

    private static final String LOOK = "Night_Set_Look";
    private static final TierRef TWO_PIECES = new TierRef("Night_Set", 0);
    private static final TierRef FULL_ARMOR = new TierRef("Night_Set", 1);
    private static final Set<TierRef> SUITED = Set.of(TWO_PIECES, FULL_ARMOR);

    private final UUID player = UUID.randomUUID();

    @BeforeEach
    @AfterEach
    void emptyTheTable() {
        GearSetApplied.clearForTests();
    }

    @Nonnull
    private static GearSetApplied.Applied suitedUp() {
        return new GearSetApplied.Applied(SUITED, Set.of(LOOK), SUITED);
    }

    @Test
    void aLookClearedByDeathAndRespawnComesBackWithTheRowIntactAndNothingIsAnnounced() {
        GearSetApplied.put(player, suitedUp());
        GearSetApplied.Applied previous = GearSetApplied.get(player);
        assertNotNull(previous, "a death touches no row");

        // The respawn recompute: the same tiers hold, the entity lost every effect to the engine.
        Set<String> shown = GearSets.shownEffects(Set.of(LOOK), false);
        EffectChanges changes = GearSets.effectChanges(previous.effects(), shown, effect -> false);

        assertEquals(List.of(LOOK), changes.applies(), "the entity is asked, not the row, so the look goes back on");
        assertTrue(changes.removes().isEmpty(), "and nothing the set still wants is taken off");
        assertTrue(GearSets.announcements(previous, SUITED).isEmpty(), "no tier flipped, so no notice");
    }

    @Test
    void aLookAlreadyOnIsNeverPutOnTwice() {
        EffectChanges changes = GearSets.effectChanges(Set.of(LOOK), Set.of(LOOK), effect -> true);

        assertTrue(changes.applies().isEmpty());
        assertTrue(changes.removes().isEmpty());
    }

    @Test
    void aLookNoTierWantsComesOffWhetherOrNotTheEntityStillShowsIt() {
        EffectChanges changes = GearSets.effectChanges(Set.of(LOOK), Set.of(), effect -> false);

        assertEquals(List.of(LOOK), changes.removes(), "what the engine answers for comes off without asking");
        assertTrue(changes.applies().isEmpty());
    }

    @Test
    void aDeadPlayerShowsNoLookAndGetsItBackOnceAlive() {
        Set<String> whileDead = GearSets.shownEffects(Set.of(LOOK), true);
        EffectChanges dying = GearSets.effectChanges(Set.of(LOOK), whileDead, effect -> false);

        assertTrue(whileDead.isEmpty(), "the engine clears a corpse's effects, and the engine keeps it that way");
        assertTrue(dying.applies().isEmpty());

        EffectChanges respawned = GearSets.effectChanges(whileDead, GearSets.shownEffects(Set.of(LOOK), false),
                effect -> false);
        assertEquals(List.of(LOOK), respawned.applies());
    }

    @Test
    void aRecomputeQueuedBeforeTheDisconnectCannotOutliveTheSession() {
        GearSetApplied.put(player, suitedUp());
        GearSetApplied.forget(player);            // the disconnect event, fired before the entity leaves
        GearSetApplied.put(player, suitedUp());   // a recompute already queued on the world thread

        GearSets.onEntityRemoved(player, RemoveReason.REMOVE);   // the entity leaves its store

        assertNull(GearSetApplied.get(player), "so the next login is a hydrate again");
    }

    @Test
    void aWorldChangeKeepsTheRow() {
        GearSetApplied.put(player, suitedUp());

        GearSets.onEntityRemoved(player, RemoveReason.UNLOAD);   // the holder moves to the next world

        assertNotNull(GearSetApplied.get(player), "an unload is a world change, never the end of the session");
    }

    @Test
    void anyOtherRemovalEndsTheSessionAndANullPlayerIsHarmless() {
        GearSetApplied.put(player, suitedUp());

        GearSets.onEntityRemoved(null, RemoveReason.REMOVE);
        assertNotNull(GearSetApplied.get(player));

        GearSets.onEntityRemoved(player, RemoveReason.BUILDER_TOOLS_UNDO);
        assertNull(GearSetApplied.get(player));
    }
}
