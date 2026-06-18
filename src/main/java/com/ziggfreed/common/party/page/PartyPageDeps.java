package com.ziggfreed.common.party.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.party.PartyService;

/**
 * The immutable consumer-policy bundle a {@link PartyInvitePage} is built from (the
 * dialogue page's {@code DialoguePageDeps} twin): the {@link PartyService} the page
 * drives, the locale-free {@link PartyScreenMessages} chrome, and an optional
 * {@link PartyQueueHandler} for the Queue button. Built once at the consumer's startup
 * and reused for every page, so the page stays mod-agnostic.
 */
public final class PartyPageDeps {

    private final PartyService service;
    private final PartyScreenMessages text;
    @Nullable private final PartyQueueHandler queueHandler;

    public PartyPageDeps(@Nonnull PartyService service, @Nonnull PartyScreenMessages text,
                         @Nullable PartyQueueHandler queueHandler) {
        this.service = service;
        this.text = text;
        this.queueHandler = queueHandler;
    }

    @Nonnull
    public PartyService service() {
        return service;
    }

    @Nonnull
    public PartyScreenMessages text() {
        return text;
    }

    /** The Queue-button handoff, or {@code null} when the page should not offer queueing. */
    @Nullable
    public PartyQueueHandler queueHandler() {
        return queueHandler;
    }
}
