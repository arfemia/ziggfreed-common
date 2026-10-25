package com.ziggfreed.common.objectives.bonus;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;
import com.ziggfreed.common.loot.drops.DropContext;
import com.ziggfreed.common.objectives.producer.BlockBreakPayload;
import com.ziggfreed.common.objectives.producer.MobKillPayload;
import com.ziggfreed.common.objectives.producer.ZigBlockBreakProducer;
import com.ziggfreed.common.objectives.producer.ZigMobKillProducer;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.gather.GatherStatSources;
import com.ziggfreed.common.world.gather.GatherYields;
import com.ziggfreed.common.world.gather.ShapedBreaks;

/**
 * Who a native drop list rolls for, said to zc-loot's {@link DropContext} off the produced moments, so a
 * {@code ZigLootable} container inside a block's or an NPC's drop list asks its table about the player who broke or
 * killed it, exactly as a bonus row asks about the moment's player.
 *
 * <ul>
 *   <li>A break ({@code BREAK_BLOCK}): {@link ZigBlockBreakProducer} says nobody first for every break it sees, then
 *       fires inside the engine's break event, and the engine rolls the block's drops straight after it, so a
 *       credited break is said there and then ({@link DropContext#breaking}) and answers that one roll, carrying the
 *       breaker's gathering yield ({@link GatherYields}) onto each written roll {@code Chance}. A block the breaker
 *       placed fires no moment, so its drops roll for nobody, and so do a vein's or a felled tree's further blocks
 *       unless the shape lets them roll rows ({@link ShapedBreaks#rowsAllowed}), so a player-only row pays once, on
 *       the block in hand, as a bonus row does.</li>
 *   <li>A kill ({@code KILL_ENTITY}): the producer fires when the death lands, crediting the player its attribution
 *       answered once, at fire time, and the engine drops the corpse's items a tick or a few seconds later. The credit
 *       is kept per victim until then. {@link Stamp}, just before the engine's death-drop pass and after its corpse
 *       timers tick, names each NPC that pass may drop for this tick, with its credit or none
 *       ({@link DropContext#dying}); {@link Close}, just after, forgets this tick's stamps and every credit whose
 *       corpse is gone.</li>
 * </ul>
 * All of it waits on {@link DropContext#live}: until a ZigLootable container has decoded, no break, kill or tick here
 * builds, stores or sweeps anything.
 */
public final class DropCredit implements MomentListener {

    /** The one listener. */
    public static final DropCredit INSTANCE = new DropCredit();

    /** Each kill's credit, by the victim's own reference, until its corpse is gone. */
    private static final Map<Ref<EntityStore>, DropContext.Who> CREDITED = new ConcurrentHashMap<>();

    private DropCredit() {
    }

    @Override
    public void react(@Nonnull Moment moment) {
        if (!DropContext.live()) {
            return;
        }
        try {
            String kind = moment.kindId();
            if (ZigBlockBreakProducer.KIND.equals(kind)) {
                onBreak(moment, moment.payload(BlockBreakPayload.class));
            } else if (ZigMobKillProducer.KIND.equals(kind)) {
                onKill(moment, moment.payload(MobKillPayload.class));
            }
        } catch (Throwable t) {
            SafeLog.warn("[loot] drops: the drop credit for a " + moment.kindId() + " moment failed", t);
        }
    }

    private static void onBreak(@Nonnull Moment moment, @Nullable BlockBreakPayload payload) {
        if (payload == null) {
            return;
        }
        // A further block a vein or fell shape breaks for the player credits nobody unless the shape lets its blocks
        // roll (its Rows), as a bonus row reads it: a player-only row pays once, on the block in hand.
        if (!ShapedBreaks.rowsAllowed()) {
            DropContext.breakingForNobody();
            return;
        }
        // The breaker's gathering yield raises each written roll Chance of a table in the block's drop list, as it
        // raises a bonus row's own Chance.
        double yield = GatherYields.percentFor(moment.store(), moment.ref(), GatherStatSources.On.BREAK,
                moment.target());
        DropContext.breaking(new DropContext.Who(BonusRowReactions.subjectOf(moment), moment.ref(), null,
                moment.target(), yield), payload.event().getBlockType());
    }

    private static void onKill(@Nonnull Moment moment, @Nullable MobKillPayload payload) {
        if (payload == null) {
            return;
        }
        Ref<EntityStore> victim = payload.victimRef();
        CREDITED.put(victim, new DropContext.Who(BonusRowReactions.subjectOf(moment), moment.ref(), victim, null));
    }

    /**
     * Just before the engine's death-drop pass ({@code NPCDamageSystems.DropDeathItems}), and after the corpse timers
     * it reads have ticked ({@code DeathSystems.TickCorpseRemoval}): each NPC that pass may drop for this tick (its
     * items lost on death, a drop list, not yet dropped, its corpse due), with the probe the engine flips as it drops
     * it, the drop list it rolls, and the player its kill credited, or none. Same thread, same order as that pass; a
     * stamp that pass does not drop is never flipped, so it never answers.
     */
    public static final class Stamp extends EntityTickingSystem<EntityStore> {

        @Nonnull private final Query<EntityStore> query =
                Query.and(NPCEntity.getComponentType(), DeathComponent.getComponentType());

        @Nonnull private final Set<Dependency<EntityStore>> dependencies = Set.of(
                new SystemDependency<>(Order.AFTER, DeathSystems.TickCorpseRemoval.class),
                new SystemDependency<>(Order.BEFORE, NPCDamageSystems.DropDeathItems.class));

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
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            if (!DropContext.live()) {
                return;
            }
            try {
                DeathComponent death = archetypeChunk.getComponent(index, DeathComponent.getComponentType());
                NPCEntity npc = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
                Role role = npc == null ? null : npc.getRole();
                if (death == null || role == null || role.hasDroppedDeathItems() || role.getDropListId() == null
                        || death.getItemsLossMode() != DeathConfig.ItemsLossMode.ALL) {
                    return;
                }
                if (!role.isDropDeathItemsInstantly()) {
                    DeferredCorpseRemoval deferred =
                            archetypeChunk.getComponent(index, DeferredCorpseRemoval.getComponentType());
                    if (deferred != null && !deferred.shouldRemove()) {
                        return;
                    }
                }
                DropContext.dying(role::hasDroppedDeathItems, role.getDropListId(),
                        CREDITED.get(archetypeChunk.getReferenceTo(index)));
            } catch (Throwable t) {
                SafeLog.warn("[loot] drops: a dying NPC could not be stamped for its drops", t);
            }
        }
    }

    /**
     * Just after the engine's death-drop pass: this world thread's deaths and last break are forgotten, so neither
     * outlives its tick, and every credit whose corpse is gone is dropped.
     */
    public static final class Close extends TickingSystem<EntityStore> {

        @Nonnull private final Set<Dependency<EntityStore>> dependencies =
                Set.of(new SystemDependency<>(Order.AFTER, NPCDamageSystems.DropDeathItems.class));

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return dependencies;
        }

        @Override
        public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
            if (!DropContext.live()) {
                return;
            }
            DropContext.clear();
            if (!CREDITED.isEmpty()) {
                CREDITED.keySet().removeIf(ref -> !ref.isValid());
            }
        }
    }
}
