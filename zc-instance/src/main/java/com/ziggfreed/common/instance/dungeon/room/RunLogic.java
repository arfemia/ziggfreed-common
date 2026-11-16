package com.ziggfreed.common.instance.dungeon.room;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.joml.Vector3d;

import com.hypixel.hytale.builtin.points.PointsPlugin;
import com.hypixel.hytale.builtin.points.component.Point;
import com.hypixel.hytale.builtin.points.manager.PointEntry;
import com.hypixel.hytale.builtin.points.manager.PointManager;
import com.hypixel.hytale.builtin.triggervolumes.TriggerVolumesPlugin;
import com.hypixel.hytale.builtin.triggervolumes.component.TriggerVolume;
import com.hypixel.hytale.builtin.triggervolumes.component.TriggerVolumeGroup;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerRule;
import com.hypixel.hytale.builtin.triggervolumes.effect.builtin.PastePrefabEffect;
import com.hypixel.hytale.builtin.triggervolumes.effect.builtin.TeleportEffect;
import com.hypixel.hytale.builtin.triggervolumes.manager.GroupEntry;
import com.hypixel.hytale.builtin.triggervolumes.manager.TriggerVolumeManager;
import com.hypixel.hytale.builtin.triggervolumes.manager.VolumeEntry;
import com.hypixel.hytale.builtin.triggervolumes.system.DelayedEffectScheduler;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.FromPrefab;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.SpawnLineage;
import com.ziggfreed.common.instance.dungeon.layout.Box;
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.TickingSections;

/**
 * One generated run's room logic: the trigger volumes, groups, points and entities (encounters, and the markers its
 * room files keep) its rooms' prefabs hold, which the painter never places. They are placed room by room as the party
 * nears them and removed together when the run ends.
 *
 * <p><b>Prepared off the world thread</b> ({@link #prepare}, on a painter thread as the run is laid out): each room's
 * entities are read ({@link RoomEntities}), kept as its room file says ({@link #kept}), filtered by the run's variant
 * picks ({@link RoomVariants}), their {@code @room} and {@code @run} keys rewritten to the room's and run's tokens
 * ({@link RoomKeys}), turned with the room ({@link RoomTurns}), and built: a volume as the entry the world's manager
 * registers, its effects turned once by the engine's own {@code rotateInPlace} (but the two {@link RoomTurns} turns in
 * the JSON), an entity as a clone of its holder standing where it lands, with a fresh UUID and {@code FromPrefab}.
 *
 * <p><b>Placed on the copy's world thread</b>: {@link #activate} (the area members' 1 Hz ticker, for the room a member
 * stands in) claims that room and every room one door from it, each once per run, and posts their placement to the
 * world thread: groups and volumes the way the engine's prefab paste registers them (the group links remapped per
 * prefab, a unique name, register, the viewers told, the volume's create event), points through the point manager,
 * entities added once their chunk section ticks ({@code TickingSections}). Each room's placement is timed for the
 * run's {@code [dungeon]} line ({@link #summary}).
 *
 * <p><b>Removed at the run's end</b>: {@link #close} stops every placement still to come, and {@link #remove}, on the
 * world thread, cancels each volume's delayed effects, unregisters it and tells the viewers, drops the groups and
 * points the same way, and removes the entities (one parked in a sleeping section is woken first); off the world
 * thread (the copy has closed) it only forgets them, since everything went with the world.
 */
public final class RunLogic {

    private static final String LOG = "[dungeon] ";
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /** How far above or below a room's box a member still counts as in it (a jump, a floor's thickness). */
    private static final int Y_SLACK = 2;

    /** What has been warned about once already. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * One room of the run as its logic reads it, in world blocks.
     *
     * @param index       its index in the layout
     * @param origin      where its prefab anchor stands
     * @param turns       its quarter turns, 0 to 3 (the engine's {@code PrefabRotation} index)
     * @param box         the blocks it covers
     * @param base        its variant's prefab
     * @param overlay     the overlay painted on its base, or null for none
     * @param keepMarkers whether its room file keeps the base's own markers ({@code Keep.Markers})
     * @param neighbours  the rooms its doors join, by index
     * @param label       what the log calls it
     */
    public record Room(int index, @Nonnull Cell origin, int turns, @Nonnull Box box, @Nonnull IPrefabBuffer base,
                       @Nullable IPrefabBuffer overlay, boolean keepMarkers, @Nonnull List<Integer> neighbours,
                       @Nonnull String label) {

        public Room {
            neighbours = List.copyOf(neighbours);
        }
    }

