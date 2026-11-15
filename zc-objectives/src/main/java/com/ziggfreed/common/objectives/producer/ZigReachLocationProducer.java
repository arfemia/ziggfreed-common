package com.ziggfreed.common.objectives.producer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.progress.LocationTarget;
import com.ziggfreed.common.progress.ObjectiveIndex;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;

/**
 * Fires {@code REACH_LOCATION} when a player walks into a place a quest step or an achievement criterion names:
 * the one producer of the kind, so arriving counts once for every reader of it.
 *
 * <p>Once a second it reads the distinct targets of the kind off both shared engines' own indexes (quests and
 * achievements, whoever folded them) and checks each player against each target's sphere
 * ({@link LocationTarget}: {@code x,y,z[,radius]} or {@code world:x,y,z[,radius]}), in the player's own world. The
 * moment goes out on ENTRY, the Target the authored string itself, so a step matches it whatever its match mode;
 * a player standing inside fires nothing more until they leave and come back. With no step of the kind anywhere
 * it costs one index lookup per player a second. A blank or malformed target never fires (the validators report
 * it). Who is inside what is transient and per process, dropped when the player leaves the server.
 */
public final class ZigReachLocationProducer extends DelayedEntitySystem<EntityStore> {

    /** The objective kind this producer feeds. */
    public static final String KIND = LocationTarget.KIND;

    /** Authored target to its parse; pure, so it never goes stale. A malformed target maps to {@link #UNPARSEABLE}. */
    private static final Map<String, LocationTarget> PARSED = new ConcurrentHashMap<>();

    /** The parse of a malformed target, kept so it is parsed once. */
    private static final LocationTarget UNPARSEABLE = new LocationTarget(null, 0, 0, 0, 1);

    /** Per player, the authored targets they are standing in now (the entry edge). */
    private static final Map<UUID, Set<String>> INSIDE = new ConcurrentHashMap<>();

    public ZigReachLocationProducer() {
        super(1.0F);
    }

    /** Register the check and the departure cleanup. Registration only, from {@code ProgressionDefaults.install}. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new ZigReachLocationProducer());
        plugin.getEventRegistry().register(PlayerDisconnectEvent.class, ZigReachLocationProducer::onDisconnect);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            List<String> targets = targets();
            if (!targets.isEmpty()) {
                check(chunk.getReferenceTo(index), store, commandBuffer, targets);
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] reach-location check failed", t);
        }
    }

    private static void check(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull List<String> targets) {
        if (!ref.isValid()) {
            return;
        }
        PlayerRef player = PlayerAccess.playerRef(store, ref);
        UUID playerId = player == null ? null : player.getUuid();
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (playerId == null || transform == null) {
            return;
        }
        Vector3d pos = transform.getPosition();
        World world = store.getExternalData().getWorld();
        String worldName = world == null ? null : world.getName();
        Set<String> inside = INSIDE.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet());
        for (String raw : targets) {
            LocationTarget target = parsed(raw);
            if (target == UNPARSEABLE || target.excludesWorld(worldName)) {
                continue;
            }
            if (!target.contains(pos.x(), pos.y(), pos.z())) {
                inside.remove(raw);
            } else if (inside.add(raw)) {
                ProgressDispatch.fire(store, ref, commandBuffer, KIND, raw, null, 1L, null);
            }
        }
    }

    /**
     * The distinct targets the kind is authored with, quests first, read off the shared engines' indexes. Empty
     * before the shared runtime is built (a player's ready builds it), so a tick never builds it early.
     */
    @Nonnull
    static List<String> targets() {
        if (!ProgressionRuntime.isBuilt()) {
            return List.of();
        }
        List<ObjectiveIndex.Entry> quests = ProgressionRuntime.quests().index().forKind(KIND);
        List<ObjectiveIndex.Entry> achievements = ProgressionRuntime.achievements().index().forKind(KIND);
        if (quests.isEmpty() && achievements.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        addTargets(quests, distinct);
        addTargets(achievements, distinct);
        return new ArrayList<>(distinct);
    }

    private static void addTargets(@Nonnull List<ObjectiveIndex.Entry> entries, @Nonnull Set<String> out) {
        for (ObjectiveIndex.Entry entry : entries) {
            String target = entry.objective().target();
            if (!target.isBlank()) {
                out.add(target);
            }
        }
    }

    @Nonnull
    private static LocationTarget parsed(@Nonnull String raw) {
        return PARSED.computeIfAbsent(raw, r -> {
            LocationTarget target = LocationTarget.parse(r);
            return target == null ? UNPARSEABLE : target;
        });
    }

    /** A player left the server: who they were standing in goes. */
    static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            if (player != null && player.getUuid() != null) {
                INSIDE.remove(player.getUuid());
            }
        } catch (Throwable t) {
            SafeLog.fine("[progression] a departing player's reach-location state could not be dropped: "
                    + t.getMessage());
        }
    }
}
