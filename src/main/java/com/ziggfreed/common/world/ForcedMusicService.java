package com.ziggfreed.common.world;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.world.UpdateForcedMusic;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Forces a MUSIC container (a music "bed") onto a single player, overriding whatever
 * the engine would otherwise play. Mod-agnostic: it takes only a {@code MusicContainer}
 * id and engine types, never any consumer round / state type - the consumer iterates
 * its own roster and calls {@link #applyFor} per player, owning the candidate-id /
 * tier-selection policy itself.
 *
 * <p><b>Mechanism (verified against the 0.5.3 decompile + 0.5.5 javap):</b> the engine
 * music override is the {@code UpdateForcedMusic} packet (id 151), a single int container
 * index. {@code AudioPlugin} ensures a {@code ForcedMusicTracker} on every player and
 * registers {@code ForcedMusicSystems.Tick}, which sends that packet ONLY when
 * {@code currentContainerIndex} differs from {@code lastSentContainerIndex}. Setting the
 * tracker index alone proved unreliable inside a plugin-spawned instance world, so this
 * does NOT depend on the Tick system: it sets the tracker index (so the engine stays
 * consistent and {@link #clearFor} can zero it out) AND pushes the {@code UpdateForcedMusic}
 * packet DIRECTLY to the player's {@link PlayerRef#getPacketHandler()}, exactly the packet
 * that Tick / {@code /audio music force} send. The direct send works whether or not the Tick
 * system ticks instance worlds. Index 0 clears the override (restores the default bed).
 *
 * <p>The id is resolved (and validated) against {@code MusicContainer.getAssetMap()} on
 * every call - a bad / not-yet-registered id resolves to a non-positive index and the
 * apply degrades to {@code false} (no packet sent), so the caller can retry next tick.
 * <b>World-thread only</b> (it reads the Store/Ref); the packet write itself is thread-safe.
 * Fully try-guarded so a missing asset / bad ref degrades to a no-op, never a throw.
 */
public final class ForcedMusicService {

    private ForcedMusicService() {
    }

    /**
     * Force the {@code containerId} music bed onto ONE player. Resolves the id to its
     * container index, sets the player's {@link ForcedMusicTracker} index, and pushes the
     * {@code UpdateForcedMusic} packet directly to the player's handler.
     *
     * @param store       the entity store (read on the world thread)
     * @param ref         the player's entity ref
     * @param containerId the {@code MusicContainer} asset id to force
     * @return {@code true} once handled (the index resolved and at least one lever - the
     *         tracker or the direct packet - took); {@code false} on a bad / unresolved id,
     *         an invalid ref, or a failure (the caller may retry next tick)
     */
    public static boolean applyFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                                   @Nonnull String containerId) {
        int idx = resolveIndex(containerId);
        if (idx <= 0) {
            // Unresolved (asset map not ready or no such container). Do NOT latch; the caller
            // can retry next tick once the index resolves to a real (> 0) value.
            return false;
        }
        if (!ref.isValid()) {
            return false;
        }
        try {
            // 1) Set the engine tracker so ForcedMusicSystems.Tick keeps it forced IF it runs
            //    in this world, and so clearFor() has a tracker to zero out.
            ForcedMusicTracker tracker = store.ensureAndGetComponent(ref, ForcedMusicTracker.getComponentType());
            boolean haveTracker = tracker != null;
            if (haveTracker) {
                tracker.setCurrentContainerIndex(idx);
            }
            // 2) Push the packet DIRECTLY - the mechanism-independent send (see class javadoc).
            boolean sent = sendForcedMusic(store, ref, idx);
            // If our direct send landed, mark the tracker already-sent so the engine's Tick
            // (if it runs in this world) does not emit a duplicate identical packet next tick.
            // If the direct send failed, leave lastSent untouched so Tick still delivers it.
            if (sent && haveTracker) {
                tracker.setLastSentContainerIndex(idx);
            }
            // Retry next tick if neither lever took (no tracker AND no packet); else done.
            return haveTracker || sent;
        } catch (Throwable t) {
            warn("applyFor failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Clear the forced music override for ONE player (sends container index 0), restoring
     * the engine's default bed. Zeroes the tracker too so the engine stays consistent.
     * A no-op on an invalid ref or a failure.
     *
     * @param store the entity store (read on the world thread)
     * @param ref   the player's entity ref
     */
    public static void clearFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return;
        }
        try {
            ForcedMusicTracker tracker = store.getComponent(ref, ForcedMusicTracker.getComponentType());
            if (tracker != null) {
                tracker.setCurrentContainerIndex(0);
            }
            sendForcedMusic(store, ref, 0);
        } catch (Throwable t) {
            fine("clearFor failed: " + t.getMessage());
        }
    }

    /** Write the {@code UpdateForcedMusic} packet to the player's handler. Returns true if sent. */
    private static boolean sendForcedMusic(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, int index) {
        PlayerRef pr = store.getComponent(ref, PlayerRef.getComponentType());
        if (pr == null) {
            return false;
        }
        pr.getPacketHandler().write(new UpdateForcedMusic(index));
        return true;
    }

    /**
     * Resolve a {@code MusicContainer} id to its container index. Returns a non-positive
     * value (0 / {@code Integer.MIN_VALUE}) when the asset map is not ready or the id is
     * missing, so the caller treats it as "retry / skip" rather than forcing a bad index.
     */
    private static int resolveIndex(@Nonnull String containerId) {
        try {
            return MusicContainer.getAssetMap().getIndex(containerId);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void warn(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("[ziggfreed-common][music] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("[ziggfreed-common][music] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
