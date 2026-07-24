package com.ziggfreed.common.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Tests for {@link PlayerPuppetService}'s unit-JVM-safe surface: the pure spatial helpers, the
 * {@link PlayerPuppetService.PuppetSpawnRequest} builder's mutual-exclusivity/default contract,
 * and the try-guard "never throws, degrades to a no-op" contract on every engine-touching entry
 * point when handed a deliberately-broken (null) accessor - the same style
 * {@code cast.HitResolverTest} uses for {@code HitContext}'s accessor slot (a null
 * {@code Store}/{@code CommandBuffer} is a legitimate test double here since the methods under
 * test never dereference a non-null field before failing). {@link PlayerPuppetService#spawn}/
 * {@link PlayerPuppetService#despawn}/{@link PlayerPuppetService#playAnimation}/
 * {@link PlayerPuppetService#hideByScale}/{@link PlayerPuppetService#revealByScale}'s SUCCESS
 * paths touch live Store/Holder/component types and have no unit coverage, matching this
 * ecosystem's established precedent (e.g. {@code station.StationCustodyDisplayTest}).
 */
class PlayerPuppetServiceTest {

    // ==================== pure spatial helpers ====================

    @Test
    void offsetPosition_shiftsEachAxis() {
        assertArrayEquals(new double[] {10.5, 65.05, -3.3},
                PlayerPuppetService.offsetPosition(10.5, 64.5, -3.5, 0.0, 0.55, 0.2));
    }

    @Test
    void offsetPosition_zeroOffset_isAnchor() {
        assertArrayEquals(new double[] {1.0, 2.0, 3.0}, PlayerPuppetService.offsetPosition(1.0, 2.0, 3.0, 0, 0, 0));
    }

    @Test
    void yawRadiansFromDegrees_convertsCorrectly() {
        assertEquals(0f, PlayerPuppetService.yawRadiansFromDegrees(0.0), 1e-6f);
        assertEquals((float) Math.PI, PlayerPuppetService.yawRadiansFromDegrees(180.0), 1e-5f);
        assertEquals((float) (Math.PI / 2), PlayerPuppetService.yawRadiansFromDegrees(90.0), 1e-5f);
    }

    @Test
    void nearZeroScale_isSmallAndPositive() {
        float scale = PlayerPuppetService.nearZeroScale();
        assertTrue(scale > 0f);
        assertTrue(scale < 0.1f);
    }

    // ==================== PuppetSpawnRequest builder ====================

    @Test
    void builder_defaults_areEmptyHandedNoPreseedZeroYaw() {
        PlayerPuppetService.PuppetSpawnRequest req = PlayerPuppetService.PuppetSpawnRequest.builder().build();
        assertEquals(0f, req.yawRadians());
        assertFalse(req.mirrorHeldItem());
        assertNull(req.heldItemIdOverride());
        assertNull(req.initialAnimationSlot());
        assertNull(req.initialClipId());
    }

    @Test
    void builder_mirrorHeldItem_clearsHeldItemIdOverride() {
        PlayerPuppetService.PuppetSpawnRequest req = PlayerPuppetService.PuppetSpawnRequest.builder()
                .heldItemIdOverride("Tool_Hammer_Iron")
                .mirrorHeldItem(true)
                .build();
        assertTrue(req.mirrorHeldItem());
        assertNull(req.heldItemIdOverride(), "mirrorHeldItem(true) after an override clears the override");
    }

    @Test
    void builder_heldItemIdOverride_clearsMirrorHeldItem() {
        PlayerPuppetService.PuppetSpawnRequest req = PlayerPuppetService.PuppetSpawnRequest.builder()
                .mirrorHeldItem(true)
                .heldItemIdOverride("Tool_Hammer_Iron")
                .build();
        assertFalse(req.mirrorHeldItem(), "an override after mirrorHeldItem(true) clears the mirror flag");
        assertEquals("Tool_Hammer_Iron", req.heldItemIdOverride());
    }

    @Test
    void builder_initialAnimation_roundTripsBothFields() {
        PlayerPuppetService.PuppetSpawnRequest req = PlayerPuppetService.PuppetSpawnRequest.builder()
                .initialAnimation(AnimationSlot.Action, "Chop")
                .build();
        assertEquals(AnimationSlot.Action, req.initialAnimationSlot());
        assertEquals("Chop", req.initialClipId());
    }

    @Test
    void builder_yawRadiansAndPosition_roundTrip() {
        org.joml.Vector3d pos = new org.joml.Vector3d(1, 2, 3);
        PlayerPuppetService.PuppetSpawnRequest req = PlayerPuppetService.PuppetSpawnRequest.builder()
                .position(pos)
                .yawRadians(1.5f)
                .build();
        assertEquals(pos, req.position());
        assertEquals(1.5f, req.yawRadians());
    }

    // ==================== spawn-path skin isolation (round8 skin-wipe guard) ====================

    @Test
    void playerSkinCopyCtor_isIndependentOfSource() {
        // Guards the assumption PlayerPuppetService.spawn relies on: it clones the source player's
        // live PlayerSkin via `new PlayerSkin(source)` so the puppet NEVER shares the player's skin
        // object. If a future engine change made the copy shallow (a shared mutable sub-object),
        // mutating the puppet's copy would corrupt the real player's look. This asserts the copy is
        // a genuine, independent snapshot - the boundary the puppet spawn depends on staying safe.
        // (The round8 wipe itself was NOT a sharing bug - the copy was already independent, proven
        // here - but a network-refresh omission, fixed in hideByScale/revealByScale.)
        PlayerSkin source = new PlayerSkin();
        source.haircut = "Hair_Long.Brown";
        source.overtop = "Shirt_Tunic.Green";
        source.face = "Face_A";

        PlayerSkin copy = new PlayerSkin(source);
        assertEquals("Hair_Long.Brown", copy.haircut, "copy carries the source's cosmetic slots");
        assertEquals("Shirt_Tunic.Green", copy.overtop);

        // Mutating the puppet's copy must leave the source player's skin untouched.
        copy.haircut = null;
        copy.overtop = null;
        copy.face = null;
        assertEquals("Hair_Long.Brown", source.haircut, "the puppet copy must not share state with the player's skin");
        assertEquals("Shirt_Tunic.Green", source.overtop);
        assertEquals("Face_A", source.face);
    }

    // ==================== held-item mirror refresh (dirty-gate decision) ====================

    @Test
    void heldItemChanged_sameId_isFalse() {
        assertFalse(PlayerPuppetService.heldItemChanged("Tool_Hammer_Iron", "Tool_Hammer_Iron"));
    }

    @Test
    void heldItemChanged_differentId_isTrue() {
        assertTrue(PlayerPuppetService.heldItemChanged("Tool_Hammer_Iron", "Tool_Hammer_Crude"));
    }

    @Test
    void heldItemChanged_bothNull_isFalse() {
        assertFalse(PlayerPuppetService.heldItemChanged(null, null));
    }

    @Test
    void heldItemChanged_nullToItem_isTrue() {
        assertTrue(PlayerPuppetService.heldItemChanged(null, "Tool_Hammer_Iron"));
    }

    @Test
    void heldItemChanged_itemToNull_isTrue() {
        assertTrue(PlayerPuppetService.heldItemChanged("Tool_Hammer_Iron", null));
    }

    @Test
    void heldItemChanged_blankTreatedAsNull_isFalse() {
        assertFalse(PlayerPuppetService.heldItemChanged(null, ""), "blank is normalized to null - not a change");
        assertFalse(PlayerPuppetService.heldItemChanged("", null), "blank is normalized to null - not a change");
    }

    @Test
    void updateHeldItem_withNullPuppetRef_returnsLastMirroredUnchanged() {
        ComponentAccessor<EntityStore> nullAccessor = null;
        assertEquals("Tool_Hammer_Iron", assertDoesNotThrow(
                () -> PlayerPuppetService.updateHeldItem(nullAccessor, null, "Tool_Hammer_Iron", "Tool_Hammer_Crude")));
    }

    @Test
    void updateHeldItem_noChange_neverTouchesAccessor() {
        // A null accessor would NPE if updateHeldItem attempted any component read/write on a
        // no-change call - reaching this without throwing proves the dirty-gate short-circuits
        // BEFORE any accessor touch, even with a (deliberately invalid) null puppetRef.
        ComponentAccessor<EntityStore> nullAccessor = null;
        assertEquals("Tool_Hammer_Iron", assertDoesNotThrow(
                () -> PlayerPuppetService.updateHeldItem(nullAccessor, null, "Tool_Hammer_Iron", "Tool_Hammer_Iron")));
    }

    // ==================== try-guard contracts (never throw, degrade to a no-op) ====================

    @Test
    void spawn_withNullAccessor_returnsNullWithoutThrowing() {
        Ref<EntityStore> nullRef = null;
        PlayerPuppetService.PuppetSpawnRequest req = PlayerPuppetService.PuppetSpawnRequest.builder()
                .sourceRef(nullRef)
                .position(new org.joml.Vector3d(0, 0, 0))
                .build();
        ComponentAccessor<EntityStore> nullAccessor = null;
        assertNull(assertDoesNotThrow(() -> PlayerPuppetService.spawn(nullAccessor, req)));
    }

    @Test
    void despawn_withNullRef_isNoOpOnBothOverloads() {
        Store<EntityStore> store = null;
        CommandBuffer<EntityStore> commandBuffer = null;
        assertDoesNotThrow(() -> PlayerPuppetService.despawn(null, store));
        assertDoesNotThrow(() -> PlayerPuppetService.despawn(null, commandBuffer));
    }

    @Test
    void despawn_withNullCommandBuffer_isNoOp() {
        assertDoesNotThrow(() -> PlayerPuppetService.despawn(null, (CommandBuffer<EntityStore>) null));
    }

    @Test
    void playAnimation_withNullPuppetRef_isNoOp() {
        ComponentAccessor<EntityStore> nullAccessor = null;
        assertDoesNotThrow(() -> PlayerPuppetService.playAnimation(nullAccessor, null, AnimationSlot.Action, null, "Chop", true));
    }

    @Test
    void hideByScale_withNullAccessor_returnsNullWithoutThrowing() {
        ComponentAccessor<EntityStore> nullAccessor = null;
        Ref<EntityStore> nullRef = null;
        assertNull(assertDoesNotThrow(() -> PlayerPuppetService.hideByScale(nullAccessor, nullRef)));
    }

    @Test
    void revealByScale_withNullRef_isNoOp() {
        ComponentAccessor<EntityStore> nullAccessor = null;
        assertDoesNotThrow(() -> PlayerPuppetService.revealByScale(nullAccessor, null, 1.0f));
    }
}