    /** One prefab's groups and volumes, whose group links remap together. */
    private record Batch(@Nonnull List<GroupPiece> groups, @Nonnull List<VolumePiece> volumes) {
    }

    /** A volume, built: the entry the manager registers, and the prefab group link it joins (null for none). */
    private record VolumePiece(@Nonnull VolumeEntry entry, @Nullable String link) {
    }

    /** A group, decoded, scoped and turned in its JSON; its effects turned by the engine once registered. */
    private record GroupPiece(@Nonnull TriggerVolumeGroup group, @Nonnull Vector3d at, boolean turnEffects) {
    }

    /** A point, decoded and scoped; it takes the room's yaw as it is registered. */
    private record PointPiece(@Nonnull Point point, @Nonnull Vector3d at) {
    }

    /** An entity, cloned and standing where it lands, with a UUID of its own. */
    private record EntityPiece(@Nonnull Holder<EntityStore> holder, @Nonnull UUID uuid, @Nonnull Vector3d at) {
    }

    /** One room's logic, ready to place, and the room's quarter turns (its points and groups take their yaw). */
    private record Prepared(int turns, @Nonnull List<Batch> batches, @Nonnull List<PointPiece> points,
                            @Nonnull List<EntityPiece> entities) {

        static final Prepared NONE = new Prepared(0, List.of(), List.of(), List.of());

        boolean empty() {
            return batches.isEmpty() && points.isEmpty() && entities.isEmpty();
        }
    }

    /** An entity the run placed (or is placing), found again by its UUID. */
    private record Placed(@Nonnull UUID uuid, double x, double y, double z) {
    }

    private final int lane;
    private final int run;
    @Nonnull private final List<Room> rooms;
    /** Each room's logic, by layout index; null for an index the run has no room at. */
    @Nonnull private final Prepared[] prepared;
    private final double prepareMs;
    private final int leftOut;

    private final AtomicBoolean ended = new AtomicBoolean();
    /** Every room whose placement has been claimed, by layout index. */
    private final Set<Integer> claimed = ConcurrentHashMap.newKeySet();

    /** What has been placed, for its removal. Guarded by itself; written on the world thread. */
    private final Records records = new Records();

    private final AtomicInteger roomsPlaced = new AtomicInteger();
    private final AtomicInteger volumesPlaced = new AtomicInteger();
    private final AtomicInteger pointsPlaced = new AtomicInteger();
    private final AtomicInteger entitiesPlaced = new AtomicInteger();
    private final AtomicLong placeNanos = new AtomicLong();
    private final AtomicLong maxNanos = new AtomicLong();

    private RunLogic(int lane, int run, @Nonnull List<Room> rooms, @Nonnull Prepared[] prepared, double prepareMs,
            int leftOut) {
        this.lane = lane;
        this.run = run;
        this.rooms = List.copyOf(rooms);
        this.prepared = prepared;
        this.prepareMs = prepareMs;
        this.leftOut = leftOut;
    }

    // ==================== preparing (off the world thread) ====================

