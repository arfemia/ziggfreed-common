package com.ziggfreed.common.entity.performer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.performer.PerformerLook.LookSource;
import com.ziggfreed.common.entity.performer.PerformerLook.SkinSource;

/**
 * The performer VALUE types + the {@link StationPerformer}/{@link WalkHandle} contract, exercised
 * with contract-level FAKES (no live server). Covers the pure value semantics the engine leg codes
 * against and the {@link WalkHandle#isDone()} default.
 */
class PerformerContractTest {

    // ==================== PerformerKind ====================

    @Test
    void performerKind_codeRoundTrips() {
        assertEquals("BareHolder", PerformerKind.HOLDER.code());
        assertEquals("NpcRole", PerformerKind.NPC_ROLE.code());
        assertEquals(PerformerKind.HOLDER, PerformerKind.fromCode("BareHolder"));
        assertEquals(PerformerKind.NPC_ROLE, PerformerKind.fromCode("NpcRole"));
    }

    @Test
    void performerKind_unknownOrNullDefaultsToHolder() {
        assertEquals(PerformerKind.HOLDER, PerformerKind.fromCode(null));
        assertEquals(PerformerKind.HOLDER, PerformerKind.fromCode("SomeFutureKind"));
    }

    // ==================== PropSpec ====================

    @Test
    void propSpec_emptyDetection() {
        assertTrue(PropSpec.none().isEmpty());
        assertTrue(PropSpec.of(null).isEmpty());
        assertTrue(PropSpec.of("  ").isEmpty());
        assertFalse(PropSpec.of("Tool_Hammer_Crude").isEmpty());
        assertEquals("Tool_Hammer_Crude", PropSpec.of("Tool_Hammer_Crude").itemId());
    }

    // ==================== ClipSpec ====================

    @Test
    void clipSpec_defaultsSelfVisibleNoItemSet() {
        ClipSpec c = ClipSpec.of(AnimationSlot.Emote, "RPG_Emote_Hammer");
        assertEquals(AnimationSlot.Emote, c.slot());
        assertEquals("RPG_Emote_Hammer", c.clipId());
        assertNull(c.itemAnimationsId());
        assertTrue(c.sendToSelf());
    }

    // ==================== PerformerLook ====================

    @Test
    void performerLook_defaultsToTransientPlayerClone() {
        PerformerLook look = PerformerLook.playerClone();
        assertEquals(LookSource.PLAYER_CLONE, look.source());
        assertEquals(SkinSource.PLAYER_CLONE, look.skinSource());
        assertFalse(look.persist());
        assertEquals(PerformerKind.HOLDER, look.kind());
    }

    @Test
    void performerLook_npcRoleRoutesToNpcKind() {
        PerformerLook look = PerformerLook.builder()
                .source(LookSource.NPC_ROLE)
                .roleId("RPG_Performer_Worker")
                .skinSource(SkinSource.ROLE_DEFAULT)
                .speedMps(4.0)
                .persist(true)
                .build();
        assertEquals(PerformerKind.NPC_ROLE, look.kind());
        assertEquals("RPG_Performer_Worker", look.roleId());
        assertEquals(4.0, look.speedMps(), 1e-9);
        assertTrue(look.persist());
    }

    @Test
    void performerLook_modelSourceStaysHolderKind() {
        PerformerLook look = PerformerLook.builder()
                .source(LookSource.MODEL)
                .modelId("Apprentice_Golem")
                .fallbackModelId("Mannequin")
                .build();
        assertEquals(PerformerKind.HOLDER, look.kind());
        assertEquals("Apprentice_Golem", look.modelId());
        assertEquals("Mannequin", look.fallbackModelId());
    }

    // ==================== PerformerSpawnCtx (the spawn-accessor seam) ====================

