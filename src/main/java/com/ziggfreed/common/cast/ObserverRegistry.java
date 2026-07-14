package com.ziggfreed.common.cast;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * A generic, guarded observer registry: register {@code Consumer<E>} listeners and {@link #fire}
 * an event to all of them, with a PER-LISTENER try/catch so one misbehaving listener can neither
 * throw out of {@code fire} nor block the listeners after it. Backed by a
 * {@link CopyOnWriteArrayList} so registration is safe against a concurrent fire.
 *
 * <p>A consumer holds one instance per event type (e.g. an "ability cast" event, an "encounter
 * started" event) and fires it from its own dispatch point. The registry is not unregister-aware
 * by design - listeners are expected to live for the plugin lifetime; add teardown only if a real
 * use case needs it.
 *
 * <p><b>Per-consumer instance:</b> an INSTANTIABLE registry, not a static singleton, so two mods
 * sharing this jar never see each other's listeners.
 *
 * @param <E> the event type
 */
public final class ObserverRegistry<E> {

    @Nullable private final String label;
    private final CopyOnWriteArrayList<Consumer<E>> observers = new CopyOnWriteArrayList<>();

    public ObserverRegistry() {
        this.label = null;
    }

    /**
     * @param label an optional short tag prefixed to the guarded warn when a listener throws (so a
     *              consumer can identify which registry misfired); may be null.
     */
    public ObserverRegistry(@Nullable String label) {
        this.label = label;
    }

    /** Register a listener. */
    public void register(@Nonnull Consumer<E> observer) {
        observers.add(observer);
    }

    /**
     * Fire {@code event} to every registered listener in registration order. A listener that throws
     * is caught + logged (guarded warn) and the remaining listeners still run.
     */
    public void fire(@Nonnull E event) {
        for (Consumer<E> observer : observers) {
            try {
                observer.accept(event);
            } catch (Throwable t) {
                warn("observer threw: " + t.getMessage());
            }
        }
    }

    private void warn(@Nonnull String message) {
        try {
            String prefix = label != null ? "[ziggfreed-common][" + label + "] " : "[ziggfreed-common][observer] ";
            ZiggfreedCommonPlugin.LOGGER.atWarning().log(prefix + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
