package com.ziggfreed.common.entity.overhead;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.util.SafeLog;

/**
 * A picture over an entity's head that only some players see: any consumer hangs an authored look
 * over any entity for one player, a set of players or everyone, and takes it down again, with a
 * lifetime and the look's own offset. It knows nothing about quests, or about any consumer.
 *
 * <p><b>Show a STATE, not a picture.</b> A consumer names a state id ({@code Boss_Enraged},
 * {@code Station_Busy}, {@code Quest_Available}) and the look for it is a file, one per state, at
 * {@code Server/ZiggfreedCommon/OverheadIndicators/<State_Id>.json} ({@link OverheadIndicatorAsset}),
 * so what a cue looks like is a pack's or a server owner's to change and a consumer never ships a
 * picture in code. A state nothing describes shows nothing, and the library says so once per id.
 *
 * <p><b>One marker per host and state, seen only by its audience.</b> The picture is a transient
 * marker entity floating over the host, created the first time a state is shown over that host and
 * shared by every viewer of that state. Who sees it is decided every tick inside the engine's own
 * visibility pass: the marker is taken out of every other viewer's visible set before anything is
 * sent, so a viewer it is not for never receives it at all, and one it stops being for is sent the
 * ordinary despawn. The marker follows its host on a cadence rather than every tick
 * ({@link #followCadenceMs}), and only when the host has moved more than a hair.
 *
 * <p><b>Threading and the accessor.</b> Every call runs on the host's world thread. {@code show}
 * takes a {@link ComponentAccessor} because it may have to spawn the marker: hand it the live
 * {@link Store} from a world task, or the {@link CommandBuffer} you were handed when you are inside
 * a system tick (a direct store add throws there, and the guarded call would only warn). Hiding
 * and clearing only record what is wanted; the follow pass takes the marker down on its next beat,
 * through its own command buffer, so those two need no accessor at all. Every engine-touching call
 * is try-guarded and degrades to a warning, never a throw into the caller.
 *
 * <p>Registration is the library's own ({@code EntityBootstrap.registerOverheadIndicators}); a
 * consumer only calls.
 */
public final class OverheadIndicators {

    /** How often a marker is repositioned under its host, by default. */
    public static final long DEFAULT_FOLLOW_MS = 250L;

    /** How far a host has to move, in blocks, before its markers are moved after it. */
    public static final double FOLLOW_EPSILON = 0.02;

    /** How long a freshly queued marker may stay invalid before it is treated as gone and rebuilt. */
    private static final long PENDING_GRACE_MS = 1_000L;

    private static final String LOG = "[overhead] ";

    private static volatile long followCadenceMs = DEFAULT_FOLLOW_MS;

    /** State ids already reported as having no look, so a busy sweep says it once. */
    private static final Set<String> WARNED_LOOKLESS = ConcurrentHashMap.newKeySet();

    private OverheadIndicators() {
    }

    // ==================== the consumer's calls ====================

    /** {@link #show(ComponentAccessor, Ref, String, IndicatorAudience, IndicatorLifetime)} until hidden. */
    public static void show(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> hostRef,
            @Nonnull String stateId, @Nonnull IndicatorAudience audience) {
        show(accessor, hostRef, stateId, audience, IndicatorLifetime.untilHidden());
    }

    /**
     * Show the look authored for {@code stateId} over {@code hostRef} to {@code audience}, for
     * {@code lifetime}. Showing a second state to the same audience replaces the first for them;
     * showing to a viewer over a state shown to everyone layers theirs on top.
     */
    public static void show(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> hostRef,
            @Nonnull String stateId, @Nonnull IndicatorAudience audience, @Nonnull IndicatorLifetime lifetime) {
        if (stateId.isBlank() || audience.isEmpty() || !hostRef.isValid()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            OverheadRegistry registry = OverheadRegistry.of(WorldEvictors.worldOf(hostRef));
            HostIndicators host = registry.host(hostRef);
            host.show(audience, stateId, lifetime, now);
            ensureMarker(accessor, registry, hostRef, host, HostIndicators.keyOf(stateId), now);
        } catch (Throwable t) {
            warn("show failed for '" + stateId + "': " + t.getMessage(), t);
        }
    }