    @Test
    void performerSpawnCtx_accessorRequiredStoreNullableByDefault() {
        ComponentAccessor<EntityStore> accessor = noopAccessor();
        UUID owner = UUID.randomUUID();
        PerformerSpawnCtx ctx = PerformerSpawnCtx.builder()
                .accessor(accessor)
                .ownerUuid(owner)
                .stationKey("world:1:2:3")
                .position(new Vector3d(0.5, 64.0, 0.5))
                .yawRadians(1.25f)
                .look(PerformerLook.playerClone())
                .build();

        // The REQUIRED spawn accessor round-trips by identity (a Store OR a CommandBuffer both fit).
        assertSame(accessor, ctx.accessor());
        // The concrete live store is a SEPARATE, nullable NPC-synchronous-spawn handle - unset here
        // (the lock-held engage-time shape: only an accessor, so the NPC backend defers its spawn).
        assertNull(ctx.store());
        assertNull(ctx.world());
        assertEquals(owner, ctx.ownerUuid());
        assertEquals("world:1:2:3", ctx.stationKey());
        assertEquals(0.5, ctx.position().x, 1e-9);
        assertEquals(1.25f, ctx.yawRadians(), 1e-6f);
        assertEquals(LookSource.PLAYER_CLONE, ctx.look().source());
    }

    /**
     * A fixture {@link ComponentAccessor} whose IDENTITY is all a value test needs - no method on it
     * is ever invoked (any call throws), so a bare {@link Proxy} avoids stubbing the ~18-method
     * interface. Stands in for the {@code CommandBuffer}/{@code Store} a real caller passes.
     */
    @SuppressWarnings("unchecked")
    private static ComponentAccessor<EntityStore> noopAccessor() {
        return (ComponentAccessor<EntityStore>) Proxy.newProxyInstance(
                ComponentAccessor.class.getClassLoader(),
                new Class<?>[] {ComponentAccessor.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("fixture accessor: " + method.getName());
                });
    }

    // ==================== WalkHandle.isDone default ====================

    @Test
    void walkHandle_isDoneDefault() {
        assertFalse(new FakeWalkHandle(WalkHandle.State.WALKING).isDone());
        assertTrue(new FakeWalkHandle(WalkHandle.State.ARRIVED).isDone());
        assertTrue(new FakeWalkHandle(WalkHandle.State.STUCK).isDone());
        assertTrue(new FakeWalkHandle(WalkHandle.State.FAILED).isDone());
    }

    // ==================== a contract-level fake performer ====================

    @Test
    void fakePerformer_recordsDrivenSequence() {
        FakePerformer p = new FakePerformer();
        assertFalse(p.isAlive());
        assertNull(p.ref());

        p.spawn(null);
        assertTrue(p.isAlive());
        p.setProp(PropSpec.of("Tool_Hammer_Crude"));
        p.playClip(ClipSpec.of(AnimationSlot.Emote, "RPG_Emote_Hammer"));
        WalkHandle h = p.walkTo(new Vector3d(1, 64, 2), 2.5);
        assertSame(WalkHandle.State.ARRIVED, h.poll(50.0));
        p.despawn();
        assertFalse(p.isAlive());

        assertEquals(List.of("spawn", "setProp:Tool_Hammer_Crude", "playClip:RPG_Emote_Hammer",
                "walkTo:1.0,64.0,2.0@2.5", "despawn"), p.calls);
    }

    /** A minimal recording {@link StationPerformer} fake - the shape a consumer/driver codes against. */
    private static final class FakePerformer implements StationPerformer {
        final List<String> calls = new ArrayList<>();
        private boolean alive;

        @Override
        public void spawn(@Nullable PerformerSpawnCtx ctx) {
            calls.add("spawn");
            alive = true;
        }

        @Override
        public void despawn() {
            calls.add("despawn");
            alive = false;
        }

        @Override
        public void presentAt(@Nonnull Vector3d pos, float yaw) {
            calls.add("presentAt");
        }

        @Override
        @Nonnull
        public WalkHandle walkTo(@Nonnull Vector3d target, double speedMps) {
            calls.add("walkTo:" + target.x + "," + target.y + "," + target.z + "@" + speedMps);
            return new FakeWalkHandle(WalkHandle.State.ARRIVED);
        }

        @Override
        public void setProp(@Nonnull PropSpec prop) {
            calls.add("setProp:" + prop.itemId());
        }

        @Override
        public void playClip(@Nonnull ClipSpec clip) {
            calls.add("playClip:" + clip.clipId());
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        @Nullable
        public Ref<EntityStore> ref() {
            return null;
        }
    }

    private static final class FakeWalkHandle implements WalkHandle {
        @Nonnull
        private State state;

        FakeWalkHandle(@Nonnull State state) {
            this.state = state;
        }

        @Override
        @Nonnull
        public State poll(double dtMs) {
            return state;
        }

        @Override
        @Nonnull
        public State state() {
            return state;
        }

        @Override
        public void cancel() {
            state = State.FAILED;
        }
    }
}
