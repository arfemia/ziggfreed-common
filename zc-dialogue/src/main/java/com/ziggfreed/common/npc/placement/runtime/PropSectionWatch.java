package com.ziggfreed.common.npc.placement.runtime;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.NonTicking;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.TickingSections.SectionPos;

/**
 * Sweeps a world again when a chunk section that should hold a placement's prop loads or wakes, so a prop
 * is drawn whenever a player can see it ({@link PlacementProps}).
 *
 * <p>A prop is never saved: a section going to sleep drops it, and a section unloaded and loaded again
 * comes back without it. The placement sweep is latched per world, so nothing else would draw it again
 * until the next unrelated trigger. Two moments make a section somewhere a prop can stand again: it loads
 * (a column coming into memory, possibly already ticking) and it wakes (its {@code NonTicking} marker
 * removed, the moment the engine's own {@code EntitySectionLoadingSystem} brings its parked entities back).
 * Either one, for a section holding a prop that is wanted and not standing, clears the world's latch and
 * asks for a sweep; any other section costs one map lookup.
 *
 * <p>Runs inside the chunk store's processing window, so it only reads the section's position and defers:
 * the sweep runs on the world's task queue ({@code NpcPlacementReconciler.requestSweep}), never here. Never
 * throws.
 */
public final class PropSectionWatch {

    private PropSectionWatch() {
    }

    /** Register both halves on the chunk store. Call once, at setup. */
    public static void register(@Nonnull ComponentRegistryProxy<ChunkStore> registry) {
        registry.registerSystem(new Loaded());
        registry.registerSystem(new Woken());
    }

    /** A section coming into memory. */
    static final class Loaded extends RefSystem<ChunkStore> {

        @Nonnull
        @Override
        public Query<ChunkStore> getQuery() {
            return ChunkSection.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
            notice(ref, store);
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<ChunkStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
            // A section leaving memory takes its props with it; the book already reads them as not standing.
        }
    }

    /** A section waking: its {@code NonTicking} marker removed. */
    static final class Woken extends RefChangeSystem<ChunkStore, NonTicking<ChunkStore>> {

        @Nonnull
        @Override
        public Query<ChunkStore> getQuery() {
            return ChunkSection.getComponentType();
        }

        @Nonnull
        @Override
        public ComponentType<ChunkStore, NonTicking<ChunkStore>> componentType() {
            return ChunkStore.REGISTRY.getNonTickingComponentType();
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<ChunkStore> ref, @Nonnull NonTicking<ChunkStore> component,
                @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
            // Going to sleep: the section drops its props, which the book reads off their refs.
        }

        @Override
        public void onComponentSet(@Nonnull Ref<ChunkStore> ref, NonTicking<ChunkStore> oldComponent,
                @Nonnull NonTicking<ChunkStore> newComponent, @Nonnull Store<ChunkStore> store,
                @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        }

        @Override
        public void onComponentRemoved(@Nonnull Ref<ChunkStore> ref, @Nonnull NonTicking<ChunkStore> component,
                @Nonnull Store<ChunkStore> store, @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
            notice(ref, store);
        }
    }

    /** Ask for a sweep when this section holds a prop that is wanted and not standing. */
    private static void notice(@Nonnull Ref<ChunkStore> ref, @Nonnull Store<ChunkStore> store) {
        try {
            ChunkSection section = store.getComponent(ref, ChunkSection.getComponentType());
            if (section == null) {
                return;
            }
            World world = store.getExternalData().getWorld();
            if (world == null) {
                return;
            }
            SectionPos at = new SectionPos(section.getX(), section.getY(), section.getZ());
            if (!PlacementProps.wantsSection(NpcPlacementService.worldName(world), at)) {
                return;
            }
            NpcPlacementReconciler.clearDebounce(world);
            NpcPlacementReconciler.requestSweep(world, world.getEntityStore().getStore());
        } catch (Throwable t) {
            SafeLog.fine("[placement] a prop's section watch failed: " + t.getMessage());
        }
    }
}
