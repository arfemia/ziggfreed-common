package com.ziggfreed.common.effect.landing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one watch on effects landing, refreshing and ending. The engine says nothing when an effect goes on or comes
 * off, so this looks: at every living carrier ({@link EffectLandingSystems.Carriers}: a player every tick, a creature
 * on zc-entity's {@code StatCarriers} cadence, and every tick a creature a hit or a control landed on in the last
 * {@value #WATCH_SECONDS} seconds, {@link #watch}), once a look, for everyone who reads it ({@link Watcher}): the
 * effect-length pass (zc-effects' {@code EffectDurations}, every active effect as seen) and zc-track's effect moments
 * (EffectLanded and EffectEnded, for the effects a live proc names). Never a second poller beside it.
 *
 * <p>A landing is an instance not there at the last look (a re-apply that keeps its instance is not one), and an
 * ending one gone; only the effects some watcher {@link Watcher#tracks tracks} are kept between looks, per carrier, so
 * a carrier with none costs one look. An effect shorter than the gap between two looks can come and go unseen.
 *
 * <p>World thread; a carrier's record is its own world's thread's alone, and the maps are concurrent, since an entity
 * leaving one world is forgotten while another world looks at its own. Never throws.
 */
public final class EffectLandings {

    /** How long a hit target stays read every tick, in seconds: long enough for a hit's own effects to land. */
    public static final double WATCH_SECONDS = 2.0;

    /** How many watched entities the map holds before a watch sweeps the expired and gone ones out. */
    static final int SWEEP_PAST = 256;

    /** One reader of the watch, registered once at setup ({@link #register}). */
    public interface Watcher {

        /** Does it want anything now? Asked every look; a cheap switch. */
        boolean listening();

        /** Every active effect {@code carrier} wears, at every look (the length pass's per-instance read). */
        default void seen(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> carrier,
                @Nonnull ActiveEntityEffect active) {
        }

        /** Does it want the landings and endings of the effect at {@code effectIndex}? */
        default boolean tracks(int effectIndex) {
            return false;
        }

        /** A tracked effect landed on {@code carrier}, put on by {@code owner} (null for none). */
        default void landed(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
                @Nonnull Ref<EntityStore> carrier, int effectIndex, @Nullable Ref<EntityStore> owner) {
        }

        /** A tracked effect came off {@code carrier}, put on by {@code owner} (null for none). */
        default void ended(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
                @Nonnull Ref<EntityStore> carrier, int effectIndex, @Nullable Ref<EntityStore> owner) {
        }
    }

    /** One tracked effect as a look saw it: its instance and who put it on. */
    private record Seen(@Nonnull Object instance, @Nullable Ref<EntityStore> owner) {
    }

    private static final List<Watcher> WATCHERS = new CopyOnWriteArrayList<>();

    /** Each watched entity and when its watch ends (nanos). */
    private static final Map<Ref<EntityStore>, Long> WATCHED = new ConcurrentHashMap<>();

    /** Each carrier's tracked effects at its last look, by effect index; absent for none. */
    private static final Map<Ref<EntityStore>, Map<Integer, Seen>> TRACKED = new ConcurrentHashMap<>();

    private static volatile boolean failureWarned;

    private EffectLandings() {
    }

    /** Add a reader. Call once from a bootstrap at setup. */
    public static void register(@Nonnull Watcher watcher) {
        if (!WATCHERS.contains(watcher)) {
            WATCHERS.add(watcher);
        }
    }

    /**
     * Read {@code ref} every tick for the next {@value #WATCH_SECONDS} seconds: a hit or a control just landed on it. An
     * entity the carrier pass never visits (one with no effects, a dead one) would never drop out on its own, so the
     * map sweeps its expired and gone entries whenever it grows past a few hundred.
     */
    public static void watch(@Nullable Ref<EntityStore> ref, long nanos) {
        if (ref == null || !ref.isValid()) {
            return;
        }
        WATCHED.put(ref, nanos + (long) (WATCH_SECONDS * 1_000_000_000L));
        if (WATCHED.size() > SWEEP_PAST) {
            WATCHED.entrySet().removeIf(entry -> !entry.getKey().isValid() || entry.getValue() < nanos);
        }
    }

    /** Is {@code ref} under watch now? An expired watch is dropped. */
    static boolean watched(@Nonnull Ref<EntityStore> ref, long nanos) {
        if (WATCHED.isEmpty()) {
            return false;
        }
        Long until = WATCHED.get(ref);
        if (until == null) {
            return false;
        }
        if (until < nanos) {
            WATCHED.remove(ref, until);
            return false;
        }
        return true;
    }

    /** Forget {@code ref} (it left its store). */
    static void forget(@Nonnull Ref<EntityStore> ref) {
        WATCHED.remove(ref);
        TRACKED.remove(ref);
    }

    /**
     * One look at {@code carrier}: every watcher's per-instance read, then the tracked effects' landings and endings
     * since the last look. World thread; never throws.
     */
    static void look(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
            @Nonnull Ref<EntityStore> carrier) {
        try {
            if (!carrier.isValid() || WATCHERS.isEmpty()) {
                return;
            }
            boolean any = false;
            for (Watcher watcher : WATCHERS) {
                any |= watcher.listening();
            }
            if (!any) {
                TRACKED.remove(carrier);
                return;
            }
            EffectControllerComponent ctrl = store.getComponent(carrier, EffectControllerComponent.getComponentType());
            List<ActiveEntityEffect> actives = ctrl == null || ctrl.getActiveEffects().isEmpty() ? List.of()
                    : List.copyOf(ctrl.getActiveEffects().values());
            Map<Integer, Seen> now = null;
            for (ActiveEntityEffect active : actives) {
                boolean tracked = false;
                for (Watcher watcher : WATCHERS) {
                    if (!watcher.listening()) {
                        continue;
                    }
                    watcher.seen(store, carrier, active);
                    tracked |= watcher.tracks(active.getEntityEffectIndex());
                }
                if (tracked) {
                    if (now == null) {
                        now = new HashMap<>();
                    }
                    now.put(active.getEntityEffectIndex(), new Seen(active, active.getOwner()));
                }
            }
            Map<Integer, Seen> before = now == null ? TRACKED.remove(carrier) : TRACKED.put(carrier, now);
            changes(store, buffer, carrier, before == null ? Map.of() : before, now == null ? Map.of() : now);
        } catch (Throwable t) {
            if (!failureWarned) {
                failureWarned = true;
                SafeLog.warn("[landing] a look at an entity's effects failed (named once): " + t);
            }
        }
    }

    /** Tell each tracking watcher of every ending, then every landing, between {@code before} and {@code now}. */
    private static void changes(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> buffer,
            @Nonnull Ref<EntityStore> carrier, @Nonnull Map<Integer, Seen> before, @Nonnull Map<Integer, Seen> now) {
        if (before.isEmpty() && now.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, Seen> was : before.entrySet()) {
            Seen is = now.get(was.getKey());
            if (is == null || is.instance() != was.getValue().instance()) {
                for (Watcher watcher : WATCHERS) {
                    if (watcher.tracks(was.getKey())) {
                        watcher.ended(store, buffer, carrier, was.getKey(), was.getValue().owner());
                    }
                }
            }
        }
        for (Map.Entry<Integer, Seen> is : now.entrySet()) {
            Seen was = before.get(is.getKey());
            if (was == null || was.instance() != is.getValue().instance()) {
                for (Watcher watcher : WATCHERS) {
                    if (watcher.tracks(is.getKey())) {
                        watcher.landed(store, buffer, carrier, is.getKey(), is.getValue().owner());
                    }
                }
            }
        }
    }

    /** Forget every record (a test). */
    static void clear() {
        WATCHED.clear();
        TRACKED.clear();
    }
}
