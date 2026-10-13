package com.ziggfreed.common.world;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.modules.interaction.event.InteractionChainStartEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.CycleBlockGroupInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.operation.Operation;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Enforces {@link BlockRules#creativeOnlyBreaking}: in a world whose gameplay config writes it, every
 * breaker but a creative-mode player is refused. Silent, as the engine's own refusal is: the block is
 * resent to the breaker's client and nothing is said.
 *
 * <p><b>Where each refusal sits.</b> The engine breaks a block in two ways. A creative-mode player removes
 * it outright, which fires only the break event. Everybody else damages it first, and the damage event
 * fires before the block takes any (a soft block and a fragile placed block included), so refusing the
 * damage refuses the break that would follow. That is why the refusals sit on {@link DamageBlockEvent}:
 * an entity's ({@link AttributedDamage}: players, NPCs, a projectile's owner) and the world's own
 * ({@link UnattributedDamage}: an explosion, which damages with no entity). A block-group cycling tool
 * swaps a block under the same engine permission without damaging it, so {@link BlockCycle} refuses that
 * chain as it starts. A break no entity attributes and nothing damages first (a support collapse, fire)
 * and a break by an interaction the engine never gated on {@code AllowBlockBreaking} (carrying or
 * destroying a block by design) are left alone.
 *
 * <p><b>One gap no event closes.</b> An explosion turns a block that has an explosion reaction state
 * (an explosive barrel, a boomshroom, some traps) into that state before the damage event fires, so in
 * such a world those blocks react to an explosion while every other block holds.
 *
 * <p><b>Cannot tell reads as allowed</b>, as {@link BuildPermission} reads it: an unresolved world or a
 * throw leaves the answer to the engine. Each system is ordered first among its event's listeners, so a
 * refused damage never reaches a later one.
 */
public final class BlockRuleSystems {

    private BlockRuleSystems() {
    }

    /** Whether the world this store belongs to lets only a creative-mode player break; false when it cannot be told. */
    static boolean creativeOnlyBreaking(@Nonnull Store<EntityStore> store) {
        try {
            World world = store.getExternalData().getWorld();
            return world != null && BlockRules.creativeOnlyBreaking(world.getGameplayConfig());
        } catch (Throwable t) {
            SafeLog.fine("[build] could not read the world's block rules; leaving the break to the engine: "
                    + t.getMessage());
            return false;
        }
    }

    /** The game mode of the entity at {@code index}, or null when it is no player. */
    @Nullable
    static GameMode gameModeAt(int index, @Nonnull ArchetypeChunk<EntityStore> chunk) {
        Player player = chunk.getComponent(index, Player.getComponentType());
        return player == null ? null : player.getGameMode();
    }

    /**
     * Whether a root interaction swaps a block through its block group anywhere in its compiled chain. An
     * unbuilt root or a throw reads as no.
     */
    static boolean cyclesBlockGroups(@Nullable RootInteraction root) {
        if (root == null) {
            return false;
        }
        try {
            int max = root.getOperationMax();
            for (int i = 0; i < max; i++) {
                Operation operation = root.getOperation(i);
                if (operation != null && operation.getInnerOperation() instanceof CycleBlockGroupInteraction) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            SafeLog.fine("[build] could not read the interaction chain " + root.getId() + ": " + t.getMessage());
            return false;
        }
    }

    /** Block damage an entity deals: a player's swing, an NPC's, a projectile's for its owner. */
    public static final class AttributedDamage extends EntityEventSystem<EntityStore, DamageBlockEvent> {

        public AttributedDamage() {
            super(DamageBlockEvent.class);
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return RootDependency.firstSet();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull DamageBlockEvent event) {
            if (!creativeOnlyBreaking(store)) {
                return;
            }
            if (BlockRules.refusesBreaker(true, gameModeAt(index, archetypeChunk))) {
                event.setCancelled(true);
            }
        }
    }

    /** Block damage no entity deals, which is an explosion's. */
    public static final class UnattributedDamage extends WorldEventSystem<EntityStore, DamageBlockEvent> {

        public UnattributedDamage() {
            super(DamageBlockEvent.class);
        }

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return RootDependency.firstSet();
        }

        @Override
        public void handle(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull DamageBlockEvent event) {
            if (creativeOnlyBreaking(store)) {
                event.setCancelled(true);
            }
        }
    }

    /** A player's interaction chain that would swap a block through its block group. */
    public static final class BlockCycle extends EntityEventSystem<EntityStore, InteractionChainStartEvent> {

        public BlockCycle() {
            super(InteractionChainStartEvent.class);
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Player.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
                @Nonnull InteractionChainStartEvent event) {
            if (!creativeOnlyBreaking(store)) {
                return;
            }
            if (!BlockRules.refusesBreaker(true, gameModeAt(index, archetypeChunk))) {
                return;
            }
            if (cyclesBlockGroups(event.getChain().getInitialRootInteraction())) {
                event.setCancelled(true);
            }
        }
    }
}
