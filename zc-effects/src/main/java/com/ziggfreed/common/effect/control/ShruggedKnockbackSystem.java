package com.ziggfreed.common.effect.control;

import java.util.Set;

import javax.annotation.Nonnull;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.condition.BossRules;
import com.ziggfreed.common.stats.condition.ControlKind;
import com.ziggfreed.common.util.SafeLog;

/**
 * A creature whose rules shrug a Push (zc-entity's {@code BossRules}: a phase or a window naming it under
 * {@code ZigControlShrugs}) is moved by no knockback, the game's own included: a weapon's swing, a bomb, any hit's
 * knockback and a step that writes one. Right before the engine's own knockback step
 * ({@link KnockbackSystems.ApplyKnockback}, a creature's), the knockback waiting on such a creature is emptied to a
 * zero push that adds nothing, so its own motion runs on untouched, and the engine takes it off as it always does. A
 * player is never asked. Free while no loaded rule shrugs a Push.
 */
public final class ShruggedKnockbackSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies =
            Set.of(new SystemDependency<>(Order.BEFORE, KnockbackSystems.ApplyKnockback.class));

    @Nonnull
    private final Query<EntityStore> query =
            Query.and(KnockbackComponent.getComponentType(), Query.not(Player.getComponentType()));

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            if (!BossRules.mayShrug(ControlKind.PUSH)) {
                return;
            }
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            if (!BossRules.shrugs(commandBuffer, ref, ControlKind.PUSH)) {
                return;
            }
            KnockbackComponent knockback = chunk.getComponent(index, KnockbackComponent.getComponentType());
            if (knockback == null) {
                return;
            }
            // An Add of nothing: a Set of nothing would stop the creature dead for the tick.
            knockback.setVelocity(new Vector3d());
            knockback.setVelocityType(ChangeVelocityType.Add);
            knockback.setDuration(0f);
        } catch (Throwable t) {
            SafeLog.fine("[control] could not empty a knockback on a creature that shrugs it: " + t.getMessage());
        }
    }
}
