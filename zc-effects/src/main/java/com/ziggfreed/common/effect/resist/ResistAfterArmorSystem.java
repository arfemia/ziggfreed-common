package com.ziggfreed.common.effect.resist;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The after-armor step ({@link ResistAfterArmor}): in the Filter damage group, after the engine's own armor
 * step, every hit gets its armor penetration, a rolled landing's share and the 1-point minimum while this
 * library's resistance is in the sum. A consumer that hands a hit its penetration later in the same group
 * (from its own damage mutator) orders itself {@code BEFORE} this class; one that reads the hit's final
 * amount orders itself after it. Registered once, by {@link ResistBootstrap}.
 */
public final class ResistAfterArmorSystem extends DamageEventSystem {

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            // DEPRECATION-KEPT: the engine's armor step is the only resistance applier in both lines (its note names no successor); this step rescales what it left.
            new SystemDependency<>(Order.AFTER, DamageSystems.ArmorDamageReduction.class));

    @Nonnull
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
        try {
            if (damage.isCancelled() || damage.getAmount() <= 0f) {
                return;
            }
            ResistAfterArmor.Outcome outcome = ResistAfterArmor.evaluate(commandBuffer, chunk.getReferenceTo(index), damage);
            float landed = (float) outcome.landed();
            if (landed != damage.getAmount()) {
                damage.setAmount(landed);
            }
        } catch (Throwable t) {
            SafeLog.fine("[resist] after-armor step failed: " + t.getMessage());
        }
    }
}
