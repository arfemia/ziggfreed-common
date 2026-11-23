package com.ziggfreed.common.cast;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * A creature casting a named ability: the seam a mod that arms creatures with abilities (a mob scaler's caster
 * roster) asks, and a mod that HAS abilities fills with its own caster. The library ships no ability vocabulary, so
 * the id is the filling mod's own and opaque here; neither side imports the other.
 *
 * <p>Unfilled, nothing is cast: {@link #cast} answers {@link Outcome#NO_CASTER} and says so once, so a roster naming
 * abilities on a server without a mod that casts them costs one line and nothing else. A cast is a bare one (no cost,
 * no cooldown, no progress for anyone), on the world thread, and how often to ask is the caller's business.
 */
public final class NpcAbilityCasts {

    /** What a cast came to. */
    public enum Outcome {
        /** The ability went off. */
        CAST,
        /** The caster refused it (an id it does not know, an ability a creature cannot cast, no valid target). */
        REFUSED,
        /** No mod fills the seam, so nothing on this server casts abilities for creatures. */
        NO_CASTER
    }

    /** A mod's caster: cast {@code abilityId} from {@code casterRef}, true when it went off. World thread. */
    @FunctionalInterface
    public interface Caster {

        boolean cast(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> casterRef, @Nonnull String abilityId);
    }

    private static final AtomicReference<Caster> CASTER = new AtomicReference<>();
    private static final AtomicBoolean REPORTED_UNFILLED = new AtomicBoolean();

    private NpcAbilityCasts() {
    }

    /** Cast every creature's abilities through {@code caster}; null empties the seam. Last fill wins. */
    public static void fill(@Nullable Caster caster) {
        CASTER.set(caster);
    }

    /** Does a mod cast abilities for creatures on this server? */
    public static boolean isFilled() {
        return CASTER.get() != null;
    }

    /**
     * Cast {@code abilityId} from {@code casterRef} through the filling mod's caster. {@link Outcome#NO_CASTER} when
     * none fills the seam (said once), {@link Outcome#REFUSED} when it refused or threw (a throw is warned and reads
     * as refused, never rethrown). World thread.
     */
    @Nonnull
    public static Outcome cast(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> casterRef,
            @Nonnull String abilityId) {
        Caster caster = CASTER.get();
        if (caster == null) {
            if (REPORTED_UNFILLED.compareAndSet(false, true)) {
                SafeLog.info("[cast] a creature was asked to cast '" + abilityId + "', but no mod on this server casts "
                        + "abilities for creatures, so no creature casts one");
            }
            return Outcome.NO_CASTER;
        }
        try {
            return caster.cast(store, casterRef, abilityId) ? Outcome.CAST : Outcome.REFUSED;
        } catch (Throwable t) {
            SafeLog.warn("[cast] casting '" + abilityId + "' failed: " + t.getMessage());
            return Outcome.REFUSED;
        }
    }

    /** Empty the seam and its once-only report; for a test starting from nothing. */
    public static void resetForTests() {
        CASTER.set(null);
        REPORTED_UNFILLED.set(false);
    }
}
