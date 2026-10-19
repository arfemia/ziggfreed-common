package com.ziggfreed.common.cast;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

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
 * @deprecated a moment another mod may hook is a native event: make the event an {@code IEvent<Void>}
 *     POJO, fire it through zc-core's {@code event/NativeEventSeam} (guarded, built only when somebody
 *     listens), and register each listener on the engine bus through its plugin's
 *     {@code getEventRegistry().register(Event.class, listener)}. The bus already runs every listener
 *     whatever another throws (it logs the throw at SEVERE); a listener wanting a quieter line of its
 *     own wraps itself in a try/catch. Removed in a later release.
 */
@Deprecated(since = "2.6.0")
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
        SafeLog.warn((label != null ? "[" + label + "] " : "[observer] ") + message);
    }
}
