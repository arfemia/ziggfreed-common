package com.ziggfreed.common.instance.claims;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Taking a spoils chest down ({@link SpoilsChests}): {@link Upkeep} looks at every standing chest once a second and
 * removes one whose lifetime is over, whose players have taken everything, or that this session does not know;
 * {@link Sweep} asks the same of a chest the moment it loads, so one a restart left on disk goes before anyone can
 * use it. Both read the chest's {@link SpoilsChest} mark and remove through the system's command buffer. Built only
 * once the mark is registered, so {@link SpoilsChest#TYPE} is set here. Never throws.
 */
public final class SpoilsChestSystems {

    private SpoilsChestSystems() {
    }

    /**
     * Register both on the entity store, at setup, only once the chest's mark is registered (they query by it).
     */
    public static void register(@Nonnull ComponentRegistryProxy<EntityStore> registry) {
        if (SpoilsChest.TYPE != null) {
            registry.registerSystem(new Upkeep());
            registry.registerSystem(new Sweep());
        } else {
            SafeLog.warn(SpoilsChests.LOG + "the chests' mark is not registered, so no chest is ever taken down; "
                    + "chests are not stood either");
        }
    }

    /** The once-a-second look at every standing chest. */
    public static final class Upkeep extends EntityTickingSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return SpoilsChest.TYPE;
        }

        /** It reads the Claims store and the spoils noted, so it runs on the world thread alone. */
        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            return false;
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                SpoilsChest mark = chunk.getComponent(index, SpoilsChest.TYPE);
                long now = System.currentTimeMillis();
                if (mark == null || !mark.due(now, SpoilsChests.CHECK_EVERY_MS)) {
                    return;
                }
                takeDownUnlessKept(chunk.getReferenceTo(index), mark,
                        uuidOf(chunk.getComponent(index, UUIDComponent.getComponentType())), now, commandBuffer);
            } catch (Throwable t) {
                SafeLog.fine(SpoilsChests.LOG + "a chest's upkeep failed: " + t.getMessage());
            }
        }
    }

    /** A chest loading: kept only when this session stood it and it is still owed. */
    public static final class Sweep extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return SpoilsChest.TYPE;
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            if (reason != AddReason.LOAD) {
                return;
            }
            try {
                SpoilsChest mark = store.getComponent(ref, SpoilsChest.TYPE);
                if (mark != null) {
                    takeDownUnlessKept(ref, mark, uuidOf(store.getComponent(ref, UUIDComponent.getComponentType())),
                            System.currentTimeMillis(), commandBuffer);
                }
            } catch (Throwable t) {
                SafeLog.fine(SpoilsChests.LOG + "a loading chest could not be read: " + t.getMessage());
            }
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            // A chest leaving memory (a section asleep, a closing world) is still owed; its spoils stay noted.
        }
    }

    /** Remove {@code ref} unless {@link SpoilsChests#verdict} keeps it. */
    private static void takeDownUnlessKept(@Nonnull Ref<EntityStore> ref, @Nonnull SpoilsChest mark, @Nullable UUID entity,
            long nowMs, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        SpoilsChests.Verdict verdict = SpoilsChests.verdict(mark, entity, nowMs);
        if (verdict == SpoilsChests.Verdict.KEEP) {
            return;
        }
        commandBuffer.removeEntity(ref, RemoveReason.REMOVE);
        SpoilsChests.takenDown(mark.fight(), entity, verdict);
    }

    @Nullable
    private static UUID uuidOf(@Nullable UUIDComponent component) {
        return component == null ? null : component.getUuid();
    }
}
