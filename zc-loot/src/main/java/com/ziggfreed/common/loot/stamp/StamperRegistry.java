package com.ziggfreed.common.loot.stamp;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Which {@link Stamper} this server writes stamps with. Exactly ONE, deliberately.
 *
 * <p>Two stampers would mean two item formats, and then every budget check reads only half the
 * history: an item stamped by one would look bare to the other, and the ceilings meant to keep gear
 * in line would simply stop working. So the last registration WINS outright rather than the two
 * coexisting, and a mod with a richer stamper (one that also writes a tooltip, say) replaces the
 * plain one instead of running beside it.
 *
 * <p>Register during plugin setup, before anything can stamp. Nothing is installed by default: a
 * server with no stamper registered writes nothing, which is the honest outcome rather than
 * silently picking a format.
 */
public final class StamperRegistry {

    private static final AtomicReference<Stamper> ACTIVE = new AtomicReference<>();

    private StamperRegistry() {
    }

    /** Install {@code stamper} as the one this server uses, replacing whatever was there. */
    public static void register(@Nullable Stamper stamper) {
        ACTIVE.set(stamper);
    }

    /** The active stamper, or null when nothing has registered one. */
    @Nullable
    public static Stamper get() {
        return ACTIVE.get();
    }

    /** True when something can actually write a stamp. */
    public static boolean isRegistered() {
        return ACTIVE.get() != null;
    }

    /** Drop the registration (test reset, and the shutdown path). */
    public static void clear() {
        ACTIVE.set(null);
    }

    /**
     * What {@code stack} already carries, read through the active stamper, or
     * {@link StampInspection#empty()} when nothing is registered or the read failed. A read that
     * throws must not take the surrounding grant with it, and an empty answer is the conservative
     * one - it never INVENTS history that would loosen a budget.
     */
    @Nonnull
    public static StampInspection inspect(@Nonnull ItemStack stack) {
        Stamper stamper = ACTIVE.get();
        if (stamper == null) {
            return StampInspection.empty();
        }
        try {
            return stamper.inspect(stack);
        } catch (Throwable t) {
            return StampInspection.empty();
        }
    }
}
