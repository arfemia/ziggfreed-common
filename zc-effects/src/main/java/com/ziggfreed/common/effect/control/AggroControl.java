package com.ziggfreed.common.effect.control;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Who the creatures near an entity go after, the one way the family does it (the {@code ZigTaunt},
 * {@code ZigAggroDrop} and {@code ZigAggroSuppress} Types, Kweebec Nightmare's hunters, the MMO's {@code MmoTaunt}
 * and {@code MmoAggroSuppress}). Three windows, one per source at a time, a newer call replacing an older one:
 *
 * <ul>
 *   <li>{@link #taunt}: for the window, every creature in reach that is not friendly toward the taunter (its own
 *       attitude, asked of the game's other providers, is hostile or neutral) treats the taunter as hostile, marks it
 *       in its default target slot ({@code LockedTarget}) and remembers it as its closest hostile.</li>
 *   <li>{@link #suppress}: for the window, every creature in reach ignores the source and forgets it: its marked
 *       target slots holding the source are cleared, the source leaves its target memory, and its combat evaluator
 *       lets go of it.</li>
 *   <li>{@link #drop}: the same forgetting once, on the next tick, with no attitude after it, so a creature still
 *       close by may find the source again. A drop over an open suppress changes nothing (the suppress already does
 *       it every tick).</li>
 * </ul>
 *
 * <p><b>Attitudes through the native seam.</b> The hostile and ignore attitudes are this library's provider on the
 * world's {@code AttitudeView}, registered just ahead of the game's own override memory
 * ({@code IAttitudeProvider.OVERRIDE_PRIORITY} minus one), the seam the game's reputation attitude uses, never a write
 * into a creature's private override memory. A creature's cached attitude refreshes within a tenth of a second.
 *
 * <p>Reach is a radius in blocks around where the source stands each tick; 0 or less is the whole world. Each tick
 * {@code AggroSweepSystem} walks the world's creatures once for every open window, so the windows follow their
 * source as it moves. The window is kept in the world's own store and nothing goes on the source entity, so a
 * source that dies or leaves simply ends its window.
 *
 * <p>World thread only. Every call takes a {@link ComponentAccessor} of the source's world (a {@code CommandBuffer}
 * or the {@code Store}) and answers false or zero, never a throw.
 */
public final class AggroControl {

    private AggroControl() {
    }

    /**
     * Pull every creature within {@code radius} blocks onto {@code taunter} for {@code seconds}.
     *
     * @return true when the window opened
     */
    public static boolean taunt(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> taunter,
            float seconds, double radius) {
        return openTimed(accessor, taunter, AggroWindow.Kind.TAUNT, seconds, radius);
    }

    /**
     * Make every creature within {@code radius} blocks ignore and forget {@code source} for {@code seconds}.
     *
     * @return true when the window opened
     */
    public static boolean suppress(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> source,
            float seconds, double radius) {
        return openTimed(accessor, source, AggroWindow.Kind.SUPPRESS, seconds, radius);
    }

    /**
     * Make every creature within {@code radius} blocks forget {@code source} once, on the next tick.
     *
     * @return true when the drop is queued, or an open suppress already covers it
     */
    public static boolean drop(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> source,
            double radius) {
        AggroWindows windows = windowsFor(accessor, source);
        if (windows == null) {
            return false;
        }
        AggroWindow open = windows.get(source);
        if (open != null && open.kind() == AggroWindow.Kind.SUPPRESS) {
            return true;
        }
        windows.open(new AggroWindow(source, AggroWindow.Kind.DROP, radius, 0L));
        return true;
    }

    /**
     * End {@code source}'s open taunt or suppress early.
     *
     * @return true when there was one to end
     */
    public static boolean clear(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> source) {
        AggroWindows windows = windowsFor(accessor, source);
        return windows != null && windows.close(source);
    }

    /** Whether {@code taunter} has a taunt open right now. */
    public static boolean isTaunting(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> taunter) {
        return openKind(accessor, taunter) == AggroWindow.Kind.TAUNT;
    }

    /** Whether {@code source} has a suppress open right now. */
    public static boolean isSuppressed(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> source) {
        return openKind(accessor, source) == AggroWindow.Kind.SUPPRESS;
    }

    /** The seconds left on {@code source}'s open taunt or suppress, 0 when it has none. */
    public static float remainingSeconds(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> source) {
        AggroWindows windows = windowsFor(accessor, source);
        AggroWindow open = windows == null ? null : windows.get(source);
        long now = System.nanoTime();
        return open == null || !open.isLive(now) ? 0f : open.remainingSeconds(now);
    }

    private static boolean openTimed(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> source, @Nonnull AggroWindow.Kind kind, float seconds, double radius) {
        if (!(seconds > 0f)) {
            return false;
        }
        AggroWindows windows = windowsFor(accessor, source);
        if (windows == null) {
            return false;
        }
        long endsAt = System.nanoTime() + (long) (seconds * 1_000_000_000.0);
        windows.open(new AggroWindow(source, kind, radius, endsAt));
        return true;
    }

    @Nullable
    private static AggroWindow.Kind openKind(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> source) {
        AggroWindows windows = windowsFor(accessor, source);
        AggroWindow open = windows == null ? null : windows.get(source);
        return open == null || !open.isLive(System.nanoTime()) ? null : open.kind();
    }

    /** The windows of {@code source}'s world, or null for a missing source or an unreadable resource. */
    @Nullable
    private static AggroWindows windowsFor(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> source) {
        if (source == null || !source.isValid()) {
            return null;
        }
        return AggroWindows.of(accessor);
    }
}
