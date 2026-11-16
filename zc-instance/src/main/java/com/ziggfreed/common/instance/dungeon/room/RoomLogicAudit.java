package com.ziggfreed.common.instance.dungeon.room;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabBufferUtil;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan;
import com.ziggfreed.common.instance.dungeon.layout.RoomCatalogue;
import com.ziggfreed.common.instance.dungeon.layout.RoomSpec;
import com.ziggfreed.common.instance.dungeon.layout.asset.PrefabPoolIndex;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * What a room's logic must not do once it is placed in a generated run, found by the boot audit:
 *
 * <ul>
 *   <li>{@code LOGIC_WORLD_ABSOLUTE} (ERROR): a volume writes to a world-absolute position (an {@code Origin} of
 *       {@code WorldAbsolute}, a {@code SetWorldSpawn} with no {@code Origin}, a {@code Teleport} anchored on neither
 *       the volume nor the entity within the same world). Every copy of the room, wherever the run puts it, would
 *       write into the one spot its author stood in.</li>
 *   <li>{@code LOGIC_WORLD_WIDE} (WARNING): a volume reaches past its room: a {@code MatchKey} or {@code MatchTag}
 *       with no {@code @room} or {@code @run} suffix, at a {@code Radius} of 0 (the engine's own default for
 *       {@code TriggerNpcMarkers}, 50 for the rest), reaches every volume of the world carrying it; a
 *       {@code TagCondition} whose {@code Source} is {@code Radius} with no {@code MatchKey} reads every volume within
 *       its {@code Radius}, every one of the world at 0. Every party in the copy, and every other copy of the room,
 *       is heard or read.</li>
 * </ul>
 *
 * <p>The checks are pure over a volume's or group's encoded JSON ({@link #worldAbsoluteWrites},
 * {@link #worldWideKeys}, {@link #unkeyedRadiusReads}); {@link #audit} reads the logic of every room a layout plan may place: its {@code Overlay}'s
 * entities when it has one, else every variant of its {@code Prefab}.
 */
public final class RoomLogicAudit {

    /** The domain every finding is filed under. */
    public static final String DOMAIN = "dungeon";

    public static final String LOGIC_WORLD_ABSOLUTE = "LOGIC_WORLD_ABSOLUTE";
    public static final String LOGIC_WORLD_WIDE = "LOGIC_WORLD_WIDE";

    /** The arrays of a volume or group whose elements carry a {@code Type}. */
    private static final String[] PARTS = {"Effects", "RejectionEffects", "Conditions", "Rules"};

    /** The engine's {@code Radius} when an element leaves it out: 50 for every Type with one but this. */
    private static final double DEFAULT_RADIUS = 50.0;

    private RoomLogicAudit() {
    }

    /** The {@code Type} of each element of {@code holder} that writes to a world-absolute position, in order. */
    @Nonnull
    public static List<String> worldAbsoluteWrites(@Nonnull BsonDocument holder) {
        List<String> out = new ArrayList<>();
        for (BsonDocument element : parts(holder)) {
            String type = String.valueOf(RoomTurns.str(element, "Type"));
            String origin = RoomTurns.str(element, "Origin");
            boolean absolute = "WorldAbsolute".equals(origin)
                    || "WorldAbsolute".equals(RoomTurns.str(element, "PositionSource"))
                    || ("SetWorldSpawn".equals(type) && origin == null);
            if ("Teleport".equals(type) && element.get("Position") != null
                    && !RoomTurns.bool(element, "RelativeToVolume") && !RoomTurns.bool(element, "RelativeToEntity")) {
                String world = RoomTurns.str(element, "World");
                absolute |= world == null || world.isBlank();
            }
            if (absolute) {
                out.add(type);
            }
        }
        return out;
    }

    /** Each key of {@code holder}'s elements that reaches every volume of the world carrying it, in order. */
    @Nonnull
    public static List<String> worldWideKeys(@Nonnull BsonDocument holder) {
        List<String> out = new ArrayList<>();
        for (BsonDocument element : parts(holder)) {
            String key = RoomTurns.str(element, "MatchKey");
            if (key == null || key.isBlank()) {
                key = RoomTurns.str(element, "MatchTag");
            }
            if (key == null || key.isBlank() || RoomKeys.isScoped(key)) {
                continue;
            }
            BsonValue radius = element.get("Radius");
            double reach = radius != null && radius.isNumber() ? radius.asNumber().doubleValue()
                    : "TriggerNpcMarkers".equals(RoomTurns.str(element, "Type")) ? 0.0 : DEFAULT_RADIUS;
            if (reach <= 0) {
                out.add(key.trim());
            }
        }
        return out;
    }

    /**
     * How many of {@code holder}'s {@code TagCondition}s read other volumes' tags with no {@code MatchKey}: a
     * {@code Source} of {@code Radius} with none reads every volume within its {@code Radius}, whatever room or party
     * it belongs to, and every volume of the world at 0 ({@code TagCondition.collectRadiusTargets}).
     */
    public static int unkeyedRadiusReads(@Nonnull BsonDocument holder) {
        int out = 0;
        for (BsonDocument element : parts(holder)) {
            String key = RoomTurns.str(element, "MatchKey");
            if ("TagCondition".equals(RoomTurns.str(element, "Type"))
                    && "Radius".equals(RoomTurns.str(element, "Source")) && (key == null || key.isBlank())) {
                out++;
            }
        }
        return out;
    }

    // ==================== the engine walk ====================

    /**
     * The logic findings of every usable room some plan of {@code plans} may place (it carries the plan's room tags):
     * its {@code Overlay}'s entities, else those of every variant of its {@code Prefab}, each finding once per room.
     * Loads the prefabs through the engine's shared cache; never throws.
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull RoomCatalogue catalogue, @Nonnull PrefabPoolIndex pools,
            @Nonnull Collection<LayoutPlan> plans) {
        List<Finding> out = new ArrayList<>();
        for (RoomSpec room : catalogue.rooms()) {
            if (!catalogue.usable(room) || !placeable(room, plans)) {
                continue;
            }
            try {
                auditRoom(room, pools, out);
            } catch (Throwable t) {
                SafeLog.warn("[dungeon] the logic of the room " + room.id() + " could not be audited", t);
            }
        }
        return out;
    }

    private static boolean placeable(@Nonnull RoomSpec room, @Nonnull Collection<LayoutPlan> plans) {
        for (LayoutPlan plan : plans) {
            if (plan != null && room.carriesAll(plan.roomTags())) {
                return true;
            }
        }
        return false;
    }

    private static void auditRoom(@Nonnull RoomSpec room, @Nonnull PrefabPoolIndex pools, @Nonnull List<Finding> out) {
        List<Path> sources = room.overlay() != null ? firstOf(pools.paths(room.overlay())) : pools.paths(room.prefab());
        Set<String> absolute = new LinkedHashSet<>();
        Set<String> wide = new LinkedHashSet<>();
        for (Path source : sources) {
            for (RoomEntities.Entity entity : RoomEntities.read(PrefabBufferUtil.getCached(source), source.toString())) {
                if (entity.json() == null || entity.kind() == RoomEntities.Kind.POINT) {
                    continue;
                }
                String name = nameOf(entity.json());
                for (String type : worldAbsoluteWrites(entity.json())) {
                    absolute.add(name + " (" + type + ")");
                }
                for (String key : worldWideKeys(entity.json())) {
                    wide.add(name + " (the key '" + key + "' at Radius 0)");
                }
                if (unkeyedRadiusReads(entity.json()) > 0) {
                    wide.add(name + " (a TagCondition reading every volume in its Radius, with no MatchKey)");
                }
            }
        }
        if (!absolute.isEmpty()) {
            out.add(Finding.error(DOMAIN, LOGIC_WORLD_ABSOLUTE, "Its logic writes to a world-absolute position in "
                    + String.join(", ", absolute) + ": every copy of the room would write into that one spot, wherever "
                    + "a run puts it. Anchor it on the volume (Origin VolumeOrigin, a Teleport RelativeToVolume).",
                    room.id()));
        }
        if (!wide.isEmpty()) {
            out.add(Finding.warning(DOMAIN, LOGIC_WORLD_WIDE, "Its logic reaches past its room in "
                    + String.join(", ", wide) + ", so every party in the copy, and every other copy of the room, is "
                    + "heard or read. End a key with @room or @run, give it a Radius, and give a Radius TagCondition a "
                    + "MatchKey ending @room.", room.id()));
        }
    }

    @Nonnull
    private static List<Path> firstOf(@Nonnull List<Path> paths) {
        return paths.isEmpty() ? List.of() : List.of(paths.get(0));
    }

    @Nonnull
    private static String nameOf(@Nonnull BsonDocument holder) {
        String name = RoomTurns.str(holder, "Name");
        return name == null || name.isBlank() ? "a volume" : "'" + name.trim() + "'";
    }

    @Nonnull
    private static List<BsonDocument> parts(@Nonnull BsonDocument holder) {
        List<BsonDocument> out = new ArrayList<>();
        for (String key : PARTS) {
            BsonArray list = RoomTurns.array(holder, key);
            if (list == null) {
                continue;
            }
            for (BsonValue element : list) {
                if (element.isDocument()) {
                    out.add(element.asDocument());
                }
            }
        }
        return out;
    }
}
