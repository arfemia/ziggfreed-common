package com.ziggfreed.common.sound;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.util.AssetIndexCache;

/**
 * Shared 3D {@code SoundEvent} playback: the superset of the MMO Skill Tree
 * {@code AbilitySoundUtil} (play-by-id at a position, with a missing-asset warn
 * toggle) and the Kweebec {@code HeartbeatService.playSoundEvent3d} (a
 * {@link SoundCategory} plus a per-listener {@link Predicate} so a sound can be
 * private to one listener).
 *
 * <p>Resolution of a sound id to its asset index goes through {@link AssetIndexCache}
 * (one cache per id, lazily created), so an id that is missing or whose pack is not
 * loaded yet re-resolves on the next call instead of latching into silence. Every
 * entry point swallows missing-asset / runtime errors so a misconfigured sound never
 * throws into the caller.
 *
 * <p><b>World-thread only.</b> All overloads read the store and write packets via
 * the engine spatial collect, so call them inside {@code world.execute} (or already
 * on the world tick). The packet write itself is thread-safe, but the asset-map and
 * {@code TransformComponent} reads are not.
 */
public final class Sound3D {

    /** Default category for an unspecified call (matches the MMO ability path). */
    public static final SoundCategory DEFAULT_CATEGORY = SoundCategory.SFX;

    /** One index cache per sound id; only positive indices are cached (see AssetIndexCache). */
    private static final Map<String, AssetIndexCache<SoundEvent>> CACHES = new ConcurrentHashMap<>();

    /** Hears everyone in range (the engine default). */
    private static final Predicate<Ref<EntityStore>> ALL_LISTENERS = ref -> true;

    private Sound3D() {
    }

    // ---------------------------------------------------------------------
    // play at a position
    // ---------------------------------------------------------------------

    /** Play a 3D sound at a position to all listeners in range ({@link #DEFAULT_CATEGORY}). */
    public static void play(@Nullable String soundEventId,
                            @Nonnull Vector3d position,
                            @Nonnull Store<EntityStore> store,
                            @Nonnull String contextLabel) {
        play(soundEventId, DEFAULT_CATEGORY, position.x(), position.y(), position.z(),
                ALL_LISTENERS, store, contextLabel, false);
    }

    /**
     * Play a 3D sound at a position, optionally warning on a missing asset.
     *
     * @param contextLabel  short label prefixed to log lines (e.g. "HEARTBEAT", "STINGER")
     * @param warnOnMissing when true, a missing/unresolved asset logs at WARNING (a likely
     *                      config bug); when false it logs at FINE (an optional sound)
     */
    public static void play(@Nullable String soundEventId,
                            @Nonnull SoundCategory category,
                            @Nonnull Vector3d position,
                            @Nonnull Store<EntityStore> store,
                            @Nonnull String contextLabel,
                            boolean warnOnMissing) {
        play(soundEventId, category, position.x(), position.y(), position.z(),
                ALL_LISTENERS, store, contextLabel, warnOnMissing);
    }

    /**
     * Play a 3D sound at explicit coordinates, with a category and a per-listener
     * predicate (e.g. a self-only predicate for a private heartbeat). This is the
     * single underlying implementation; the other overloads delegate here.
     *
     * @param soundEventId  the {@code SoundEvent} asset id; null/missing = no-op
     * @param shouldHear    per-candidate filter; only listeners it passes get the packet
     * @param warnOnMissing WARNING vs FINE for a missing/unresolved asset
     */
    public static void play(@Nullable String soundEventId,
                            @Nonnull SoundCategory category,
                            double x, double y, double z,
                            @Nonnull Predicate<Ref<EntityStore>> shouldHear,
                            @Nonnull Store<EntityStore> store,
                            @Nonnull String contextLabel,
                            boolean warnOnMissing) {
        if (soundEventId == null || soundEventId.isEmpty()) {
            return;
        }
        try {
            int idx = resolveIndex(soundEventId);
            if (idx == AssetIndexCache.UNRESOLVED) {
                String msg = contextLabel + " sound '" + soundEventId + "' not in SoundEvent registry";
                if (warnOnMissing) {
                    ZiggfreedCommonPlugin.LOGGER.atWarning().log(msg);
                } else {
                    ZiggfreedCommonPlugin.LOGGER.atFine().log(msg);
                }
                return;
            }
            SoundUtil.playSoundEvent3d(idx, category, x, y, z, shouldHear, store);
        } catch (Throwable t) {
            String msg = contextLabel + " sound (" + soundEventId + ") failed: " + t.getMessage();
            if (warnOnMissing) {
                ZiggfreedCommonPlugin.LOGGER.atWarning().log(msg);
            } else {
                ZiggfreedCommonPlugin.LOGGER.atFine().log(msg);
            }
        }
    }

    // ---------------------------------------------------------------------
    // play at an entity
    // ---------------------------------------------------------------------

    /**
     * Play a 3D sound at an entity's current position (read from its
     * {@link TransformComponent}) to all listeners in range. No-op if the entity
     * has no transform or the ref is invalid.
     */
    public static void playAt(@Nullable String soundEventId,
                              @Nonnull SoundCategory category,
                              @Nonnull Ref<EntityStore> entityRef,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull String contextLabel,
                              boolean warnOnMissing) {
        playAt(soundEventId, category, entityRef, ALL_LISTENERS, store, contextLabel, warnOnMissing);
    }

    /**
     * Play a 3D sound at an entity's current position, with a per-listener predicate
     * (e.g. {@link #onlyEntity(Ref)} for a sound private to that one entity's player).
     */
    public static void playAt(@Nullable String soundEventId,
                              @Nonnull SoundCategory category,
                              @Nonnull Ref<EntityStore> entityRef,
                              @Nonnull Predicate<Ref<EntityStore>> shouldHear,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull String contextLabel,
                              boolean warnOnMissing) {
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }
        TransformComponent tc = store.getComponent(entityRef, TransformComponent.getComponentType());
        if (tc == null) {
            return;
        }
        Vector3d pos = tc.getPosition();
        play(soundEventId, category, pos.x(), pos.y(), pos.z(), shouldHear, store, contextLabel, warnOnMissing);
    }

    // ---------------------------------------------------------------------
    // listener predicates
    // ---------------------------------------------------------------------

    /**
     * A predicate that passes ONLY the given entity ref (identity match) - the
     * private-to-one-listener filter used for a per-player heartbeat / stinger.
     */
    @Nonnull
    public static Predicate<Ref<EntityStore>> onlyEntity(@Nonnull Ref<EntityStore> target) {
        return candidate -> candidate != null && candidate.equals(target);
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    private static int resolveIndex(@Nonnull String soundEventId) {
        return CACHES.computeIfAbsent(soundEventId,
                id -> AssetIndexCache.of(id, key -> SoundEvent.getAssetMap().getIndex(key))).resolve();
    }
}
