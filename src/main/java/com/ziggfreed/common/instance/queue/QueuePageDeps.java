package com.ziggfreed.common.instance.queue;

import javax.annotation.Nonnull;

import com.ziggfreed.common.lobby.LobbyService;

/**
 * The immutable consumer-policy bundle a {@link QueuePage} is built from: the
 * {@link LobbyService} the page reads its live queue from (via
 * {@code currentQueueOf(viewer)}) and the locale-free {@link QueueScreenMessages}
 * chrome. Built once at the consumer's startup; the page stays mod-agnostic.
 */
public final class QueuePageDeps {

    private final LobbyService lobby;
    private final QueueScreenMessages text;

    public QueuePageDeps(@Nonnull LobbyService lobby, @Nonnull QueueScreenMessages text) {
        this.lobby = lobby;
        this.text = text;
    }

    @Nonnull
    public LobbyService lobby() {
        return lobby;
    }

    @Nonnull
    public QueueScreenMessages text() {
        return text;
    }
}
