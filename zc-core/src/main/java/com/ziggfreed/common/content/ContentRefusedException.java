package com.ziggfreed.common.content;

import java.util.concurrent.CompletionException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A save the server content pack refused, delivered only on a result's completion (never thrown into
 * a caller): its {@link ContentProblem} says why in words an admin reads, its message says what for
 * the log.
 */
public final class ContentRefusedException extends RuntimeException {

    private final ContentProblem problem;

    public ContentRefusedException(@Nonnull ContentProblem problem, @Nonnull String detail) {
        super(detail);
        this.problem = problem;
    }

    public ContentRefusedException(@Nonnull ContentProblem problem, @Nonnull String detail, @Nullable Throwable cause) {
        super(detail, cause);
        this.problem = problem;
    }

    @Nonnull
    public ContentProblem problem() {
        return problem;
    }

    /**
     * The problem behind a completion's failure: the refusal's own, unwrapped from the {@link
     * CompletionException} a chained stage hands on, or {@link ContentProblem#IO} for anything else.
     */
    @Nonnull
    public static ContentProblem problemOf(@Nullable Throwable failure) {
        Throwable t = failure;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t instanceof ContentRefusedException refused ? refused.problem() : ContentProblem.IO;
    }
}
