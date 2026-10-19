package com.ziggfreed.common.effect.control;

import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.system.StoreSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.blackboard.Blackboard;
import com.hypixel.hytale.server.npc.blackboard.view.attitude.AttitudeView;
import com.hypixel.hytale.server.npc.systems.BlackboardSystems;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers {@link AggroAttitudes} on every world's {@code AttitudeView} as the world's store comes up, the way the
 * game's own reputation attitude registers ({@code ReputationAttitudeSystem}): after the blackboard that holds the
 * view is initialised, at {@link AggroAttitudes#PRIORITY}.
 */
public final class AggroAttitudeSystem extends StoreSystem<EntityStore> {

    private final Set<Dependency<EntityStore>> dependencies =
            Set.of(new SystemDependency<>(Order.AFTER, BlackboardSystems.InitSystem.class));

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void onSystemAddedToStore(@Nonnull Store<EntityStore> store) {
        try {
            AttitudeView view = store.getResource(Blackboard.getResourceType()).getView(AttitudeView.class, 0);
            view.registerProvider(AggroAttitudes.PRIORITY, AggroAttitudes::attitude);
        } catch (Throwable t) {
            SafeLog.warn("[control] the aggro attitude provider could not be registered on a world: a taunt there"
                    + " still points creatures at the taunter, but a suppress no longer makes them ignore anyone", t);
        }
    }

    @Override
    public void onSystemRemovedFromStore(@Nonnull Store<EntityStore> store) {
        // The view goes with the world's blackboard.
    }
}
