package com.ziggfreed.common.instance.claims;

import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DropItemEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Taking a spoils chest down ({@link SpoilsChests}): {@link Upkeep} looks at every standing chest once a second and
 * removes one whose lifetime is over, whose players have taken everything, or that this session does not know;
 * {@link Sweep} asks the same of a chest the moment it loads, so one a restart left on disk goes before anyone can
 * use it. Both read the chest's {@link SpoilsChest} mark and remove through the system's command buffer. Built only
 * once the mark is registered, so {@link SpoilsChest#TYPE} is set here. {@link NoDrop} keeps an open spoils
 * window's items off the ground. Never throws.
 */
public final class SpoilsChestSystems {

    private SpoilsChestSystems() {
    }

    /**
     * Register the three on the entity store, at setup: the upkeep and the sweep only once the chest's mark is
     * registered (they query by it), the drop guard always.
     */
    public static void register(@Nonnull ComponentRegistryProxy<EntityStore> registry) {
        if (SpoilsChest.TYPE != null) {
            registry.registerSystem(new Upkeep());
            registry.registerSystem(new Sweep());
        } else {
            SafeLog.warn(SpoilsChests.LOG + "the chests' mark is not registered, so no chest is ever taken down; "
                    + "chests are not stood either");
        }
        registry.registerSystem(new NoDrop());
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

    /**
     * A drop to the ground from an open spoils window ({@link SpoilsWindow}) is refused, so its items only ever go
     * into the bag and never lie owner-less: the drop request names the window's id as its section. The window is
     * sent again, since the client already took the stack out of it. Ordered last, so no earlier listener can let
     * it through again.
     */
    public static final class NoDrop extends EntityEventSystem<EntityStore, DropItemEvent.PlayerRequest> {

        public NoDrop() {
            super(DropItemEvent.PlayerRequest.class);
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return RootDependency.lastSet();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull DropItemEvent.PlayerRequest event) {
            try {
                PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
                Integer window = SpoilsWindow.windowIdOf(playerRef == null ? null : playerRef.getUuid());
                if (window == null || window != event.getInventorySectionId()) {
                    return;
                }
                event.setCancelled(true);
                Player player = chunk.getComponent(index, Player.getComponentType());
                if (player != null) {
                    player.getWindowManager().markWindowChanged(window);
                }
            } catch (Throwable t) {
                SafeLog.fine(SpoilsChests.LOG + "a drop from a spoils window could not be read: " + t.getMessage());
            }
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