    /**
     * Read, keep, pick, scope, turn and build the logic of every room of run {@code run} in lane {@code lane} of the
     * copy {@code worldName}, from the run's {@code seed}. Off the world thread; a room whose logic cannot be read is
     * prepared empty (warned once), so the run still opens.
     */
    @Nonnull
    public static RunLogic prepare(@Nonnull String worldName, int lane, int run, long seed, @Nonnull List<Room> rooms) {
        long start = System.nanoTime();
        int size = 0;
        for (Room room : rooms) {
            size = Math.max(size, room.index() + 1);
        }
        List<List<Source>> read = new ArrayList<>(rooms.size());
        Map<String, Integer> counts = new HashMap<>();
        for (Room room : rooms) {
            List<Source> sources;
            try {
                sources = sourcesOf(room);
            } catch (Throwable t) {
                warnOnce("read:" + room.label(), LOG + "the entities of the room " + room.label() + " could not be "
                        + "read; the room is placed without its logic", t);
                sources = List.of();
            }
            read.add(sources);
            for (Source source : sources) {
                for (RoomEntities.Entity entity : source.entities()) {
                    if (entity.json() != null) {
                        RoomVariants.count(RoomVariants.of(entity.json()), counts);
                    }
                }
            }
        }
        Map<String, Integer> picks = RoomVariants.picks(seed, counts);
        Prepared[] prepared = new Prepared[size];
        int leftOut = 0;
        String world = worldName.toLowerCase(Locale.ROOT);
        for (int i = 0; i < rooms.size(); i++) {
            Room room = rooms.get(i);
            int[] dropped = {0};
            try {
                prepared[room.index()] = build(world, lane, run, room, read.get(i), picks, dropped);
            } catch (Throwable t) {
                warnOnce("prepare:" + room.label(), LOG + "the logic of the room " + room.label() + " could not be "
                        + "prepared; the room is placed without it", t);
                prepared[room.index()] = Prepared.NONE;
            }
            leftOut += dropped[0];
        }
        return new RunLogic(lane, run, rooms, prepared, (System.nanoTime() - start) / NANOS_PER_MILLI, leftOut);
    }

    /** A run with no logic: what a run whose logic could not be prepared at all runs with. */
    @Nonnull
    public static RunLogic none(int lane, int run) {
        return new RunLogic(lane, run, List.of(), new Prepared[0], 0.0, 0);
    }

    /** One prefab of a room and the entities of it the room file keeps. */
    private record Source(@Nonnull List<RoomEntities.Entity> entities) {
    }

    @Nonnull
    private static List<Source> sourcesOf(@Nonnull Room room) {
        boolean hasOverlay = room.overlay() != null;
        List<Source> out = new ArrayList<>(2);
        out.add(new Source(keptOf(RoomEntities.read(room.base(), room.label()), false, hasOverlay,
                room.keepMarkers())));
        if (room.overlay() != null) {
            out.add(new Source(keptOf(RoomEntities.read(room.overlay(), room.label() + " (overlay)"), true, true,
                    room.keepMarkers())));
        }
        return out;
    }

    @Nonnull
    private static List<RoomEntities.Entity> keptOf(@Nonnull List<RoomEntities.Entity> entities, boolean fromOverlay,
            boolean hasOverlay, boolean keepMarkers) {
        List<RoomEntities.Entity> out = new ArrayList<>(entities.size());
        for (RoomEntities.Entity entity : entities) {
            if (kept(entity.kind(), fromOverlay, hasOverlay, keepMarkers)) {
                out.add(entity);
            }
        }
        return out;
    }

    /**
     * Whether a room places one of its prefabs' entities: an overlay's always, being the room's logic; a base's own
     * logic (volumes, groups, points, encounters) only when the room has no overlay; anything else a base holds (spawn
     * and patrol markers, NPCs, props) only when its room file keeps its markers ({@code Keep.Markers}).
     */
    static boolean kept(@Nonnull RoomEntities.Kind kind, boolean fromOverlay, boolean hasOverlay, boolean keepMarkers) {
        if (fromOverlay) {
            return true;
        }
        if (kind == RoomEntities.Kind.PIECE) {
            return keepMarkers;
        }
        return !hasOverlay;
    }

