package com.ziggfreed.common.effect.control;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * One world's open aggro windows, kept as an ECS resource of that world's entity store, so a world that goes away
 * takes its windows with it and nothing else holds them. Keyed by the source's entity index; the newest window sorts
 * last, so where two taunts reach one creature the later one wins the sweep. Beside them, the creatures pinned onto
 * one taunter each ({@link Pin}), which outrank every window on the creature they hold. World thread only.
 */
public final class AggroWindows implements Resource<EntityStore> {

    private static volatile ResourceType<EntityStore, AggroWindows> type;

    private static final AtomicBoolean WARNED_MISSING = new AtomicBoolean();

    private final Map<Integer, AggroWindow> windows = new LinkedHashMap<>();

    /**
     * One creature pinned onto a taunter until a time ({@link AggroControl#tauntOne}), kept by the creature's entity
     * index: a creature holds one pin at a time, the newest. Whether the creature took it (its own attitude toward
     * the taunter is hostile or neutral, as an area taunt asks) is recorded by the sweep, and only a pin it took
     * makes the attitude provider answer hostile, so a friendly creature the holder struck never turns on them.
     */
    static final class Pin {

        @Nonnull private final Ref<EntityStore> creature;
        @Nonnull private final Ref<EntityStore> taunter;
        private final long endsAtNanos;
        private boolean taken;

        Pin(@Nonnull Ref<EntityStore> creature, @Nonnull Ref<EntityStore> taunter, long endsAtNanos) {
            this.creature = creature;
            this.taunter = taunter;
            this.endsAtNanos = endsAtNanos;
        }

        @Nonnull
        Ref<EntityStore> creature() {
            return creature;
        }

        @Nonnull
        Ref<EntityStore> taunter() {
            return taunter;
        }

        /** Did the last sweep find the creature willing to take the taunt? */
        boolean taken() {
            return taken;
        }

        void taken(boolean took) {
            taken = took;
        }

        /**
         * Still running at {@code nowNanos}: both refs live (a taunter that left its world's store is gone) and time
         * left. A dead taunter ends it too, which the sweep reads ({@link #endedPins}).
         */
        boolean isLive(long nowNanos) {
            return creature.isValid() && taunter.isValid() && nowNanos < endsAtNanos;
        }
    }

    private final Map<Integer, Pin> pins = new LinkedHashMap<>();

    /**
     * True while the sweep asks the other attitude providers what a creature thinks of a taunter without this
     * library's answer (a taunt never turns a friendly creature), so the provider stands aside for that one call.
     */
    boolean muted;

    /** Name the registered resource type. Called once, from {@link ControlBootstrap}. */
    static void install(@Nonnull ResourceType<EntityStore, AggroWindows> registered) {
        type = registered;
    }

    /**
     * The windows of the world {@code accessor} belongs to, or null when the resource is not registered (reported
     * once) or cannot be read.
     */
    @Nullable
    static AggroWindows of(@Nonnull ComponentAccessor<EntityStore> accessor) {
        ResourceType<EntityStore, AggroWindows> registered = type;
        if (registered == null) {
            if (WARNED_MISSING.compareAndSet(false, true)) {
                SafeLog.warn("[control] the aggro windows were not registered at setup, so taunts and aggro drops do"
                        + " nothing this boot");
            }
            return null;
        }
        try {
            return accessor.getResource(registered);
        } catch (Throwable t) {
            SafeLog.fine("[control] could not read the aggro windows: " + t.getMessage());
            return null;
        }
    }

    /** Whether two live refs name one entity in one store (a gone entity's index may already be reused). */
    static boolean sameEntity(@Nullable Ref<EntityStore> a, @Nullable Ref<EntityStore> b) {
        if (a == null || b == null || !a.isValid() || !b.isValid()) {
            return false;
        }
        return a == b || (a.getIndex() == b.getIndex() && a.getStore() == b.getStore());
    }

    boolean isEmpty() {
        return windows.isEmpty();
    }

    /** Open {@code window} for its source, replacing any it had, as the newest. */
    void open(@Nonnull AggroWindow window) {
        Integer key = window.source().getIndex();
        windows.remove(key);
        windows.put(key, window);
    }

    /** The window {@code source} has open, or null. */
    @Nullable
    AggroWindow get(@Nullable Ref<EntityStore> source) {
        if (source == null || windows.isEmpty()) {
            return null;
        }
        AggroWindow window = windows.get(source.getIndex());
        return window != null && window.isFor(source) ? window : null;
    }

    /** Close the window {@code source} has open. */
    boolean close(@Nullable Ref<EntityStore> source) {
        AggroWindow window = get(source);
        return window != null && windows.remove(source.getIndex(), window);
    }

    /** Drop every window that has ended or whose source is gone, and answer the rest, oldest first. */
    @Nonnull
    List<AggroWindow> live(long nowNanos) {
        List<AggroWindow> live = new ArrayList<>(windows.size());
        Iterator<AggroWindow> it = windows.values().iterator();
        while (it.hasNext()) {
            AggroWindow window = it.next();
            if (window.isLive(nowNanos)) {
                live.add(window);
            } else {
                it.remove();
            }
        }
        return live;
    }

    /** Close every one-pass drop: its sweep has run. */
    void closeDrops() {
        windows.values().removeIf(window -> window.kind() == AggroWindow.Kind.DROP);
    }

    /** Is any creature pinned? */
    boolean hasPins() {
        return !pins.isEmpty();
    }

    /** Pin a creature, replacing any pin it had. */
    void pin(@Nonnull Pin pin) {
        pins.put(pin.creature().getIndex(), pin);
    }

    /** The live pin on {@code creature}, or null. */
    @Nullable
    Pin pinOn(@Nullable Ref<EntityStore> creature) {
        if (creature == null || pins.isEmpty()) {
            return null;
        }
        Pin pin = pins.get(creature.getIndex());
        return pin != null && sameEntity(pin.creature(), creature) && pin.isLive(System.nanoTime()) ? pin : null;
    }

    /**
     * Take every pin that has ended out (its time is up, a ref is gone, or {@code taunterDown} says its taunter is
     * down: dead), and answer those whose creature still stands (to let go of its taunter).
     */
    @Nonnull
    List<Pin> endedPins(long nowNanos, @Nonnull Predicate<Ref<EntityStore>> taunterDown) {
        if (pins.isEmpty()) {
            return List.of();
        }
        List<Pin> ended = new ArrayList<>();
        Iterator<Pin> it = pins.values().iterator();
        while (it.hasNext()) {
            Pin pin = it.next();
            if (!pin.isLive(nowNanos) || taunterDown.test(pin.taunter())) {
                it.remove();
                if (pin.creature().isValid()) {
                    ended.add(pin);
                }
            }
        }
        return ended;
    }

    @Nonnull
    @Override
    public Resource<EntityStore> clone() {
        AggroWindows copy = new AggroWindows();
        copy.windows.putAll(windows);
        copy.pins.putAll(pins);
        return copy;
    }
}
