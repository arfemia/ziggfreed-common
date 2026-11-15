package com.ziggfreed.common.proc;

import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.damage.DamageDataComponent;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.modules.interaction.event.InteractionChainStartEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.WieldingInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.condition.ConditionalStats;
import com.ziggfreed.common.stats.condition.EffectFamilies;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * The systems behind the triggers the family had no moment for, registered once by {@link ProcBootstrap} (the system
 * registry is class-keyed): a hit taken, a hit blocked and a hit dodged ({@link Taken}), a hit about to land on a
 * player or their ally ({@link Incoming}), the holder's own dodge and cast started and every chain's root
 * ({@link ChainStarts}), a landing ({@link Landing}), the holder pass ({@link HolderPass}, which proves the dodges and
 * casts, reads a guard going up and a foe's guard broken, the watched effects and a crouch held still), a player's
 * death emptying their counters ({@link Died}), and the forgetting of an entity that left its store ({@link Left}). Hit, Kill, BlockBreak and Harvest ride the library's own moments ({@link ProcMoments}), Crit
 * zc-entity's crit step's event ({@link Procs#crit}); a consumer's own Cast is the consumer's ({@link Procs#cast}).
 */
final class ProcSystems {

    private ProcSystems() {
    }

    /**
     * A hit a foe landed on a player (HitTaken, the damage as applied its amount), a hit their guard blocked
     * ({@code Damage.BLOCKED}, Blocked, the stamina the guard is about to pay for it the moment's cost), and a hit
     * their dodge slipped (Dodged: the engine cancelled it because an effect of the dodge family made them
     * invulnerable, and for no other reason it has, the amount the hit would have dealt; held back until that dodge is
     * proven when its proof is still waiting, {@link MoveProofs#deferDodged}), in the Inspect group, once the hit is
     * applied and before the engine's guard pays its stamina ({@code DamageSystems.DamageStamina}), so a Blocked proc
     * can still cut what the block costs. A player's blocked hit whose drain takes some other entity's guard below
     * nothing (the one hit that breaks it, read after the blocker's own Blocked procs) is noted for the GuardBroke
     * watch ({@link GuardBreaks}). This one system also hears cancelled hits (its {@code shouldProcessEvent}), for
     * the dodge; every other path skips them. Only a hit an entity dealt counts (a fall, the fire you stand in,
     * never).
     */
    public static final class Taken extends DamageEventSystem {

        @Nonnull
        private final Set<Dependency<EntityStore>> dependencies = Set.of(
                new SystemDependency<>(Order.BEFORE, DamageSystems.DamageStamina.class));

        @Nonnull
        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getInspectDamageGroup();
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

        /** Hear a cancelled hit too: a dodge's invulnerability cancels the hit it slips. */
        @Override
        protected boolean shouldProcessEvent(@Nonnull Damage event) {
            return true;
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            try {
                boolean player = chunk.getComponent(index, PlayerRef.getComponentType()) != null;
                if (!player && !Procs.listens(ProcTrigger.GUARD_BROKE)) {
                    return;
                }
                if (damage.isCancelled()) {
                    if (player) {
                        dodged(chunk.getReferenceTo(index), store, commandBuffer, damage);
                    }
                    return;
                }
                if (!(damage.getSource() instanceof Damage.EntitySource source)) {
                    return;
                }
                Ref<EntityStore> victim = chunk.getReferenceTo(index);
                Ref<EntityStore> attacker = source.getRef();
                if (attacker == null || !attacker.isValid() || Nearby.same(attacker, victim)) {
                    return;
                }
                boolean blocked = Boolean.TRUE.equals(damage.getIfPresentMetaObject(Damage.BLOCKED));
                if (player) {
                    if (Procs.listens(ProcTrigger.HIT_TAKEN) && damage.getAmount() > 0f) {
                        Procs.fire(new ProcMoment(ProcTrigger.HIT_TAKEN, store, commandBuffer, victim, attacker,
                                damage, damage.getAmount(), null));
                    }
                    if (blocked && Procs.listens(ProcTrigger.BLOCKED)) {
                        Procs.fire(new ProcMoment(ProcTrigger.BLOCKED, store, commandBuffer, victim, attacker, damage,
                                damage.getAmount(), null, null, null, null, guardCost(chunk, index, damage)));
                    }
                }
                // Read after the blocker's own Blocked procs (which may spare the block): only the player whose
                // blocked hit drains the guard below nothing broke it, so only they are watched for the break.
                if (blocked && Procs.listens(ProcTrigger.GUARD_BROKE)
                        && commandBuffer.getComponent(attacker, PlayerRef.getComponentType()) != null
                        && breaksGuard(chunk, index, damage)) {
                    GuardBreaks.struck(attacker, victim, System.nanoTime());
                }
            } catch (Throwable t) {
                Procs.failed("a hit taken", t);
            }
        }

        /** Does the blocked {@code damage} drain the guard of the entity at {@code index} below nothing? */
        private static boolean breaksGuard(@Nonnull ArchetypeChunk<EntityStore> chunk, int index,
                @Nonnull Damage damage) {
            EntityStatMap stats = chunk.getComponent(index, EntityStatMap.getComponentType());
            EntityStatValue stamina = stats == null ? null : stats.get(DefaultEntityStatTypes.getStamina());
            return stamina != null && Procs.breaksGuard(stamina.get(), guardCost(chunk, index, damage));
        }

        /**
         * The stamina the guard of the entity at {@code index} is about to pay for the blocked {@code damage}: the
         * engine's own reckoning (its wielding's {@code StaminaCost} on the hit's amount before the guard, times the
         * hit's stamina-drain multiplier), read the moment before its stamina step pays it. 0 when it pays nothing.
         */
        static double guardCost(@Nonnull ArchetypeChunk<EntityStore> chunk, int index, @Nonnull Damage damage) {
            try {
                DamageDataComponent data = chunk.getComponent(index, DamageDataComponent.getComponentType());
                EntityStatMap stats = chunk.getComponent(index, EntityStatMap.getComponentType());
                WieldingInteraction wielding = data == null ? null : data.getCurrentWielding();
                WieldingInteraction.StaminaCost cost = wielding == null ? null : wielding.getStaminaCost();
                if (cost == null || stats == null) {
                    return 0.0;
                }
                double amount = cost.computeStaminaAmountToConsume(damage.getInitialAmount(), stats);
                Float multiplier = damage.getIfPresentMetaObject(Damage.STAMINA_DRAIN_MULTIPLIER);
                if (multiplier != null) {
                    amount *= multiplier;
                }
                return Double.isFinite(amount) && amount > 0.0 ? amount : 0.0;
            } catch (Throwable t) {
                return 0.0;
            }
        }

        /**
         * A cancelled hit from a foe on a player whom only their dodge's invulnerability spared: Dodged. The engine
         * names no reason for a cancel, so every other reason it has is ruled out first (the player dead, immune or
         * intangible, under spawn protection, a player's hit in a world with PvP off, incoming damage switched off by
         * the world's combat config); what is left with a dodge effect of the family on is the dodge's.
         */
        private static void dodged(@Nonnull Ref<EntityStore> victim, @Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Damage damage) {
            if (!Procs.listens(ProcTrigger.DODGED) || !(damage.getSource() instanceof Damage.EntitySource source)) {
                return;
            }
            Ref<EntityStore> attacker = source.getRef();
            if (attacker == null || !attacker.isValid() || Nearby.same(attacker, victim)
                    || !dodgeInvulnerable(commandBuffer, victim)
                    || cancelledOtherwise(store, commandBuffer, victim, attacker)) {
                return;
            }
            // A dodge whose proof still waits answers its Dodge first, so the payback mark the Dodge clears is never
            // cleared after a Dodged paid it.
            if (MoveProofs.deferDodged(victim, attacker, damage)) {
                return;
            }
            Procs.fire(new ProcMoment(ProcTrigger.DODGED, store, commandBuffer, victim, attacker, damage,
                    damage.getAmount(), null));
        }

        /**
         * Would the engine have cancelled the hit for a reason other than the dodge's invulnerability? True when it
         * cannot tell, so no other cancel ever reads as a dodge.
         */
        private static boolean cancelledOtherwise(@Nonnull Store<EntityStore> store,
                @Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> victim,
                @Nonnull Ref<EntityStore> attacker) {
            try {
                Player player = accessor.getComponent(victim, Player.getComponentType());
                World world = store.getExternalData().getWorld();
                boolean attackerIsPlayer = accessor.getComponent(attacker, Player.getComponentType()) != null;
                return otherCancel(accessor.getComponent(victim, DeathComponent.getComponentType()) != null
                                || accessor.getComponent(victim, Invulnerable.getComponentType()) != null
                                || accessor.getComponent(victim, Intangible.getComponentType()) != null,
                        player != null && player.hasSpawnProtection(),
                        attackerIsPlayer, world != null && world.getWorldConfig().isPvpEnabled(),
                        world != null && world.getGameplayConfig().getCombatConfig().isPlayerIncomingDamageDisabled());
            } catch (Throwable t) {
                return true;
            }
        }

        /**
         * The engine's other reasons to cancel a hit on a player: the player dead, immune or intangible; spawn
         * protection; a player's hit while PvP is off; incoming damage switched off. Pure.
         */
        static boolean otherCancel(boolean deadImmuneOrIntangible, boolean spawnProtected, boolean attackerIsPlayer,
                boolean pvpOn, boolean incomingDisabled) {
            return deadImmuneOrIntangible || spawnProtected || (attackerIsPlayer && !pvpOn) || incomingDisabled;
        }

        /** Does {@code ref} wear an effect of the dodge family that makes it invulnerable? */
        private static boolean dodgeInvulnerable(@Nonnull ComponentAccessor<EntityStore> accessor,
                @Nonnull Ref<EntityStore> ref) {
            EffectControllerComponent controller =
                    accessor.getComponent(ref, EffectControllerComponent.getComponentType());
            if (controller == null || !controller.isInvulnerable()) {
                return false;
            }
            for (ActiveEntityEffect active : controller.getActiveEffects().values()) {
                if (active.isInvulnerable()
                        && EffectFamilies.contains(MoveProofs.DODGE_FAMILY, active.getEntityEffectIndex())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * A hit about to land on a player: HitIncoming for them, then AllyHitIncoming for each ally of theirs near enough
     * for a live proc to ask (each such proc holds its own {@code AllyRadius}). Asked once every Filter-group step has
     * had its say (armor, guards, resistances, a cancel for an invulnerable target such as the Forgotten Temple's) and
     * just before the engine's {@code ApplyDamage} takes the hit off their health, so a Soak can still lower it, stop
     * it, or leave them standing. A cancelled hit, a hit of nothing and a hit on the dead are passed over. Its amount
     * is the damage the hit would deal; its target the attacker (none for a fall, a burn, a drowning).
     */
    public static final class Incoming extends DamageEventSystem {

        /** The most allies one coming hit asks. */
        static final int ALLY_LIMIT = 16;

        @Nonnull
        private final Set<Dependency<EntityStore>> dependencies = Set.of(
                new SystemGroupDependency<>(Order.AFTER, DamageModule.get().getGatherDamageGroup()),
                new SystemGroupDependency<>(Order.AFTER, DamageModule.get().getFilterDamageGroup()),
                new SystemDependency<>(Order.BEFORE, DamageSystems.ApplyDamage.class));

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return dependencies;
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
                boolean own = Procs.listens(ProcTrigger.HIT_INCOMING);
                boolean allies = Procs.listens(ProcTrigger.ALLY_HIT_INCOMING);
                if ((!own && !allies) || damage.isCancelled() || !(damage.getAmount() > 0f)
                        || chunk.getArchetype().contains(DeathComponent.getComponentType())) {
                    return;
                }
                Ref<EntityStore> victim = chunk.getReferenceTo(index);
                Ref<EntityStore> attacker = damage.getSource() instanceof Damage.EntitySource source
                        && source.getRef() != null && source.getRef().isValid()
                        && !Nearby.same(source.getRef(), victim) ? source.getRef() : null;
                if (own) {
                    Procs.fire(new ProcMoment(ProcTrigger.HIT_INCOMING, store, commandBuffer, victim, attacker, damage,
                            damage.getAmount(), null));
                }
                if (allies && !damage.isCancelled() && damage.getAmount() > 0f) {
                    toAllies(store, commandBuffer, victim, damage);
                }
            } catch (Throwable t) {
                Procs.failed("a coming hit", t);
            }
        }

        /** The same coming hit, asked of each ally of {@code victim} near enough for a live proc. */
        private static void toAllies(@Nonnull Store<EntityStore> store,
                @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> victim,
                @Nonnull Damage damage) {
            double reach = ProcConfig.getInstance().allyReach();
            if (!(reach > 0.0)) {
                return;
            }
            // The victim's allies (zc-core's one rule, symmetric) are the players whose ally was hit.
            for (Ref<EntityStore> other : Nearby.alliesAround(commandBuffer, victim,
                    Nearby.positionOf(commandBuffer, victim), reach, null, ConditionalStats.now(commandBuffer),
                    ALLY_LIMIT)) {
                if (damage.isCancelled() || !(damage.getAmount() > 0f)) {
                    return;
                }
                Procs.fire(new ProcMoment(ProcTrigger.ALLY_HIT_INCOMING, store, commandBuffer, other, victim,
                        damage, damage.getAmount(), null));
            }
        }
    }

    /**
     * A player's chain starting. Every one is recorded by its root while a proc asks which move a hit came from
     * ({@link ChainRoots}); one the player's own input started (a client chain: the engine numbers server chains
     * below 0) under Dodge or Ability1 to Ability4 opens a wait for its proof ({@link MoveProofs}), which the holder
     * pass settles into a Dodge or a Cast moment once the move truly went off. A chain a mod fires from the server
     * under one of those labels (an ability body, a proc's own chain) never counts, unless the mod handed the
     * player's press back to it ({@link Procs#handBack}), nor does one whose root a consumer says starts its own
     * casts. Only an uncancelled start. The guard is read off the holder, never a chain.
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
                if (event.isCancelled()) {
                    return;
                }
                InteractionChain chain = event.getChain();
                Ref<EntityStore> holder = chunk.getReferenceTo(index);
                long nanos = System.nanoTime();
                String root = event.getRootInteractionId();
                if (ProcConfig.getInstance().asksRoots()) {
                    ChainRoots.started(holder, chain, root, nanos);
                }
                if (chain.getChainId() < 0 && !MoveProofs.takeHandedBack(holder, root, nanos)) {
                    return;
                }
                ProcTrigger trigger = triggerOf(event.getType());
                if (trigger == null || !Procs.listens(trigger)) {
                    return;
                }
                if (trigger == ProcTrigger.DODGE) {
                    MoveProofs.dodgeStarted(commandBuffer, holder, chain, nanos);
                } else if (!ProcAbilities.castsItself(root)) {
                    MoveProofs.castStarted(holder, chain, root, nanos);
                }
            } catch (Throwable t) {
                Procs.failed("a chain start", t);
            }
        }

        /** The trigger a chain of {@code type} waits to prove, or null. */
        @Nullable
        static ProcTrigger triggerOf(@Nullable InteractionType type) {
            if (type == null) {
                return null;
            }
            return switch (type) {
                case Dodge -> ProcTrigger.DODGE;
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