    /** Take down what was shown to {@code audience} over {@code hostRef}, and only that. */
    public static void hide(@Nonnull Ref<EntityStore> hostRef, @Nonnull IndicatorAudience audience) {
        try {
            HostIndicators host = peekHost(hostRef);
            if (host != null) {
                host.hide(audience);
            }
        } catch (Throwable t) {
            warn("hide failed: " + t.getMessage(), t);
        }
    }

    /** Take down everything shown over {@code hostRef}, for everyone. */
    public static void clear(@Nonnull Ref<EntityStore> hostRef) {
        try {
            HostIndicators host = peekHost(hostRef);
            if (host != null) {
                host.clear();
            }
        } catch (Throwable t) {
            warn("clear failed: " + t.getMessage(), t);
        }
    }

    /** Forget one viewer's own entries over every host in every world (a disconnect). Any thread. */
    public static void forgetViewer(@Nonnull UUID viewer) {
        for (OverheadRegistry registry : OverheadRegistry.all()) {
            for (HostIndicators host : registry.hosts().values()) {
                host.forget(viewer);
            }
        }
    }

    /** The folded state key {@code viewer} currently sees over {@code hostRef}, or null. A read. */
    @Nullable
    public static String stateShownTo(@Nonnull Ref<EntityStore> hostRef, @Nonnull UUID viewer) {
        HostIndicators host = peekHost(hostRef);
        return host == null ? null : host.stateFor(viewer, System.currentTimeMillis());
    }

    /** How often a marker is repositioned under a moving host; a value under one tick is one tick. */
    public static void followCadenceMs(long cadenceMs) {
        followCadenceMs = Math.max(1L, cadenceMs);
    }

    public static long followCadenceMs() {
        return followCadenceMs;
    }

    // ==================== the library's own lifecycle ====================

    /** A world going away takes its table with it; the entities are the world's and go with it too. */
    public static void onWorldRemoved(@Nullable World world) {
        if (world != null) {
            OverheadRegistry.evict(world);
        }
    }

    /**
     * Drop every table at plugin shutdown. The markers are never persisted, so a restart cannot
     * bring one back; what is left is only memory.
     */
    public static void shutdown() {
        OverheadRegistry.clearAll();
        WARNED_LOOKLESS.clear();
    }

    /**
     * One beat of the follow pass for {@code registry}'s world: expire what has run out, take down
     * markers no live entry names or whose host is gone, put a marker under every state that lacks
     * one, and move the rest after their hosts. Runs on the world thread with the tick's own command
     * buffer, so a despawn or a spawn queued here lands when the buffer flushes.
     */
    static void pass(@Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull OverheadRegistry registry,
            long nowMs) {
        for (Map.Entry<Ref<EntityStore>, HostIndicators> entry : registry.hosts().entrySet()) {
            Ref<EntityStore> hostRef = entry.getKey();
            HostIndicators host = entry.getValue();
            host.expire(nowMs);
            if (!hostRef.isValid() || (host.isIdle(nowMs) && host.markers().isEmpty())) {
                despawnAll(commandBuffer, registry.dropHost(hostRef));
                continue;
            }
            Set<String> live = host.liveStateKeys(nowMs);
            for (Map.Entry<String, Ref<EntityStore>> marker : Map.copyOf(host.markers()).entrySet()) {
                Ref<EntityStore> markerRef = marker.getValue();
                if (!live.contains(marker.getKey())) {
                    registry.dropMarker(markerRef);
                    despawn(commandBuffer, markerRef);
                } else if (!markerRef.isValid()) {
                    dropIfStale(registry, markerRef, nowMs);
                } else {
                    follow(commandBuffer, hostRef, markerRef, marker.getKey());
                }
            }
            for (String stateKey : live) {
                if (!host.markers().containsKey(stateKey)) {
                    ensureMarker(commandBuffer, registry, hostRef, host, stateKey, nowMs);
                }
            }
        }
    }

    // ==================== internals ====================

    @Nullable
    private static HostIndicators peekHost(@Nonnull Ref<EntityStore> hostRef) {
        OverheadRegistry registry = OverheadRegistry.peek(WorldEvictors.worldOf(hostRef));
        return registry == null ? null : registry.peekHost(hostRef);
    }

