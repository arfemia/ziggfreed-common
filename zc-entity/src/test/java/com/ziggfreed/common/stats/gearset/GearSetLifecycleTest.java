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
 * on a corpse), the entity leaving its store (a disconnect and a world change both leave with
 * {@code UNLOAD}, and every removal forgets the row, so a recompute queued before the disconnect
 * cannot leave one behind), and the hydrate after it (a look the player's saved record names comes
 * off even when no current set names it). Pure: the effect rule, the table, the removal rule and
 * the hydrate's answer; the only engine type is the {@code RemoveReason} enum.
 */
class GearSetLifecycleTest {

    private static final String LOOK = "Night_Set_Look";
    private static final String DELETED_LOOK = "Retired_Set_Look";
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
        // The disconnect event fires first and evicts nothing; a recompute already queued on the
        // world thread writes the row again.
        GearSetApplied.put(player, suitedUp());

        // Then the engine removes the player: PlayerRef.removeFromStore, with UNLOAD.
        GearSets.onEntityRemoved(player, RemoveReason.UNLOAD);

        assertNull(GearSetApplied.get(player), "so the next login is a hydrate again");
    }

    @Test
    void aWorldChangeForgetsTheRowAndTheNextWorldsFirstRecomputeIsAQuietHydrate() {
        GearSetApplied.put(player, suitedUp());

        GearSets.onEntityRemoved(player, RemoveReason.UNLOAD);   // the holder moves to the next world
        GearSetApplied.Applied previous = GearSetApplied.get(player);
        assertNull(previous, "an unload forgets the row, a world change and a disconnect alike");

        // The next world's first recompute: the look travelled with the entity and the set still holds.
        Set<String> answered = GearSets.hydrateAnswersFor(Set.of(LOOK), Set.of(LOOK));
        EffectChanges changes = GearSets.effectChanges(answered, GearSets.shownEffects(Set.of(LOOK), false),
                effect -> true);
        assertTrue(changes.removes().isEmpty(), "the look the set still wants stays on");
        assertTrue(changes.applies().isEmpty(), "and the entity already has it");
        assertTrue(GearSets.announcements(previous, SUITED).isEmpty(), "no tier flipped, so no notice");
    }

    @Test
    void everyRemovalForgetsTheRowAndANullPlayerIsHarmless() {
        GearSets.onEntityRemoved(null, RemoveReason.UNLOAD);

        for (RemoveReason reason : RemoveReason.values()) {
            GearSetApplied.put(player, suitedUp());
            GearSets.onEntityRemoved(player, reason);
            assertNull(GearSetApplied.get(player), "forgotten on " + reason);
        }
    }

    @Test
    void aLookRecordedForASetDeletedSinceComesOffAtTheHydrateWithNoNotice() {
        // Night_Set is still folded; Retired_Set's file was deleted while its wearer was offline.
        GearSetIndex index = GearSetIndex.of(List.of(GearSetAsset.of("Night_Set", null, true,
                new String[] {"Night_Hood", "Night_Blade"},
                GearSetAsset.Tier.of(null, 1, null, null, null, LOOK, null))));
        Set<String> recorded = Set.of(DELETED_LOOK);

        Set<String> answered = GearSets.hydrateAnswersFor(index.allEffectIds(), recorded);
        EffectChanges changes = GearSets.effectChanges(answered, GearSets.shownEffects(Set.of(), false),
                effect -> true);

        assertEquals(List.of(LOOK, DELETED_LOOK), List.copyOf(answered),
                "the fold's looks first, then what the saved record adds");
        assertEquals(List.of(LOOK, DELETED_LOOK), changes.removes(),
                "a look no current set names still comes off, because the record says the engine put it on");
        assertTrue(changes.applies().isEmpty());
        assertTrue(GearSets.announcements(null, List.of()).isEmpty(), "a hydrate announces nothing");
    }

    @Test
    void theHydrateAnswersForEachLookOnceWhateverBothHalvesRepeat() {
        assertEquals(List.of(LOOK, DELETED_LOOK),
                List.copyOf(GearSets.hydrateAnswersFor(List.of(LOOK), List.of(DELETED_LOOK, LOOK))));
        assertTrue(GearSets.hydrateAnswersFor(List.of(), List.of()).isEmpty());
    }
}
