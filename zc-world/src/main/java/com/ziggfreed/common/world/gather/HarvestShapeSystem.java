package com.ziggfreed.common.world.gather;

import java.util.List;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Starts the {@code Harvest} gather shapes a player's use of a farming block starts ({@link
 * GatherShapes#harvestShapesFor}) and runs them in a world task once the engine has given the harvest ({@link
 * GatherShapes#runHarvests}): a use that harvested nothing leaves the block as it was, and starts nothing. The further
 * harvests go through the engine's own harvest call, each once the engine's use event for it ({@code
 * UseBlockEvent.Pre}, fired by the shape as the engine fires it) goes uncancelled; the shape fires no {@code Post}, so a
 * shape never sets off another.
 */
public final class HarvestShapeSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Post> {

    public HarvestShapeSystem() {
        super(UseBlockEvent.Post.class);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull UseBlockEvent.Post event) {
        try {
            if (GatherShapeConfig.getInstance().harvestShapes().isEmpty()) {
                return;
            }
            BlockType type = event.getBlockType();
            Vector3i target = event.getTargetBlock();
            if (type == null || type.getFarming() == null || target == null) {
                return;
            }
            String blockId = type.getId();
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            Vector3i origin = new Vector3i(target.x, target.y, target.z);
            List<GatherShapeAsset> shapes = GatherShapes.harvestShapesFor(store, ref, blockId, origin);
            World world = store.getExternalData().getWorld();
            if (shapes.isEmpty() || world == null) {
                return;
            }
            InteractionType use = event.getInteractionType() == null ? InteractionType.Use
                    : event.getInteractionType();
            world.execute(() -> GatherShapes.runHarvests(store, ref, blockId, origin, use, shapes));
        } catch (Throwable t) {
            SafeLog.fine("[gather] a harvest's gather shapes could not be asked: " + t.getMessage());
        }
    }
}
