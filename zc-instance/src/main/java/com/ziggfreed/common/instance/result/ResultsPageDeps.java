package com.ziggfreed.common.instance.result;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The immutable consumer-policy bundle a {@link ResultsPage} is built from: the
 * locale-free {@link ResultsMessages} chrome and the optional {@link ResultsActions}
 * footer handlers (View Leaderboard / Play Again). Built once at the consumer's startup;
 * the page stays mod-agnostic.
 */
public final class ResultsPageDeps {

    private final ResultsMessages text;
    @Nullable private final ResultsActions actions;

    public ResultsPageDeps(@Nonnull ResultsMessages text, @Nullable ResultsActions actions) {
        this.text = text;
        this.actions = actions;
    }

    @Nonnull
    public ResultsMessages text() {
        return text;
    }

    @Nullable
    public ResultsActions actions() {
        return actions;
    }
}
