package com.ziggfreed.common.stats.gearset;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * How a tier's {@code Effect} is put on, taken off and looked for: a SEAM this module cannot fill
 * itself, because the native-effect primitive lives in a module this one does not see, and the
 * wiring root fills it with three method references ({@code NativeEffectUtil::apply},
 * {@code NativeEffectUtil::remove}, {@code NativeEffectUtil::has}, whose signatures these three
 * interfaces repeat exactly).
 *
 * <p>Unfilled, the engine still pays every stat and announces every tier; only the look is missing.
 * That gap is invisible from every other side, so the seam REPORTS ON ITSELF, once per process, the
 * first time it is consulted unfilled, naming the fill that is missing and what it costs the player.
 */
public final class GearSetEffects {

    /** Put a native {@code EntityEffect} on an entity by id; true when it went on. */
    @FunctionalInterface
    public interface Apply {
        boolean apply(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String effectId);
    }

    /** Take a native {@code EntityEffect} off an entity by id; true when the engine was asked. */
    @FunctionalInterface
    public interface Remove {
        boolean remove(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String effectId);
    }

    /** Whether an entity has a native {@code EntityEffect} active right now; false when it cannot tell. */
    @FunctionalInterface
    public interface Has {
        boolean has(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String effectId);
    }

    private static final String LOG_PREFIX = "[gearset]";

    private static final AtomicReference<Apply> APPLY = new AtomicReference<>();
    private static final AtomicReference<Remove> REMOVE = new AtomicReference<>();
    private static final AtomicReference<Has> HAS = new AtomicReference<>();
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private GearSetEffects() {
    }

    /** Fill all three; null on any leaves the seam unfilled and reporting. */
    public static void fill(@Nullable Apply apply, @Nullable Remove remove, @Nullable Has has) {
        APPLY.set(apply);
        REMOVE.set(remove);
        HAS.set(has);
    }

    public static boolean isFilled() {
        return APPLY.get() != null && REMOVE.get() != null && HAS.get() != null;
    }

    /** Drop the fill and the once-only report; for a test starting from nothing. */
    public static void resetForTests() {
        APPLY.set(null);
        REMOVE.set(null);
        HAS.set(null);
        WARNED.set(false);
    }

    /** Put {@code effectId} on; false, having reported once, when nothing fills the seam. */
    public static boolean apply(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String effectId) {
        if (!warnIfUnfilled()) {
            return false;
        }
        Apply filled = APPLY.get();
        if (filled == null) {
            return false;
        }
        try {
            return filled.apply(store, ref, effectId);
        } catch (Throwable t) {
            SafeLog.warn(LOG_PREFIX + " the effect fill failed to apply '" + effectId + "': " + t.getMessage());
            return false;
        }
    }

    /** Take {@code effectId} off; false, having reported once, when nothing fills the seam. */
    public static boolean remove(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String effectId) {
        if (!warnIfUnfilled()) {
            return false;
        }
        Remove filled = REMOVE.get();
        if (filled == null) {
            return false;
        }
        try {
            return filled.remove(store, ref, effectId);
        } catch (Throwable t) {
            SafeLog.warn(LOG_PREFIX + " the effect fill failed to remove '" + effectId + "': " + t.getMessage());
            return false;
        }
    }

    /**
     * Whether the entity has {@code effectId} active right now; false, having reported once, when
     * nothing fills the seam, and false whenever the fill cannot tell (so the caller applies).
     */
    public static boolean has(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String effectId) {
        if (!warnIfUnfilled()) {
            return false;
        }
        Has filled = HAS.get();
        if (filled == null) {
            return false;
        }
        try {
            return filled.has(store, ref, effectId);
        } catch (Throwable t) {
            SafeLog.warn(LOG_PREFIX + " the effect fill failed to look for '" + effectId + "': " + t.getMessage());
            return false;
        }
    }

    /**
     * True when the seam is filled; when it is not, says so once and answers false.
     *
     * @return whether a caller may proceed
     */
    static boolean warnIfUnfilled() {
        if (isFilled()) {
            return true;
        }
        warnOnce("nothing applies a gear set's Effect, so a completed set pays its stats and announces "
                + "its tier but never shows its look; fill GearSets.effects(apply, remove, has) at setup");
        return false;
    }

    /** The once-only latch, for the test that pins the report fires once. */
    @Nonnull
    static AtomicBoolean latchForTests() {
        return WARNED;
    }

    /**
     * Say {@code message} once, out loud.
     *
     * @return true when this call is the one that reported it, false when it was already said
     */
    static boolean warnOnce(@Nonnull String message) {
        if (!WARNED.compareAndSet(false, true)) {
            return false;
        }
        SafeLog.warn(LOG_PREFIX + " " + message);
        return true;
    }
}
