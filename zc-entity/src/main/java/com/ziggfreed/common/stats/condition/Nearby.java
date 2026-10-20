package com.ziggfreed.common.stats.condition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.blackboard.Blackboard;
import com.hypixel.hytale.server.npc.blackboard.view.attitude.AttitudeView;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;

/**
 * Who is near an entity, read off the engine's own spatial trees (rebuilt every tick by the engine's spatial systems):
 * the foes and the other players within a radius, each one asked a set of native {@link Condition}s of its own. Used
 * by the {@code ZigFoesNear} and {@code ZigPlayersNear} conditions and by a conditional stat's {@code PerFoe} count.
 *
 * <p><b>A foe</b> is a living creature (an NPC with no death component) that is hostile to the entity (the creature's
 * attitude toward it, through the NPC blackboard's attitude view) or has the entity in its {@code LockedTarget} slot
 * (a creature that has turned on it). With no attitude view to ask, a creature counts only by its target.
 *
 * <p><b>Cost</b>: one tree query, then one attitude read per creature found, then its conditions. A count stops as soon
 * as it reaches {@code stopAt}, so "three or more" asks no fourth creature. World thread only; never throws (a read
 * that fails counts nothing).
 */
public final class Nearby {

    private Nearby() {
    }

    /**
     * How many foes of {@code holder} are within {@code radius} blocks and meet every one of {@code conditions}
     * (none: every foe), counting no further than {@code stopAt} (0 or less: no limit).
     */
    public static int foes(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            double radius, @Nullable Condition[] conditions, @Nonnull Instant now, int stopAt) {
        try {
            Vector3d at = positionOf(accessor, holder);
            if (at == null || !(radius > 0.0)) {
                return 0;
            }
            SpatialResource<Ref<EntityStore>, EntityStore> npcs = accessor.getResource(NPCPlugin.get().getNpcSpatialResource());
            if (npcs == null) {
                return 0;
            }
            List<Ref<EntityStore>> found = new ArrayList<>();
            npcs.getSpatialStructure().collect(at, radius, found);
            if (found.isEmpty()) {
                return 0;
            }
            AttitudeView attitudes = attitudes(accessor);
            int count = 0;
            for (Ref<EntityStore> creature : found) {
                if (creature == null || !creature.isValid() || Targets.same(creature, holder)
                        || accessor.getComponent(creature, DeathComponent.getComponentType()) != null
                        || !isFoe(accessor, creature, holder, attitudes)
                        || !allHold(accessor, creature, conditions, now)) {
                    continue;
                }
                count++;
                if (stopAt > 0 && count >= stopAt) {
                    return count;
                }
            }
            return count;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * How many other players are within {@code radius} blocks of {@code holder} and meet every one of
     * {@code conditions}, counting no further than {@code stopAt} (0 or less: no limit). No party exists in the
     * game, so every other living player counts.
     */
    public static int players(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            double radius, @Nullable Condition[] conditions, @Nonnull Instant now, int stopAt) {
        try {
            Vector3d at = positionOf(accessor, holder);
            if (at == null || !(radius > 0.0)) {
                return 0;
            }
            SpatialResource<Ref<EntityStore>, EntityStore> players =
                    accessor.getResource(EntityModule.get().getPlayerSpatialResourceType());
            if (players == null) {
                return 0;
            }
            List<Ref<EntityStore>> found = new ArrayList<>();
            players.getSpatialStructure().collect(at, radius, found);
            int count = 0;
            for (Ref<EntityStore> other : found) {
                if (other == null || !other.isValid() || Targets.same(other, holder)
                        || accessor.getComponent(other, DeathComponent.getComponentType()) != null
                        || !allHold(accessor, other, conditions, now)) {
                    continue;
                }
                count++;
                if (stopAt > 0 && count >= stopAt) {
                    return count;
                }
            }
            return count;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The foes of {@code holder} within {@code radius} blocks that meet every one of {@code conditions} (none: every
     * foe), nearest first, no more than {@code limit} of them (0 or less: no limit). What a proc with no target of its
     * own seeks one among (zc-track's {@code proc/}). Empty, never null, when none or when a read fails.
     */
    @Nonnull
    public static List<Ref<EntityStore>> foesWithin(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder, double radius, @Nullable Condition[] conditions, @Nonnull Instant now,
            int limit) {
        try {
            Vector3d at = positionOf(accessor, holder);
            if (at == null || !(radius > 0.0)) {
                return List.of();
            }
            SpatialResource<Ref<EntityStore>, EntityStore> npcs = accessor.getResource(NPCPlugin.get().getNpcSpatialResource());
            if (npcs == null) {
                return List.of();
            }
            List<Ref<EntityStore>> found = new ArrayList<>();
            npcs.getSpatialStructure().collect(at, radius, found);
            if (found.isEmpty()) {
                return List.of();
            }
            AttitudeView attitudes = attitudes(accessor);
            List<Ref<EntityStore>> foes = new ArrayList<>();
            List<Double> distances = new ArrayList<>();
            for (Ref<EntityStore> creature : found) {
                if (creature == null || !creature.isValid() || Targets.same(creature, holder)
                        || accessor.getComponent(creature, DeathComponent.getComponentType()) != null
                        || !isFoe(accessor, creature, holder, attitudes)
                        || !allHold(accessor, creature, conditions, now)) {
                    continue;
                }
                Vector3d there = positionOf(accessor, creature);
                double distance = there == null ? Double.MAX_VALUE : there.distanceSquared(at);
                int slot = 0;
                while (slot < distances.size() && distances.get(slot) <= distance) {
                    slot++;
                }
                foes.add(slot, creature);
                distances.add(slot, distance);
            }
            if (limit > 0 && foes.size() > limit) {
                return List.copyOf(foes.subList(0, limit));
            }
            return List.copyOf(foes);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Are two refs one live entity (the same slot of the same store)? */
    public static boolean same(@Nullable Ref<EntityStore> a, @Nullable Ref<EntityStore> b) {
        return Targets.same(a, b);
    }

    /** Is the creature hostile to {@code holder}, or locked on to it? */
    private static boolean isFoe(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> creature,
            @Nonnull Ref<EntityStore> holder, @Nullable AttitudeView attitudes) {
        if (Targets.isTargeting(accessor, creature, holder)) {
            return true;
        }
        if (attitudes == null) {
            return false;
        }
        NPCEntity npc = accessor.getComponent(creature, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }
        return attitudes.getAttitude(creature, npc.getRoleIndex(), holder, accessor) == Attitude.HOSTILE;
    }

    /** Do all of {@code conditions} hold on {@code ref}? True for none. */
    public static boolean allHold(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nullable Condition[] conditions, @Nonnull Instant now) {
        if (conditions == null || conditions.length == 0) {
            return true;
        }
        for (Condition condition : conditions) {
            if (condition != null && !condition.eval(accessor, ref, now)) {
                return false;
            }
        }
        return true;
    }

    /** Where the entity stands, or null when it has no transform. */
    @Nullable
    public static Vector3d positionOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        TransformComponent transform = accessor.getComponent(ref, TransformComponent.getComponentType());
        return transform == null ? null : transform.getPosition();
    }

    @Nullable
    private static AttitudeView attitudes(@Nonnull ComponentAccessor<EntityStore> accessor) {
        try {
            Blackboard blackboard = accessor.getResource(Blackboard.getResourceType());
            return blackboard == null ? null : blackboard.getView(AttitudeView.class, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The {@code LockedTarget} read, shared with the hit step's "not targeting you". */
    static final class Targets {

        private Targets() {
        }

        /** Is {@code creature} an NPC whose {@code LockedTarget} slot holds {@code who}? False when unreadable. */
        static boolean isTargeting(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> creature,
                @Nonnull Ref<EntityStore> who) {
            try {
                MarkedEntitySupport marked = accessor.getComponent(creature, MarkedEntitySupport.getComponentType());
                if (marked == null) {
                    return false;
                }
                return same(marked.getMarkedEntityRef(MarkedEntitySupport.DEFAULT_TARGET_SLOT), who);
            } catch (Throwable t) {
                return false;
            }
        }

        /** Are two refs one live entity (the same slot of the same store)? A ref holds no equality of its own. */
        static boolean same(@Nullable Ref<EntityStore> a, @Nullable Ref<EntityStore> b) {
            if (a == null || b == null || !a.isValid() || !b.isValid()) {
                return false;
            }
            return a == b || (a.getIndex() == b.getIndex() && a.getStore() == b.getStore());
        }

        /** Is {@code creature} an NPC (one with a target slot at all)? */
        static boolean isCreature(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
            try {
                return accessor.getComponent(ref, MarkedEntitySupport.getComponentType()) != null;
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