    @Nonnull
    private static Prepared build(@Nonnull String world, int lane, int run, @Nonnull Room room,
            @Nonnull List<Source> sources, @Nonnull Map<String, Integer> picks, @Nonnull int[] dropped) {
        RoomKeys.Tokens tokens = RoomKeys.tokens(lane, run, room.index());
        int turns = room.turns();
        float yaw = RoomTurns.yawRadians(turns);
        List<Batch> batches = new ArrayList<>(sources.size());
        List<PointPiece> points = new ArrayList<>();
        List<EntityPiece> entities = new ArrayList<>();
        for (Source source : sources) {
            List<GroupPiece> groups = new ArrayList<>();
            List<VolumePiece> volumes = new ArrayList<>();
            for (RoomEntities.Entity entity : source.entities()) {
                if (entity.json() != null && !RoomVariants.keeps(RoomVariants.of(entity.json()), picks)) {
                    dropped[0]++;
                    continue;
                }
                Vector3d at = placed(room, entity.position());
                try {
                    switch (entity.kind()) {
                        case VOLUME -> volumes.add(volume(world, entity.json(), tokens, turns, yaw, at));
                        case GROUP -> groups.add(group(entity.json(), tokens, turns, at));
                        case POINT -> points.add(new PointPiece(Point.CODEC.decode(
                                RoomKeys.rewrite(entity.json(), tokens), new ExtraInfo()), at));
                        case ENCOUNTER, PIECE -> entities.add(entity(entity, yaw, at));
                    }
                } catch (Throwable t) {
                    warnOnce("piece:" + room.label() + ":" + entity.kind(), LOG + "a " + entity.kind().name()
                            .toLowerCase(Locale.ROOT) + " of the room " + room.label() + " could not be prepared; it "
                            + "is left out", t);
                }
            }
            if (!groups.isEmpty() || !volumes.isEmpty()) {
                batches.add(new Batch(List.copyOf(groups), List.copyOf(volumes)));
            }
        }
        Prepared out = new Prepared(turns, List.copyOf(batches), List.copyOf(points), List.copyOf(entities));
        return out.empty() ? Prepared.NONE : out;
    }

    /** Where an entity at {@code position} of the room's own frame stands once the room is placed and turned. */
    @Nonnull
    private static Vector3d placed(@Nonnull Room room, @Nonnull Vector3d position) {
        Cell origin = room.origin();
        return new Vector3d(origin.x() + RoomTurns.placedX(position.x, position.z, room.turns()),
                origin.y() + position.y, origin.z() + RoomTurns.placedZ(position.x, position.z, room.turns()));
    }

    @Nonnull
    private static VolumePiece volume(@Nonnull String world, @Nullable BsonDocument json, @Nonnull RoomKeys.Tokens tokens,
            int turns, float yaw, @Nonnull Vector3d at) {
        BsonDocument scoped = RoomKeys.rewrite(requireJson(json), tokens);
        RoomTurns.turnVolume(scoped, turns);
        TriggerVolume volume = TriggerVolume.CODEC.decode(scoped, new ExtraInfo());
        // The shape is turned in the JSON already: the entry is built unturned, then its effects turned once.
        VolumeEntry entry = volume.toVolumeEntry(UUID.randomUUID().toString(), world, new Vector3d(at), 0f);
        if (turns != 0 && entry.isRotateEffectsOnPaste()) {
            Vector3d origin = new Vector3d(at);
            turnEffects(entry.getEffects(), yaw, origin);
            turnEffects(entry.getRejectionEffects(), yaw, origin);
            turnConditions(entry.getConditions(), yaw, origin);
            turnRules(entry.getRules(), yaw, origin);
        }
        String link = volume.getGroupLinkId();
        return new VolumePiece(entry, link == null || link.isBlank() ? null : link);
    }

    @Nonnull
    private static GroupPiece group(@Nullable BsonDocument json, @Nonnull RoomKeys.Tokens tokens, int turns,
            @Nonnull Vector3d at) {
        BsonDocument scoped = RoomKeys.rewrite(requireJson(json), tokens);
        boolean turnEffects = turns != 0 && RoomTurns.rotatesEffects(scoped);
        if (turnEffects) {
            RoomTurns.turnEffects(scoped, turns);
        }
        return new GroupPiece(TriggerVolumeGroup.CODEC.decode(scoped, new ExtraInfo()), at, turnEffects);
    }

    @Nonnull
    private static EntityPiece entity(@Nonnull RoomEntities.Entity entity, float yaw, @Nonnull Vector3d at) {
        Holder<EntityStore> holder = entity.holder().clone();
        TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
        if (transform == null) {
            throw new IllegalStateException("the entity has no Transform");
        }
        transform.setPosition(new Vector3d(at));
        transform.setRotation(turned(transform.getRotation(), yaw));
        HeadRotation head = holder.getComponent(HeadRotation.getComponentType());
        if (head != null) {
            head.setRotation(turned(head.getRotation(), yaw));
        }
        // The spawn markers' reset on add reads FromPrefab; every placed copy takes a UUID of its own (two copies of
        // one room hold the same stored UUIDs, and the store drops a second entity with one it already has).
        holder.ensureComponent(FromPrefab.getComponentType());
        UUIDComponent uuid = UUIDComponent.randomUUID();
        holder.putComponent(UUIDComponent.getComponentType(), uuid);
        if (entity.kind() == RoomEntities.Kind.ENCOUNTER) {
            // An encounter mints its lineage when built on add, unless it carries one: never share a stored one.
            try {
                holder.tryRemoveComponent(SpawnLineage.getComponentType());
            } catch (Throwable t) {
                SafeLog.fine(LOG + "an encounter's stored lineage could not be read: " + t.getMessage());
            }
        }
        return new EntityPiece(holder, uuid.getUuid(), new Vector3d(at));
    }

