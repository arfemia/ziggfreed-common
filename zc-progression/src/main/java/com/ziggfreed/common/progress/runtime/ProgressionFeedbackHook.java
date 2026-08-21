package com.ziggfreed.common.progress.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * "This lifecycle moment just happened to this subject, and here is what was in scope." A generic
 * reaction seam over both engines, carrying a free-string moment id and a plain argument map so
 * nothing on either side has to learn the other's vocabulary.
 *
 * <p><b>Why it is not the native events.</b> The outbound events name the subject by uuid alone, so
 * a listener on the bus has to find the player again before it can react - which is exactly the
 * layer this seam removes. A hook is called INSIDE the engine, while the {@link Subject} that owns
 * the transition is still in hand, so a reaction reads the handle the consumer attached and acts on
 * it directly.
 *
 * <p><b>Contributions STACK.</b> Every registered hook is called on every moment, each inside its
 * own guard, so one broken reaction costs its own and nobody else's; registration order is not a
 * precedence and a hook registered after the engines were built still fires, because the engines
 * call through a live forwarder that walks whatever is registered right now.
 *
 * <p><b>The argument map is one map, not two.</b> A value that is itself localized goes in as a
 * {@code Message} and stays one all the way to the client; anything that is plain data - an id, an
 * item name, a count - goes in as a String or a number. Both survive the same map because the
 * message factory binds a nested {@code Message} as a nested param and wraps anything else as a raw
 * one, so a reader never has to know which kind it was handed. An argument nobody could supply is
 * OMITTED rather than passed as null, which is what lets a reader tell "nothing to say here" apart
 * from "say this, and it happens to be empty".
 *
 * <p>Called on the world thread, on the hot path of a lifecycle transition. Keep a hook to
 * presentation work; anything expensive belongs behind the consumer's own queue.
 */
@FunctionalInterface
public interface ProgressionFeedbackHook {

    /** Reacts to nothing, and says so: the composed answer on a runtime nobody registered into. */
    ProgressionFeedbackHook NONE = new ProgressionFeedbackHook() {

        @Override
        public void fire(@Nonnull String momentId, @Nonnull Subject subject,
                @Nonnull Map<String, Object> args) {
        }

        @Override
        public boolean listening() {
            return false;
        }

        @Override
        public boolean answers(@Nonnull String momentId) {
            return false;
        }
    };

    /**
     * One lifecycle moment.
     *
     * @param momentId what happened, as a stable free string (for instance {@code Quest_Completed})
     * @param subject  who it happened to, with the consumer's own handle still attached
     * @param args     what was in scope, keyed; see the class javadoc for the one-map convention
     */
    void fire(@Nonnull String momentId, @Nonnull Subject subject, @Nonnull Map<String, Object> args);

    /**
     * Is anything going to react at all? Only {@link #NONE} says no, and a live forwarder answers
     * for whatever is registered right now. A caller uses it to skip work it would only have done
     * for a reader, and this interface uses it to say ONCE that a moment went nowhere - a library
     * seam that nobody filled is otherwise silent in exactly the way that never gets noticed.
     */
    default boolean listening() {
        return true;
    }

    /**
     * Would this hook react to THIS moment in particular? The honest default for a hook that cannot
     * tell in advance is yes; a hook that reads authored files knows the answer for free and says
     * so, which is what lets a producer carry an EXPENSIVE value for a moment nobody answers
     * without paying to build it.
     *
     * <p>It is an optimisation and never a decision: a hook answering yes and then doing nothing is
     * perfectly correct, and one answering no must be able to prove it, since the producer takes it
     * at its word and never calls.
     */
    default boolean answers(@Nonnull String momentId) {
        return true;
    }

    /**
     * A hook whose reaction and whose {@link #answers} question live in two different places -
     * which is the shape a consumer that reads authored feedback files has, since the module holding
     * those files may not be the module that knows what a lifecycle moment is.
     */
    @Nonnull
    static ProgressionFeedbackHook of(@Nonnull ProgressionFeedbackHook hook,
            @Nonnull Predicate<String> answers) {
        return new ProgressionFeedbackHook() {

            @Override
            public void fire(@Nonnull String momentId, @Nonnull Subject subject,
                    @Nonnull Map<String, Object> args) {
                hook.fire(momentId, subject, args);
            }

            @Override
            public boolean answers(@Nonnull String momentId) {
                return answers.test(momentId);
            }
        };
    }

    /**
     * Fire {@code momentId} with an argument map built from alternating key/value pairs, dropping
     * any pair whose value is null, and swallow whatever the reaction does with it.
     *
     * <p>The whole point is that a call site inside an engine stays ONE line and can never be the
     * thing that breaks a state transition: a feedback asset somebody mistyped must cost its own
     * toast and nothing else. A trailing key with no value is ignored rather than half-applied.
     *
     * <p><b>A value may be a {@link Supplier}, and then it is only asked for if somebody answers
     * this moment.</b> That is how a moment on a hot path - one announced on every objective tick,
     * so on every block broken and every mob killed - can still carry a rendered sentence: the
     * sentence is composed when a reader exists and never otherwise. A supplier that throws or
     * answers null drops its own argument, exactly as a null value does.
     */
    static void fire(@Nullable ProgressionFeedbackHook hook, @Nonnull Consumer<String> warn,
            @Nonnull String momentId, @Nonnull Subject subject, @Nonnull Object... keyValues) {
        fire(hook, warn, momentId, subject, Map.of(), keyValues);
    }

    /**
     * {@link #fire(ProgressionFeedbackHook, Consumer, String, Subject, Object...)} with a map of
     * values CARRIED by the content the moment is about laid under the engine's own pairs: the
     * pairs are bound after the map, so an engine's own name wins on a clash and a fold can never
     * shadow {@code title} or {@code icon}. A null value in the map is dropped like a null pair.
     */
    static void fire(@Nullable ProgressionFeedbackHook hook, @Nonnull Consumer<String> warn,
            @Nonnull String momentId, @Nonnull Subject subject, @Nonnull Map<String, ?> carried,
            @Nonnull Object... keyValues) {
        if (hook == null) {
            return;
        }
        if (!hook.listening()) {
            if (ProgressionParts.FEEDBACK_SILENCE_REPORTED.compareAndSet(false, true)) {
                warn.accept("no feedback hook is installed, so lifecycle moments such as '"
                        + momentId + "' announce to nobody; a consumer that wants toasts, sounds or"
                        + " banners for them registers one through the progression registrar");
            }
            return;
        }
        try {
            if (!hook.answers(momentId)) {
                return;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : carried.entrySet()) {
                Object value = resolve(entry.getValue());
                if (value != null && entry.getKey() != null) {
                    args.put(entry.getKey(), value);
                }
            }
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                Object value = resolve(keyValues[i + 1]);
                if (value != null) {
                    args.put(String.valueOf(keyValues[i]), value);
                }
            }
            hook.fire(momentId, subject, args);
        } catch (Throwable t) {
            warn.accept("the feedback moment '" + momentId + "' failed: " + t.getMessage());
        }
    }

    /** One argument, with a deferred one asked for; anything it cannot answer drops that argument. */
    @Nullable
    private static Object resolve(@Nullable Object value) {
        if (!(value instanceof Supplier<?> deferred)) {
            return value;
        }
        try {
            return deferred.get();
        } catch (Throwable t) {
            return null;
        }
    }
}
