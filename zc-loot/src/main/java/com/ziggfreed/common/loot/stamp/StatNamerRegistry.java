package com.ziggfreed.common.loot.stamp;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * Which {@link StatNamer} supplies this server's stat vocabulary. Exactly ONE, and an absent one is
 * entirely ordinary: the library names the engine's own stats itself, so a server running it alone
 * renders every stamp it can produce.
 *
 * <p>{@link #name} is the call every renderer and reporter makes. It never throws and never returns
 * a gap: a namer that declines, or one that fails, falls through to {@link DefaultStatNames}, so a
 * broken consumer vocabulary costs its own wording and never the line.
 *
 * <p>Register during plugin setup, before anything can stamp.
 */
public final class StatNamerRegistry {

    private static final AtomicReference<StatNamer> ACTIVE = new AtomicReference<>();

    private StatNamerRegistry() {
    }

    /** Install {@code namer} as the one this server uses, replacing whatever was there. */
    public static void register(@Nullable StatNamer namer) {
        ACTIVE.set(namer);
    }

    /** The active namer, or null when nothing has registered one. */
    @Nullable
    public static StatNamer get() {
        return ACTIVE.get();
    }

    /** Drop the registration (test reset, and the shutdown path). */
    public static void clear() {
        ACTIVE.set(null);
    }

    /**
     * The line for {@code statId}: the registered vocabulary's answer when it owns the id, else the
     * library's own. Always a real line - never null, never a throw.
     */
    @Nonnull
    public static Message name(@Nonnull String statId, double points) {
        StatNamer namer = ACTIVE.get();
        if (namer != null) {
            try {
                Message named = namer.name(statId, points);
                if (named != null) {
                    return named;
                }
            } catch (Throwable ignored) {
                // A vocabulary that fails is worth exactly one unnamed stat, never a lost line.
            }
        }
        return DefaultStatNames.name(statId, points);
    }
}
