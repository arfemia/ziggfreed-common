package com.ziggfreed.common.instance.dungeon.omen;

import java.util.List;

import javax.annotation.Nonnull;

import org.joml.Vector3d;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.ziggfreed.common.instance.dungeon.CopyRouter;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.util.SafeLog;

/**
 * A run's omens on its creatures: each creature that comes into being in a dungeon copy, in the area of a run whose
 * omens carry {@code Mobs.Effects}, gets those effects ({@link RunOmens#putOn}) on a world task of its own, once the
 * add has settled: its foes, its bosses and what their fights raise alike, a foe of players alone (a role hostile to
 * players by default; a friendly character, a pet or a summon that ignores players is left out). A creature anywhere
 * else costs a name read.
 *
 * <p>It answers a {@code LOAD} as a {@code SPAWN}: a creature a sleeping section parked comes back through a load, and
 * one the run's start could not see (parked then) gets the omens as it wakes; one that already carries an effect is
 * never given it twice. The creatures a pasted run's prefab brought before anyone arrived get theirs once at the run's
 * start ({@link RunOmens#putOnStanding}).
 */
public final class OmenMobs extends RefSystem<EntityStore> {

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            World world = store.getExternalData().getWorld();
            if (world == null || CopyRouter.dungeonOf(world.getName()) == null) {
                return;
            }
            TransformComponent at = store.getComponent(ref, TransformComponent.getComponentType());
            if (at == null) {
                return;
            }
            List<String> omens = DungeonRuns.shared().omensAt(null, world.getWorldConfig().getUuid(),
                    new Vector3d(at.getPosition()));
            if (omens == null || omens.isEmpty()) {
                return;
            }
            List<EntityEffect> effects = RunOmens.mobEffects(omens);
            if (!effects.isEmpty()) {
                world.execute(() -> RunOmens.putOn(ref, effects));
            }
        } catch (Throwable t) {
            SafeLog.fine("[dungeon] a creature's omens could not be read: " + t.getMessage());
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // A creature's effects go with it.
    }
}
