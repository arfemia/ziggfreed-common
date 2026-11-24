package com.ziggfreed.common.damage.pvp;

import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.interaction.event.InteractionChainStartEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The chain half of a disarm ({@link Disarm}): a player's interaction chain whose type the {@code ZigDisarm} tag of an
 * effect they wear names is refused as it starts, before its first tick, so the swing never happens and nothing it
 * would put on lands. First among the event's listeners, so no later one sees a chain that never ran.
 */
public final class DisarmChainSystem extends EntityEventSystem<EntityStore, InteractionChainStartEvent> {

    public DisarmChainSystem() {
        super(InteractionChainStartEvent.class);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return RootDependency.firstSet();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InteractionChainStartEvent event) {
        try {
            if (Disarm.refuses(store, chunk.getReferenceTo(index), event.getType())) {
                event.setCancelled(true);
            }
        } catch (Throwable t) {
            SafeLog.fine("[pvp] a disarm could not read a chain: " + t.getMessage());
        }
    }
}
