package com.ziggfreed.common.instance.dungeon.room;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.joml.Vector3d;

import com.hypixel.hytale.builtin.encountermanager.EncounterManager;
import com.hypixel.hytale.builtin.points.PointsPlugin;
import com.hypixel.hytale.builtin.points.component.Point;
import com.hypixel.hytale.builtin.triggervolumes.TriggerVolumesPlugin;
import com.hypixel.hytale.builtin.triggervolumes.component.TriggerVolume;
import com.hypixel.hytale.builtin.triggervolumes.component.TriggerVolumeGroup;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * A room prefab's entities, read without pasting ({@code IPrefabBuffer.forEachEntity} hands out the stored holders,
 * never to be changed): each one's kind, its anchor-relative place and yaw, and, for a volume, a group or a point,
 * its component encoded as the engine's own JSON. Read on any thread: a cached prefab buffer is shared and only read.
 */
final class RoomEntities {

    private static final String LOG = "[dungeon] ";

    /** What a prefab entity is to the room logic. */
    enum Kind {
        /** A trigger volume ({@code TriggerVolume}): logic, held by the world's volume manager. */
        VOLUME,
        /** A trigger volume group ({@code TriggerVolumeGroup}): logic, held by the world's volume manager. */
        GROUP,
        /** A point ({@code Point}): logic, held by the world's point manager. */
        POINT,
        /** An encounter ({@code EncounterManager}): logic, an entity of the world. */
        ENCOUNTER,
        /** Anything else the piece holds (spawn and patrol markers, an NPC, a prop): an entity, kept as Keep says. */
        PIECE
    }

    /**
     * One entity of a room prefab.
     *
     * @param kind     what it is
     * @param holder   the stored holder (never changed; clone it to place it)
     * @param position where it stands, anchor-relative, unturned
     * @param yaw      its own yaw, in radians
     * @param json     a volume's, group's or point's component as the engine encodes it; null for an entity
     */
    record Entity(@Nonnull Kind kind, @Nonnull Holder<EntityStore> holder, @Nonnull Vector3d position, float yaw,
                  @Nullable BsonDocument json) {
    }

    private RoomEntities() {
    }

    /** Every entity of {@code buffer} with a place, in the buffer's order; one that cannot be read is skipped. */
    @Nonnull
    static List<Entity> read(@Nonnull IPrefabBuffer buffer, @Nonnull String label) {
        Types types = Types.live();
        List<Entity> out = new ArrayList<>();
        buffer.forEachEntity((x, z, holders, ignored) -> {
            if (holders == null) {
                return;
            }
            for (Holder<EntityStore> holder : holders) {
                if (holder == null) {
                    continue;
                }
                try {
                    Entity entity = entityOf(holder, types);
                    if (entity != null) {
                        out.add(entity);
                    }
                } catch (Throwable t) {
                    SafeLog.warn(LOG + "an entity of " + label + " could not be read for the room logic; it is left out",
                            t);
                }
            }
        }, null);
        return out;
    }

    @Nullable
    private static Entity entityOf(@Nonnull Holder<EntityStore> holder, @Nonnull Types types) {
        TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return null;
        }
        Vector3d position = new Vector3d(transform.getPosition());
        Rotation3f rotation = transform.getRotation();
        float yaw = rotation == null ? 0f : rotation.yaw();
        TriggerVolume volume = types.volume == null ? null : holder.getComponent(types.volume);
        if (volume != null) {
            return new Entity(Kind.VOLUME, holder, position, yaw,
                    TriggerVolume.CODEC.encode(volume, new ExtraInfo()).asDocument());
        }
        TriggerVolumeGroup group = types.group == null ? null : holder.getComponent(types.group);
        if (group != null) {
            return new Entity(Kind.GROUP, holder, position, yaw,
                    TriggerVolumeGroup.CODEC.encode(group, new ExtraInfo()).asDocument());
        }
        Point point = types.point == null ? null : holder.getComponent(types.point);
        if (point != null) {
            return new Entity(Kind.POINT, holder, position, yaw,
                    Point.CODEC.encode(point, new ExtraInfo()).asDocument());
        }
        if (types.encounter != null && holder.getComponent(types.encounter) != null) {
            return new Entity(Kind.ENCOUNTER, holder, position, yaw, null);
        }
        return new Entity(Kind.PIECE, holder, position, yaw, null);
    }

    /** The component types a prefab entity is told apart by; null for a plugin this server does not run. */
    private record Types(@Nullable ComponentType<EntityStore, TriggerVolume> volume,
                         @Nullable ComponentType<EntityStore, TriggerVolumeGroup> group,
                         @Nullable ComponentType<EntityStore, Point> point,
                         @Nullable ComponentType<EntityStore, EncounterManager> encounter) {

        @Nonnull
        static Types live() {
            ComponentType<EntityStore, TriggerVolume> volume = null;
            ComponentType<EntityStore, TriggerVolumeGroup> group = null;
            ComponentType<EntityStore, Point> point = null;
            ComponentType<EntityStore, EncounterManager> encounter = null;
            try {
                TriggerVolumesPlugin volumes = TriggerVolumesPlugin.get();
                if (volumes != null) {
                    volume = volumes.getTriggerVolumeComponentType();
                    group = volumes.getTriggerVolumeGroupComponentType();
                }
            } catch (Throwable t) {
                SafeLog.fine(LOG + "the trigger volumes cannot be read: " + t.getMessage());
            }
            try {
                PointsPlugin points = PointsPlugin.get();
                if (points != null) {
                    point = points.getPointComponentType();
                }
            } catch (Throwable t) {
                SafeLog.fine(LOG + "the points cannot be read: " + t.getMessage());
            }
            try {
                encounter = EncounterManager.getComponentType();
            } catch (Throwable t) {
                SafeLog.fine(LOG + "the encounters cannot be read: " + t.getMessage());
            }
            return new Types(volume, group, point, encounter);
        }
    }
}