    /**
     * Put a marker under {@code stateKey} over {@code hostRef} if none stands yet. A look-less
     * state is reported once and shows nothing; a spawn the engine refuses is reported and retried
     * on the next pass.
     */
    private static void ensureMarker(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull OverheadRegistry registry, @Nonnull Ref<EntityStore> hostRef, @Nonnull HostIndicators host,
            @Nonnull String stateKey, long nowMs) {
        if (host.markers().containsKey(stateKey)) {
            return;
        }
        OverheadIndicatorAsset look = OverheadIndicatorConfig.getInstance().resolve(stateKey);
        if (look == null || !look.hasLook()) {
            if (WARNED_LOOKLESS.add(stateKey)) {
                SafeLog.warn(LOG + "nothing is authored for the overhead state '" + stateKey + "': add "
                        + "Server/ZiggfreedCommon/OverheadIndicators/" + stateKey + ".json with an Icon, or the "
                        + "state shows nothing");
            }
            return;
        }
        Vector3d target = targetOf(accessor, hostRef, look);
        if (target == null) {
            return;
        }
        Holder<EntityStore> holder = OverheadLooks.build(accessor, look, target);
        if (holder == null) {
            return;
        }
        try {
            Ref<EntityStore> markerRef = accessor.addEntity(holder, AddReason.SPAWN);
            host.markers().put(stateKey, markerRef);
            registry.markers().put(markerRef, new OverheadRegistry.Marker(hostRef, host, stateKey, nowMs));
        } catch (Throwable t) {
            warn("the marker for '" + stateKey + "' could not be spawned: " + t.getMessage(), t);
        }
    }

    /** Where {@code look}'s marker belongs over {@code hostRef} right now, or null when the host has no position. */
    @Nullable
    private static Vector3d targetOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> hostRef,
            @Nonnull OverheadIndicatorAsset look) {
        TransformComponent transform = accessor.getComponent(hostRef, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        return OverheadAnchor.target(transform.getPosition(), anchorHeight(accessor, hostRef),
                look.offsetX(), look.offsetY(), look.offsetZ());
    }

    /** The top of the host: the taller of its eye height and its box, or a human height for neither. */
    private static double anchorHeight(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> hostRef) {
        double eye = ModelComponent.getEyeHeight(hostRef, accessor);
        BoundingBox box = accessor.getComponent(hostRef, BoundingBox.getComponentType());
        double boxHeight = box == null ? 0d : box.getBoundingBox().height();
        return OverheadAnchor.anchorHeight(eye, boxHeight);
    }

    /** Move one standing marker after its host when the host has moved beyond the epsilon. */
    private static void follow(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> hostRef,
            @Nonnull Ref<EntityStore> markerRef, @Nonnull String stateKey) {
        try {
            OverheadIndicatorAsset look = OverheadIndicatorConfig.getInstance().resolve(stateKey);
            TransformComponent marker = accessor.getComponent(markerRef, TransformComponent.getComponentType());
            if (look == null || marker == null) {
                return;
            }
            Vector3d target = targetOf(accessor, hostRef, look);
            if (target != null && OverheadAnchor.moved(marker.getPosition(), target, FOLLOW_EPSILON)) {
                marker.setPosition(target);
            }
        } catch (Throwable t) {
            warn("a marker could not follow its host: " + t.getMessage(), t);
        }
    }

    /** A marker that has stayed invalid past the grace a queued spawn needs was removed elsewhere: forget it. */
    private static void dropIfStale(@Nonnull OverheadRegistry registry, @Nonnull Ref<EntityStore> markerRef,
            long nowMs) {
        OverheadRegistry.Marker marker = registry.markers().get(markerRef);
        if (marker != null && nowMs - marker.createdMs() > PENDING_GRACE_MS) {
            registry.dropMarker(markerRef);
        }
    }

    private static void despawnAll(@Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Collection<Ref<EntityStore>> markerRefs) {
        for (Ref<EntityStore> ref : markerRefs) {
            despawn(commandBuffer, ref);
        }
    }

    private static void despawn(@Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> markerRef) {
        if (!markerRef.isValid()) {
            return;
        }
        try {
            commandBuffer.removeEntity(markerRef, RemoveReason.REMOVE);
        } catch (Throwable t) {
            SafeLog.fine(LOG + "a marker could not be despawned: " + t.getMessage());
        }
    }

    static void warn(@Nonnull String message, @Nullable Throwable cause) {
        SafeLog.warn(LOG + message, cause);
    }
}
