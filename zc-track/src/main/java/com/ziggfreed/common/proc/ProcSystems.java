package com.ziggfreed.common.proc;

import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.modules.interaction.event.InteractionChainStartEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * The systems behind the triggers the family had no moment for, registered once by {@link ProcBootstrap} (the system
 * registry is class-keyed): a hit taken and a hit blocked ({@link Taken}), the holder's own dodge, guard and cast
 * ({@link ChainStarts}), a landing ({@link Landing}), the holder pass ({@link HolderPass}, which reads a crouch held
 * still), a player's death emptying their counters ({@link Died}), and the forgetting of an entity that left its
 * store ({@link Left}). Hit, Kill, BlockBreak and Harvest ride
 * the library's own moments ({@link ProcMoments}); Crit is a consumer's ({@link Procs#crit}).
 */
public final class ProcSystems {

    private ProcSystems() {
    }

    /**
     * A hit a foe landed on a player (HitTaken, the damage as applied its amount) and a hit their guard blocked
     * ({@code Damage.BLOCKED}, Blocked), in the Inspect group, once the hit is applied. Only a hit an entity dealt
     * counts (a fall, the fire you stand in, never).
     */
    public static final class Taken extends DamageEventSystem {

        @Nonnull
        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getInspectDamageGroup();
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            try {
                if (damage.isCancelled() || !(damage.getSource() instanceof Damage.EntitySource source)) {
                    return;
                }
                boolean taken = Procs.listens(ProcTrigger.HIT_TAKEN);
                boolean blockedListens = Procs.listens(ProcTrigger.BLOCKED);
                if (!taken && !blockedListens) {
                    return;
                }
                Ref<EntityStore> victim = chunk.getReferenceTo(index);
                Ref<EntityStore> attacker = source.getRef();
                if (attacker == null || !attacker.isValid() || Nearby.same(attacker, victim)) {
                    return;
                }
                if (taken && damage.getAmount() > 0f) {
                    Procs.fire(new ProcMoment(ProcTrigger.HIT_TAKEN, store, commandBuffer, victim, attacker, damage,
                            damage.getAmount(), null));
                }
                if (blockedListens && Boolean.TRUE.equals(damage.getIfPresentMetaObject(Damage.BLOCKED))) {
                    Procs.fire(new ProcMoment(ProcTrigger.BLOCKED, store, commandBuffer, victim, attacker, damage,
                            damage.getAmount(), null));
                }
            } catch (Throwable t) {
                Procs.failed("a hit taken", t);
            }
        }
    }

    /**
     * A chain the player's own input started (a client chain: the engine numbers server chains below 0): Dodge, a
     * Wielding chain (GuardStart), Ability1 to Ability4 (Cast). A chain a mod fires from the server under one of those
     * labels (an ability body, a proc's own chain) never counts. Only an uncancelled start.
     */
    public static final class ChainStarts extends EntityEventSystem<EntityStore, InteractionChainStartEvent> {

        public ChainStarts() {
            super(InteractionChainStartEvent.class);
        }

        @Nullable
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InteractionChainStartEvent event) {
            try {
                if (event.isCancelled() || event.getChain().getChainId() < 0) {
                    return;
                }
                ProcTrigger trigger = triggerOf(event.getType());
                if (trigger == null || !Procs.listens(trigger)) {
                    return;
                }
                Procs.fire(new ProcMoment(trigger, store, commandBuffer, chunk.getReferenceTo(index), null, null, 1.0,
                        null));
            } catch (Throwable t) {
                Procs.failed("a chain start", t);
            }
        }

        /** The trigger a chain of {@code type} is, or null. */
        @Nullable
        static ProcTrigger triggerOf(@Nullable InteractionType type) {
            if (type == null) {
                return null;
            }
            return switch (type) {
                case Dodge -> ProcTrigger.DODGE;
                case Wielding -> ProcTrigger.GUARD_START;
                case Ability1, Ability2, Ability3, Ability4 -> ProcTrigger.CAST;
                default -> null;
            };
        }
    }

    /**
     * A player landing after a fall: the movement update that puts them on the ground while they have fallen, read in
     * the Gather damage group before the engine's fall damage step resets the distance, the blocks fallen its amount.
     */
    public static final class Landing extends EntityTickingSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = Query.and(Player.getComponentType(), PlayerInput.getComponentType(),
                MovementStatesComponent.getComponentType());

        @Nonnull
        private final Set<Dependency<EntityStore>> dependencies = Set.of(
                new SystemDependency<>(Order.BEFORE, DamageSystems.FallDamagePlayers.class));

        @Nullable
        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getGatherDamageGroup();
        }

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
        public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
            if (!Procs.listens(ProcTrigger.LANDING)) {
                return;
            }
            super.tick(dt, systemIndex, store);
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                Player player = chunk.getComponent(index, Player.getComponentType());
                PlayerInput input = chunk.getComponent(index, PlayerInput.getComponentType());
                if (player == null || input == null) {
                    return;
                }
                double fallen = player.getCurrentFallDistance();
                if (!(fallen > 0.0)) {
                    return;
                }
                MovementStatesComponent states = chunk.getComponent(index, MovementStatesComponent.getComponentType());
                MovementStates before = states == null ? null : states.getMovementStates();
                if (!landsIn(before != null && before.onGround, input.getMovementUpdateQueue())) {
                    return;
                }
                Procs.fire(new ProcMoment(ProcTrigger.LANDING, store, commandBuffer, chunk.getReferenceTo(index), null,
                        null, fallen, null));
            } catch (Throwable t) {
                Procs.failed("a landing", t);
            }
        }

        /**
         * Does the queue hold a movement update that puts the player on the ground from off it? {@code grounded} is
         * where the last tick left them (the queue is applied after this system). Only that step is a landing: where
         * nothing resets the fall (a world with fall damage off), a player standing on the ground never lands again.
         */
        private static boolean landsIn(boolean grounded, @Nullable List<?> queue) {
            if (queue == null) {
                return false;
            }
            boolean onGround = grounded;
            for (Object update : queue) {
                if (update instanceof PlayerInput.SetMovementStates states && states.movementStates() != null) {
                    boolean next = states.movementStates().onGround;
                    if (next && !onGround) {
                        return true;
                    }
                    onGround = next;
                }
            }
            return false;
        }
    }

    /**
     * Every tick, for every living player, the holder pass when its time has come ({@link Procs#holderPass}): the
     * counters' decay, the held effects and a crouch held still. Ordered after the engine's stat-modifier recompute,
     * where gear and effects reach a stat's maximum.
     */
    public static final class HolderPass extends EntityTickingSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = Query.and(PlayerRef.getComponentType(),
                EntityStatMap.getComponentType(), Query.not(DeathComponent.getComponentType()));

        @Nonnull
        private final Set<Dependency<EntityStore>> dependencies = Set.of(
                new SystemDependency<>(Order.AFTER, EntityStatsSystems.Recalculate.class));

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
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            Procs.holderPass(store, commandBuffer, chunk.getReferenceTo(index));
        }
    }

    /** A living entity left its store (a death, an unload, a world change): forget it as a holder and as a target. */
    public static final class Left extends RefSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return EntityStatMap.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                Procs.forget(ref);
            } catch (Throwable t) {
                Procs.failed("forgetting an entity that left", t);
            }
        }
    }

    /**
     * A player died: every counter they hold empties, its modifiers and its shown effect with it (the holder pass
     * skips the dead, so nothing else would).
     */
    public static final class Died extends DeathSystems.OnDeathSystem {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return PlayerRef.getComponentType();
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            try {
                Counters.clearAll(commandBuffer, ref);
            } catch (Throwable t) {
                Procs.failed("emptying a dead player's counters", t);
            }
        }
    }
}