    @Nonnull
    private static Rotation3f turned(@Nullable Rotation3f rotation, float yaw) {
        return rotation == null ? new Rotation3f(0f, yaw, 0f)
                : new Rotation3f(rotation.pitch(), rotation.yaw() + yaw, rotation.roll());
    }

    @Nonnull
    private static BsonDocument requireJson(@Nullable BsonDocument json) {
        if (json == null) {
            throw new IllegalStateException("no encoded component");
        }
        return json;
    }

    /** Turn each effect the engine turns rightly ({@code rotateInPlace}); a Teleport and a PastePrefab are turned. */
    private static void turnEffects(@Nonnull List<TriggerEffect> effects, float yaw, @Nonnull Vector3d origin) {
        for (TriggerEffect effect : effects) {
            if (effect != null && !(effect instanceof TeleportEffect) && !(effect instanceof PastePrefabEffect)) {
                effect.rotateInPlace(yaw, origin);
            }
        }
    }

    private static void turnConditions(@Nonnull List<TriggerCondition> conditions, float yaw,
            @Nonnull Vector3d origin) {
        for (TriggerCondition condition : conditions) {
            if (condition != null) {
                condition.rotateInPlace(yaw, origin);
            }
        }
    }

    private static void turnRules(@Nonnull List<TriggerRule> rules, float yaw, @Nonnull Vector3d origin) {
        for (TriggerRule rule : rules) {
            if (rule != null) {
                rule.rotateInPlace(yaw, origin);
            }
        }
    }

    // ==================== where a member stands (the members' ticker) ====================

