package com.ziggfreed.common.stats.condition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

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
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.blackboard.Blackboard;
import com.hypixel.hytale.server.npc.blackboard.view.attitude.AttitudeView;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.ziggfreed.common.entity.Allies;

/**
 * Who is near an entity, read off the engine's own spatial trees (rebuilt every tick by the engine's spatial systems):
 * the foes, the allies and the other players within a radius, each one asked a set of native {@link Condition}s of its
 * own. Used by the {@code ZigFoesNear} and {@code ZigPlayersNear} conditions, by a conditional stat's {@code PerFoe}
 * count and by zc-track's procs. Every read is the one walk ({@link #walk}): the tree query, the living and
 * not-the-holder check, the kind's own check, then the conditions.
 *
 * <p><b>A foe</b> is a living creature (an NPC with no death component) that is hostile to the entity (the creature's
 * attitude toward it, through the NPC blackboard's attitude view) or has the entity in its {@code LockedTarget} slot
 * (a creature that has turned on it). With no attitude view to ask, a creature counts only by its target.
 *
 * <p><b>An ally</b> is another living player zc-core's {@link Allies} rule names one of a player holder's (R859: their
 * party's members, else any other player while the world's PvP is off). A creature holder has no allies.
 *
 * <p><b>Cost</b>: one tree query, then one kind read per entity found, then its conditions. A count stops as soon as
 * it reaches {@code stopAt}, so "three or more" asks no fourth. World thread only; never throws (a read that fails
 * counts nothing).
 */
public final class Nearby {

    private Nearby() {
    }

    /** Which entities a walk visits, beside the holder itself. */
    private enum Kind {
        /** Creatures hostile to the holder or locked on to it. */
        FOES,
        /** Other players the ally rule names the holder's. */
        ALLIES,
        /** Every other player. */
        PLAYERS
    }

    /**
     * How many foes of {@code holder} are within {@code radius} blocks and meet every one of {@code conditions}
     * (none: every foe), counting no further than {@code stopAt} (0 or less: no limit).
     */
    public static int foes(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            double radius, @Nullable Condition[] conditions, @Nonnull Instant now, int stopAt) {
        return count(accessor, holder, Kind.FOES, radius, conditions, now, stopAt);
    }

