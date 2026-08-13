package com.ziggfreed.common.progress.runtime;

import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * Whatever per-call context a consumer's own listeners need published AROUND an engine call.
 *
 * <p>The engines' outbound events carry an id and nothing else, deliberately: an event is a fact,
 * not a callback with the caller's world attached. A consumer that reacts to one - a toast, a sound,
 * a follow-on grant - usually needs more than the id, and resolves it from context its own facade
 * published before it called in. A surface that calls the engine DIRECTLY publishes no such context,
 * so the same claim made from a shared surface pays out silently while the one made from the
 * consumer's own menu does everything.
 *
 * <p>Wrapping every mutating call in the registered scope is what makes those two identical. A
 * consumer registers a scope that publishes whatever its listeners read; a server with no consumer
 * runs {@link #DIRECT} and pays nothing.
 */
@FunctionalInterface
public interface ProgressionCallScope {

    /** Runs the body with nothing published around it. */
    ProgressionCallScope DIRECT = new ProgressionCallScope() {

        @Override
        public <T> T around(@Nonnull Subject subject, @Nonnull Function<Subject, T> body) {
            return body.apply(subject);
        }
    };

    /**
     * Run {@code body} for {@code subject} with this scope's context in place, and return whatever
     * it returned. An implementation MUST call the body exactly once and MUST NOT swallow its
     * exceptions: this publishes context, it never decides whether the call happens.
     */
    <T> T around(@Nonnull Subject subject, @Nonnull Function<Subject, T> body);

    /** The void form, for a call with nothing to return. */
    default void run(@Nonnull Subject subject, @Nonnull Consumer<Subject> body) {
        around(subject, s -> {
            body.accept(s);
            return null;
        });
    }
}
