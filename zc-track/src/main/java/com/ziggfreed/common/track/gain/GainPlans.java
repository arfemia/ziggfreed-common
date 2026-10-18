package com.ziggfreed.common.track.gain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.event.events.permissions.GroupPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.permissions.PlayerPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.GainCall;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.util.SafeLog;

/**
 * The compiled gain plans, one per subject and track: each factor the track names, compiled once against where the
 * subject stands ({@link GainScope}) and kept behind an epoch, the {@code LevelCurve.generation} pattern, so a gain
 * on the hot path runs its steps with nothing to look up.
 *
 * <p><b>The epoch</b> is two counters, both read on every gain: the server's ({@link #invalidateAll}, moved by a
 * factor registration, a track fold, a world rule or gameplay config load, and a group permission change) and the
 * subject's own ({@link #invalidate}, moved when the player is added to a world, is ready in it, or has their
 * permissions changed, and by any factor whose answer for one player changes, such as a class change or a boost
 * starting). A plan built under other counters is built again on its next gain. A departing player's plans go with
 * them ({@link #forget}).
 */
public final class GainPlans {

    private static final AtomicLong GENERATION = new AtomicLong();

    /** Per subject (its kind and id, the key's own record, so a lookup allocates nothing), its counter and plans. */
    private static final Map<TrackKey.Subject, Subject> SUBJECTS = new ConcurrentHashMap<>();

    /** The factor ids already warned for throwing, so each is said once until it registers again. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** One subject's counter and plans. */
    private static final class Subject {
        final AtomicLong generation = new AtomicLong();
        final Map<String, Plan> byTrack = new ConcurrentHashMap<>();
    }

    /**
     * One compiled plan: the factor ids and their steps, in the track's order (a factor compiling to nothing is
     * left out), and the counters it was built under.
     */
    record Plan(@Nonnull String[] ids, @Nonnull GainStep[] steps, long server, long own) {
    }

    private GainPlans() {
    }

    /** Build every plan again on its next gain. */
    public static void invalidateAll() {
        GENERATION.incrementAndGet();
        WARNED.clear();
    }

    /** Build {@code playerId}'s plans again on their next gain. */
    public static void invalidate(@Nullable UUID playerId) {
        if (playerId == null) {
            return;
        }
        Subject subject = SUBJECTS.get(TrackKey.Subject.player(playerId));
        if (subject != null) {
            subject.generation.incrementAndGet();
        }
    }

    /** Forget {@code playerId}'s plans. */
    public static void forget(@Nullable UUID playerId) {
        if (playerId != null) {
            SUBJECTS.remove(TrackKey.Subject.player(playerId));
        }
    }

    /** The server counter: what a plan built now is stamped with (a test, a diagnostic). */
    public static long generation() {
        return GENERATION.get();
    }

    /** {@code call}'s plan: the one kept when its counters stand, else one built now and kept. */
    @Nonnull
    static Plan planFor(@Nonnull GainCall call) {
        long server = GENERATION.get();
        Subject subject = SUBJECTS.computeIfAbsent(call.key().subject(), key -> new Subject());
        long own = subject.generation.get();
        // The key's track is already lower-cased, so the lookup allocates nothing.
        String trackId = call.key().track();
        Plan plan = subject.byTrack.get(trackId);
        if (plan != null && plan.server() == server && plan.own() == own) {
            return plan;
        }
        plan = compile(call, server, own);
        subject.byTrack.put(trackId, plan);
        return plan;
    }

    @Nonnull
    private static Plan compile(@Nonnull GainCall call, long server, long own) {
        GainScope scope = new GainScope(call.key(), call.def(), call.ref(), worldOf(call.ref()));
        List<String> ids = new ArrayList<>();
        List<GainStep> steps = new ArrayList<>();
        for (String id : call.def().asset().gainFactors()) {
            GainFactor factor = GainFactors.factor(id);
            if (factor == null) {
                continue;
            }
            GainStep step;
            try {
                step = factor.compile(scope);
            } catch (Throwable t) {
                warnOnce(id, "could not be compiled", t);
                step = GainStep.NONE;
            }
            if (step != null && step != GainStep.NONE) {
                ids.add(id);
                steps.add(step);
            }
        }
        return new Plan(ids.toArray(new String[0]), steps.toArray(new GainStep[0]), server, own);
    }

    /** Say once that the factor {@code id} failed (until a registration or fold re-arms it). */
    static void warnOnce(@Nonnull String id, @Nonnull String what, @Nonnull Throwable t) {
        if (WARNED.add(id.toLowerCase(Locale.ROOT))) {
            SafeLog.warn("[track] the gain factor '" + id + "' " + what + ", so it changes nothing: "
                    + t.getMessage());
        }
    }

    @Nullable
    private static World worldOf(@Nullable Ref<EntityStore> ref) {
        try {
            return ref == null || !ref.isValid() ? null : ref.getStore().getExternalData().getWorld();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== the engine's events (registered by TrackBootstrap) ====================

    /** A player was added to a world (a join, a world change, an instance entry). */
    public static void onWorldChange(@Nonnull AddPlayerToWorldEvent event) {
        try {
            PlayerRef ref = event.getHolder().getComponent(PlayerRef.getComponentType());
            invalidate(ref == null ? null : ref.getUuid());
        } catch (Throwable t) {
            SafeLog.fine("[track] could not move a player's gain plans on a world change: " + t.getMessage());
        }
    }

    /** A player is ready in a world: what they stand in now is the new world. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Ref<EntityStore> ref = event.getPlayerRef();
            PlayerRef player = ref == null || !ref.isValid() ? null
                    : ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            invalidate(player == null ? null : player.getUuid());
        } catch (Throwable t) {
            SafeLog.fine("[track] could not move a player's gain plans once they were ready: " + t.getMessage());
        }
    }

    /** One player's permissions or groups changed (a permission level cap reads them). */
    public static void onPlayerPermissions(@Nonnull PlayerPermissionChangeEvent event) {
        invalidate(event.getPlayerUuid());
    }

    /** A group's permissions changed, for everyone in it. */
    public static void onGroupPermissions(@Nonnull GroupPermissionChangeEvent event) {
        invalidateAll();
    }

    /** A player left. */
    public static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef ref = event.getPlayerRef();
            forget(ref == null ? null : ref.getUuid());
        } catch (Throwable t) {
            SafeLog.fine("[track] could not forget a departing player's gain plans: " + t.getMessage());
        }
    }
}
