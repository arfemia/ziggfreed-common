package com.ziggfreed.common.world.gather;

import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.usegate.UseGateBreakSystem;
import com.ziggfreed.common.util.SafeLog;

/**
 * Starts the {@code Break} gather shapes a player's own break starts ({@link GatherShapes#breakShapesFor}), while the
 * block is still there, and runs them in a world task once the engine has made the break ({@link
 * GatherShapes#runBreaks}). A hand harvest the engine makes as a pickup break (a ripe plant with no farming reset,
 * reported with no item) starts the {@code Harvest} shapes too ({@link GatherShapes#runHarvests}), as the farming
 * harvest does through {@link HarvestShapeSystem}. A break a shape makes ({@link ShapedBreaks#active}) starts
 * nothing, so a shape never sets off another. Ordered after the use-gate refusal, so a refused break arrives
 * cancelled and starts nothing.
 */
public final class GatherShapeSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private static final Set<Dependency<EntityStore>> DEPENDENCIES =
            Set.of(new SystemDependency<>(Order.AFTER, UseGateBreakSystem.class));

    public GatherShapeSystem() {
        super(BreakBlockEvent.class);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull BreakBlockEvent event) {
        try {
            GatherShapeConfig config = GatherShapeConfig.getInstance();
            if (event.isCancelled() || ShapedBreaks.active()
                    || (config.breakShapes().isEmpty() && config.harvestShapes().isEmpty())) {
                return;
            }
            BlockType type = event.getBlockType();
            String blockId = type == null ? null : type.getId();
            Vector3i target = event.getTargetBlock();
            World world = store.getExternalData().getWorld();
            if (blockId == null || blockId.isBlank() || "Empty".equals(blockId) || target == null || world == null) {
                return;
            }
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            ItemStack held = event.getItemInHand();
            Vector3i origin = new Vector3i(target.x, target.y, target.z);
            List<GatherShapeAsset> shapes = GatherShapes.breakShapesFor(store, ref, blockId, origin, held);
            String heldId = held == null || held.isEmpty() ? null : held.getItemId();
            if (!shapes.isEmpty()) {
                world.execute(() -> GatherShapes.runBreaks(store, ref, blockId, origin, heldId, shapes));
            }
            // A ripe plant picked by hand with no farming reset (a crop that is picked off, a flower) is taken by the
            // engine's pickup break, which fires this event with no item: it is a harvest, so harvest shapes run too.
            if (heldId == null && harvestable(type)) {
                List<GatherShapeAsset> harvests = GatherShapes.harvestShapesFor(store, ref, blockId, origin);
                if (!harvests.isEmpty()) {
                    world.execute(() -> GatherShapes.runHarvests(store, ref, blockId, origin, InteractionType.Use,
                            harvests));
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[gather] a break's gather shapes could not be asked: " + t.getMessage());
        }
    }

    /** Whether the block is one a hand harvest takes (it writes a harvest). */
    private static boolean harvestable(@Nonnull BlockType type) {
        BlockGathering gathering = type.getGathering();
        return gathering != null && gathering.isHarvestable();
    }
}