    /**
     * The layout index of the room holding the world position {@code (x, y, z)}: {@code hint} first (the room the
     * member stood in last), then every room, then the same with {@value #Y_SLACK} blocks of slack above and below a
     * box; -1 when no room holds it.
     */
    public int roomAt(double x, double y, double z, int hint) {
        Cell cell = new Cell((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        Room known = roomOf(hint);
        if (known != null && known.box().contains(cell)) {
            return hint;
        }
        for (Room room : rooms) {
            if (room.box().contains(cell)) {
                return room.index();
            }
        }
        for (Room room : rooms) {
            Box box = room.box();
            if (cell.x() >= box.min().x() && cell.x() <= box.max().x() && cell.z() >= box.min().z()
                    && cell.z() <= box.max().z() && cell.y() >= box.min().y() - Y_SLACK
                    && cell.y() <= box.max().y() + Y_SLACK) {
                return room.index();
            }
        }
        return -1;
    }

    @Nullable
    private Room roomOf(int index) {
        for (Room room : rooms) {
            if (room.index() == index) {
                return room;
            }
        }
        return null;
    }

    // ==================== placing (the copy's world thread) ====================

    /**
     * A member stands in room {@code room}: place its logic and that of every room one door from it, each the first
     * time it is asked for in the run, on {@code world}'s thread. From any thread; nothing once the run has ended.
     */
    public void activate(@Nonnull World world, int room) {
        Room here = roomOf(room);
        if (here == null || ended.get()) {
            return;
        }
        List<Integer> due = new ArrayList<>();
        claim(room, due);
        for (int next : here.neighbours()) {
            claim(next, due);
        }
        if (due.isEmpty()) {
            return;
        }
        try {
            world.execute(() -> place(world, due));
        } catch (Throwable refused) {
            // The copy takes no more tasks: it is closing, and its run's logic goes with it.
            SafeLog.fine(LOG + "the room logic of run " + run + " in lane " + lane + " was not placed: "
                    + refused.getMessage());
        }
    }

    /** Claim room {@code index} for placement, once per run; a room with no logic is claimed and left out. */
    private void claim(int index, @Nonnull List<Integer> due) {
        if (index >= 0 && index < prepared.length && prepared[index] != null && claimed.add(index)
                && !prepared[index].empty()) {
            due.add(index);
        }
    }

    /** On the world thread: place each room of {@code due}, timed, unless the run has ended meanwhile. */
    private void place(@Nonnull World world, @Nonnull List<Integer> due) {
        for (int index : due) {
            if (ended.get()) {
                return;
            }
            Prepared logic = prepared[index];
            long start = System.nanoTime();
            try {
                placeRoom(world, logic);
            } catch (Throwable t) {
                warnOnce("place:" + index, LOG + "the logic of room " + index + " of run " + run + " in lane " + lane
                        + " could not be placed whole; what was placed is removed with the run", t);
            }
            long took = System.nanoTime() - start;
            roomsPlaced.incrementAndGet();
            placeNanos.addAndGet(took);
            maxNanos.accumulateAndGet(took, Math::max);
        }
    }

    private void placeRoom(@Nonnull World world, @Nonnull Prepared logic) {
        if (logic.empty()) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        String worldName = world.getName().toLowerCase(Locale.ROOT);
        float yaw = RoomTurns.yawRadians(logic.turns());
        if (!logic.batches().isEmpty()) {
            TriggerVolumeManager manager = volumeManager(store);
            if (manager != null) {
                for (Batch batch : logic.batches()) {
                    placeBatch(manager, worldName, batch, yaw);
                }
                manager.clearPrefabGroupLinkRemap();
                manager.markSpatialDirty();
            }
        }
        if (!logic.points().isEmpty()) {
            PointManager points = pointManager(store);
            if (points != null) {
                for (PointPiece piece : logic.points()) {
                    String id = points.generateUniquePointId();
                    PointEntry entry = piece.point().toPointEntry(id, worldName, new Vector3d(piece.at()), yaw);
                    points.register(id, entry);
                    points.notifyViewersAdd(entry);
                    records.point(id);
                    pointsPlaced.incrementAndGet();
                }
                points.markSpatialDirty();
            }
        }
        for (EntityPiece piece : logic.entities()) {
            records.entity(new Placed(piece.uuid(), piece.at().x, piece.at().y, piece.at().z));
            TickingSections.whenTicking(world, piece.at().x, piece.at().y, piece.at().z, () -> add(world, piece),
                    reason -> SafeLog.fine(LOG + "a room entity of run " + run + " in lane " + lane
                            + " was not placed: " + reason));
        }
    }

    private void placeBatch(@Nonnull TriggerVolumeManager manager, @Nonnull String worldName, @Nonnull Batch batch,
            float yaw) {
        // Each prefab's links name its own groups: a fresh remap, as the engine's paste clears one per paste.
        manager.clearPrefabGroupLinkRemap();
        for (GroupPiece piece : batch.groups()) {
            String link = piece.group().getGroupLinkId();
            if (link == null || link.isBlank()) {
                continue;
            }
            String groupId = manager.upsertGroupForPrefabLink(link, piece.group(), worldName,
                    new Vector3d(piece.at()), 0f);
            records.group(groupId);
            GroupEntry group = manager.getGroup(groupId);
            if (group != null && piece.turnEffects()) {
                Vector3d origin = new Vector3d(group.getOrigin());
                turnEffects(group.getEffects(), yaw, origin);
                turnEffects(group.getRejectionEffects(), yaw, origin);
                turnConditions(group.getConditions(), yaw, origin);
                turnRules(group.getRules(), yaw, origin);
            }
        }
        for (VolumePiece piece : batch.volumes()) {
            VolumeEntry entry = piece.entry();
            String id = entry.getId();
            if (manager.hasVolume(id)) {
                warnOnce("volume-id:" + id, LOG + "a room volume's fresh id " + id + " is taken; it is left out", null);
                continue;
            }
            entry.setName(manager.makeUniqueVolumeName(entry.getName(), null));
            if (piece.link() != null) {
                String groupId = manager.ensureGroupForPrefabLink(piece.link(), entry, worldName);
                entry.setGroupId(groupId);
                GroupEntry group = manager.getGroup(groupId);
                if (group != null) {
                    group.addMember(id);
                }
                records.group(groupId);
            }
            manager.register(id, entry);
            manager.notifyViewersAdd(entry);
            manager.enqueueVolumeCreateEvent(entry, null, TriggerVolumeManager.ENVIRONMENT_ACTOR_UUID);
            records.volume(id);
            volumesPlaced.incrementAndGet();
        }
    }

    /** On the world thread, its section ticking: add one entity, unless the run ended while it waited. */
    private void add(@Nonnull World world, @Nonnull EntityPiece piece) {
        if (ended.get()) {
            return;
        }
        try {
            Ref<EntityStore> ref = world.getEntityStore().getStore().addEntity(piece.holder(), AddReason.LOAD);
            if (ref != null) {
                entitiesPlaced.incrementAndGet();
            }
        } catch (Throwable t) {
            warnOnce("entity:" + lane + ":" + run, LOG + "a room entity of run " + run + " in lane " + lane
                    + " could not be added", t);
        }
    }

    // ==================== removing (the run's end) ====================

    /** Whether the run has ended: nothing more is placed. */
    public boolean ended() {
        return ended.get();
    }

    /**
     * The run ended: nothing more is placed from now on, whatever is still queued. True the first time, when the
     * caller then hands {@link #remove} to the copy's world thread. Lock-free, so a caller may hold its own lock.
     */
    public boolean close() {
        return ended.compareAndSet(false, true);
    }

    /**
     * Remove everything the run placed. On {@code world}'s thread: each volume's delayed effects cancelled, the volume
     * unregistered and the viewers told; the groups left over dropped; each point unregistered and the viewers told;
     * each entity removed, one parked in a sleeping section woken first. Anywhere else (the copy has closed and runs
     * the task where it was queued) it only forgets them. Closes the run first; never throws.
     */
    public void remove(@Nonnull World world) {
        close();
        Records.Taken taken = records.take();
        if (taken.empty()) {
            return;
        }
        try {
            if (!world.isAlive() || !world.isInThread()) {
                return;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            long start = System.nanoTime();
            int volumes = removeVolumes(store, taken);
            int points = removePoints(store, taken.points());
            int[] entities = removeEntities(world, taken.entities());
            SafeLog.info(String.format(Locale.ROOT, LOG + "run %d of %s lane %d: its room logic is removed (%d volumes,"
                            + " %d points, %d entities, %d of them woken to be removed) in %.2f ms", run,
                    world.getName(), lane, volumes, points, entities[0], entities[1],
                    (System.nanoTime() - start) / NANOS_PER_MILLI));
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the room logic of run " + run + " in lane " + lane + " could not be removed whole", t);
        }
    }

    /** The copy closed: forget everything, which went with its world. From any thread. */
    public void forget() {
        close();
        records.take();
    }

    private int removeVolumes(@Nonnull Store<EntityStore> store, @Nonnull Records.Taken taken) {
        if (taken.volumes().isEmpty() && taken.groups().isEmpty()) {
            return 0;
        }
        TriggerVolumeManager manager = volumeManager(store);
        if (manager == null) {
            return 0;
        }
        DelayedEffectScheduler delayed = manager.getDelayedEffectScheduler();
        int removed = 0;
        for (String id : taken.volumes()) {
            VolumeEntry entry = manager.getVolume(id);
            if (entry == null) {
                continue;
            }
            if (delayed != null) {
                delayed.cancelForVolume(entry);
            }
            manager.unregister(id);
            manager.notifyViewersRemove(id);
            removed++;
        }
        for (String groupId : taken.groups()) {
            if (manager.hasGroup(groupId)) {
                manager.unregisterGroup(groupId);
            }
        }
        manager.markSpatialDirty();
        return removed;
    }

    private int removePoints(@Nonnull Store<EntityStore> store, @Nonnull List<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        PointManager points = pointManager(store);
        if (points == null) {
            return 0;
        }
        for (String id : ids) {
            points.unregister(id);
            points.notifyViewersRemove(id);
        }
        points.markSpatialDirty();
        return ids.size();
    }

    /** Remove each entity: {removed now, woken to be removed}. */
    @Nonnull
    private int[] removeEntities(@Nonnull World world, @Nonnull List<Placed> placed) {
        int[] counts = {0, 0};
        for (Placed entity : placed) {
            if (removeNow(world, entity.uuid())) {
                counts[0]++;
                continue;
            }
            if (TickingSections.stateAt(world, entity.x(), entity.y(), entity.z()) == TickingSections.State.PARKING) {
                // Parked in a sleeping section: a wake brings it back into the store, where it is removed.
                counts[1]++;
                TickingSections.whenTicking(world, entity.x(), entity.y(), entity.z(),
                        () -> removeNow(world, entity.uuid()),
                        reason -> SafeLog.fine(LOG + "a parked room entity of run " + run + " was not removed: "
                                + reason));
            }
        }
        return counts;
    }

    private static boolean removeNow(@Nonnull World world, @Nonnull UUID uuid) {
        EntityStore entities = world.getEntityStore();
        Ref<EntityStore> ref = entities.getRefFromUUID(uuid);
        if (ref == null || !ref.isValid()) {
            return false;
        }
        entities.getStore().removeEntity(ref, RemoveReason.REMOVE);
        return true;
    }

    // ==================== the run's line ====================

    /** The run's room logic for its {@code [dungeon]} line: how long preparing took, and placing per room. */
    @Nonnull
    public String summary() {
        int withLogic = 0;
        for (Prepared logic : prepared) {
            if (logic != null && !logic.empty()) {
                withLogic++;
            }
        }
        int placedRooms = roomsPlaced.get();
        double totalMs = placeNanos.get() / NANOS_PER_MILLI;
        return String.format(Locale.ROOT, "room logic prepared in %.2f ms (%d left out by variant) and placed in %d of"
                        + " the %d rooms holding any, in %.2f ms (%.3f ms a room, %.3f ms at most; %d volumes, %d points,"
                        + " %d entities)", prepareMs, leftOut, placedRooms, withLogic, totalMs,
                placedRooms == 0 ? 0.0 : totalMs / placedRooms, maxNanos.get() / NANOS_PER_MILLI,
                volumesPlaced.get(), pointsPlaced.get(), entitiesPlaced.get());
    }

    // ==================== internals ====================

    @Nullable
    private static TriggerVolumeManager volumeManager(@Nonnull Store<EntityStore> store) {
        TriggerVolumesPlugin plugin = TriggerVolumesPlugin.get();
        return plugin == null ? null : store.getResource(plugin.getManagerResourceType());
    }

    @Nullable
    private static PointManager pointManager(@Nonnull Store<EntityStore> store) {
        PointsPlugin plugin = PointsPlugin.get();
        return plugin == null ? null : store.getResource(plugin.getManagerResourceType());
    }

    private static void warnOnce(@Nonnull String key, @Nonnull String line, @Nullable Throwable t) {
        if (WARNED.add(key.toLowerCase(Locale.ROOT))) {
            if (t == null) {
                SafeLog.warn(line);
            } else {
                SafeLog.warn(line, t);
            }
        }
    }

    /** What a run has placed, for its removal. Written on the world thread; taken once, from any thread. */
    private static final class Records {
        private final List<String> volumes = new ArrayList<>();
        private final List<String> groups = new ArrayList<>();
        private final List<String> points = new ArrayList<>();
        private final List<Placed> entities = new ArrayList<>();

        record Taken(@Nonnull List<String> volumes, @Nonnull List<String> groups, @Nonnull List<String> points,
                     @Nonnull List<Placed> entities) {

            boolean empty() {
                return volumes.isEmpty() && groups.isEmpty() && points.isEmpty() && entities.isEmpty();
            }
        }

        synchronized void volume(@Nonnull String id) {
            volumes.add(id);
        }

        synchronized void group(@Nonnull String id) {
            if (!groups.contains(id)) {
                groups.add(id);
            }
        }

        synchronized void point(@Nonnull String id) {
            points.add(id);
        }

        synchronized void entity(@Nonnull Placed placed) {
            entities.add(placed);
        }

        /** Everything recorded, and forget it. */
        @Nonnull
        synchronized Taken take() {
            Taken taken = new Taken(List.copyOf(volumes), List.copyOf(groups), List.copyOf(points),
                    List.copyOf(entities));
            volumes.clear();
            groups.clear();
            points.clear();
            entities.clear();
            return taken;
        }
    }
}