    /**
     * How many allies of {@code holder} ({@link Allies}: their party, else other players while PvP is off) are within
     * {@code radius} blocks and meet every one of {@code conditions}, counting no further than {@code stopAt} (0 or
     * less: no limit). 0 for a holder that is no player.
     */
    public static int players(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            double radius, @Nullable Condition[] conditions, @Nonnull Instant now, int stopAt) {
        return count(accessor, holder, Kind.ALLIES, radius, conditions, now, stopAt);
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
            return foesAround(accessor, holder, positionOf(accessor, holder), radius, conditions, now, limit);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * The foes of {@code holder} within {@code radius} blocks of {@code center} (another creature's spot, say) that
     * meet every one of {@code conditions}, nearest the center first, no more than {@code limit} (0 or less: no
     * limit). A foe is one of the holder's, as {@link #foesWithin} reads it. Empty, never null.
     */
    @Nonnull
    public static List<Ref<EntityStore>> foesAround(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder, @Nullable Vector3d center, double radius,
            @Nullable Condition[] conditions, @Nonnull Instant now, int limit) {
        return nearestFirst(accessor, holder, Kind.FOES, center, radius, conditions, now, limit);
    }

    /**
     * The allies of {@code holder} ({@link Allies}) within {@code radius} blocks of {@code center} that meet every one
     * of {@code conditions}, nearest the center first, no more than {@code limit} (0 or less: no limit): the one ally
     * read every "allies near" asks (R859). Empty, never null; empty for a holder that is no player.
     */
    @Nonnull
    public static List<Ref<EntityStore>> alliesAround(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder, @Nullable Vector3d center, double radius,
            @Nullable Condition[] conditions, @Nonnull Instant now, int limit) {
        return nearestFirst(accessor, holder, Kind.ALLIES, center, radius, conditions, now, limit);
    }

    /**
     * The other living players within {@code radius} blocks of {@code center} that meet every one of
     * {@code conditions}, {@code holder} left out, nearest the center first, no more than {@code limit} (0 or less:
     * no limit), allies or not: a reader that wants only allies asks {@link #alliesAround}. Empty, never null.
     */
    @Nonnull
    public static List<Ref<EntityStore>> playersAround(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder, @Nullable Vector3d center, double radius,
            @Nullable Condition[] conditions, @Nonnull Instant now, int limit) {
        return nearestFirst(accessor, holder, Kind.PLAYERS, center, radius, conditions, now, limit);
    }

    /** The count every count read makes: the walk around the holder's own spot, stopping at {@code stopAt}. */
    private static int count(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull Kind kind, double radius, @Nullable Condition[] conditions, @Nonnull Instant now, int stopAt) {
        try {
            int[] count = new int[1];
            walk(accessor, holder, kind, positionOf(accessor, holder), radius, conditions, now, ref -> {
                count[0]++;
                return stopAt <= 0 || count[0] < stopAt;
            });
            return count[0];
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The list every list read makes: the walk around {@code center}, nearest first, held to {@code limit}. */
    @Nonnull
    private static List<Ref<EntityStore>> nearestFirst(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder, @Nonnull Kind kind, @Nullable Vector3d center, double radius,
            @Nullable Condition[] conditions, @Nonnull Instant now, int limit) {
        try {
            if (center == null) {
                return List.of();
            }
            List<Ref<EntityStore>> out = new ArrayList<>();
            List<Double> distances = new ArrayList<>();
            walk(accessor, holder, kind, center, radius, conditions, now, ref -> {
                nearestFirst(out, distances, ref, positionOf(accessor, ref), center);
                return true;
            });
            return capped(out, limit);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * The one walk: every entity of {@code kind} within {@code radius} blocks of {@code center}, living, not the
     * holder, meeting every one of {@code conditions}, handed to {@code each} until it answers false.
     */
    private static void walk(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull Kind kind, @Nullable Vector3d center, double radius, @Nullable Condition[] conditions,
            @Nonnull Instant now, @Nonnull Predicate<Ref<EntityStore>> each) {
        if (center == null || !(radius > 0.0)) {
            return;
        }
        UUID holderId = null;
        boolean pvp = true;
        if (kind == Kind.ALLIES) {
            holderId = accessor.getComponent(holder, PlayerRef.getComponentType()) == null
                    ? null : FirstHits.idOf(accessor, holder);
            if (holderId == null) {
                return; // only a player has allies
            }
            pvp = Allies.pvpOn(accessor);
        }
        SpatialResource<Ref<EntityStore>, EntityStore> tree = accessor.getResource(kind == Kind.FOES
                ? NPCPlugin.get().getNpcSpatialResource() : EntityModule.get().getPlayerSpatialResourceType());
        if (tree == null) {
            return;
        }
        List<Ref<EntityStore>> found = new ArrayList<>();
        tree.getSpatialStructure().collect(center, radius, found);
        if (found.isEmpty()) {
            return;
        }
        AttitudeView attitudes = kind == Kind.FOES ? attitudes(accessor) : null;
        for (Ref<EntityStore> other : found) {
            if (other == null || !other.isValid() || Targets.same(other, holder)
                    || accessor.getComponent(other, DeathComponent.getComponentType()) != null
                    || !isKind(accessor, kind, other, holder, holderId, pvp, attitudes)
                    || !allHold(accessor, other, conditions, now)) {
                continue;
            }
            if (!each.test(other)) {
                return;
            }
        }
    }

    /** Is {@code other} of {@code kind} for {@code holder}? */
    private static boolean isKind(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Kind kind,
            @Nonnull Ref<EntityStore> other, @Nonnull Ref<EntityStore> holder, @Nullable UUID holderId, boolean pvp,
            @Nullable AttitudeView attitudes) {
        return switch (kind) {
            case FOES -> isFoe(accessor, other, holder, attitudes);
            case ALLIES -> {
                UUID otherId = FirstHits.idOf(accessor, other);
                yield holderId != null && otherId != null && Allies.areAllies(holderId, otherId, pvp);
            }
            case PLAYERS -> true;
        };
    }

    /** Put {@code ref} into {@code into} by its distance from {@code center} (unplaced last). */
    private static void nearestFirst(@Nonnull List<Ref<EntityStore>> into, @Nonnull List<Double> distances,
            @Nonnull Ref<EntityStore> ref, @Nullable Vector3d there, @Nonnull Vector3d center) {
        double distance = there == null ? Double.MAX_VALUE : there.distanceSquared(center);
        int slot = 0;
        while (slot < distances.size() && distances.get(slot) <= distance) {
            slot++;
        }
        into.add(slot, ref);
        distances.add(slot, distance);
    }

    @Nonnull
    private static List<Ref<EntityStore>> capped(@Nonnull List<Ref<EntityStore>> refs, int limit) {
        if (limit > 0 && refs.size() > limit) {
            return List.copyOf(refs.subList(0, limit));
        }
        return List.copyOf(refs);
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

    /**
     * Do all of {@code conditions} hold on {@code ref}? True for none. A condition that throws on an entity it
     * cannot read (the engine's {@code Wielding} on an entity with no damage data) does not hold.
     */
    public static boolean allHold(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref,
            @Nullable Condition[] conditions, @Nonnull Instant now) {
        if (conditions == null || conditions.length == 0) {
            return true;
        }
        for (Condition condition : conditions) {
            if (condition == null) {
                continue;
            }
            try {
                if (!condition.eval(accessor, ref, now)) {
                    return false;
                }
            } catch (Throwable t) {
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
