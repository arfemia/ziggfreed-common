package com.ziggfreed.common.usegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * Refuses a block break a use gate refuses: the block's own gates and the held item's ({@link UseGates}). A
 * refused break is CANCELLED, so it never reaches the library's break producer, which declares itself after this
 * system (zc-objectives' {@code ZigBlockBreakProducer}): a refused break credits no quest, pays no XP and rolls
 * no bonus drop. The player is told why through {@link UseGates#announce}.
 */
public final class UseGateBreakSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    /** The engine's id for "there is nothing here", which is not a block anybody broke. */
    private static final String EMPTY_BLOCK_ID = "Empty";

    public UseGateBreakSystem() {
        super(BreakBlockEvent.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void handle(final int index, @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull final Store<EntityStore> store, @Nonnull final CommandBuffer<EntityStore> commandBuffer,
            @Nonnull final BreakBlockEvent event) {
        try {
            if (event.isCancelled() || UseGates.nothingToAsk()) {
                return;
            }
            String blockId = event.getBlockType() == null ? null : event.getBlockType().getId();
            if (blockId == null || blockId.isBlank() || EMPTY_BLOCK_ID.equals(blockId)) {
                return;
            }
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            UseGateRefusal refusal = UseGates.breakRefusal(store, ref, PlayerAccess.activeHotbarItem(store, ref),
                    blockId);
            if (refusal == null) {
                return;
            }
            event.setCancelled(true);
            UseGates.announce(store, ref, refusal);
        } catch (Throwable t) {
            SafeLog.warn("[usegate] a block break could not be asked: " + t.getMessage());
        }
    }
}
